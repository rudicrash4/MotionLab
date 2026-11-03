package id.motionlab.core.task

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.common.MlResult
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class TaskPriority(internal val weight: Int) { LOW(0), NORMAL(1), HIGH(2), URGENT(3) }

enum class TaskState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

fun interface ProgressListener {
    fun onProgress(taskId: Long, fraction: Float)
}

/** Passed to task bodies so they can cooperate with cancellation and report progress. */
interface TaskContext {
    val isCancelled: Boolean

    /** Throws [CancellationException] if the task was cancelled. Call it in long loops. */
    fun throwIfCancelled()

    /** [fraction] is clamped to 0..1. */
    fun reportProgress(fraction: Float)
}

class TaskHandle<T> internal constructor(
    val id: Long,
    val name: String,
    val priority: TaskPriority,
    private val listener: ProgressListener?,
) {
    internal val stateRef = AtomicReference(TaskState.PENDING)
    private val resultRef = AtomicReference<MlResult<T>?>(null)
    private val latch = CountDownLatch(1)

    @Volatile
    internal var cancelRequested = false

    @Volatile
    internal var runner: Thread? = null

    @Volatile
    var progress: Float = 0f
        private set

    val state: TaskState get() = stateRef.get()
    val isDone: Boolean get() = latch.count == 0L

    internal fun updateProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        progress = clamped
        try {
            listener?.onProgress(id, clamped)
        } catch (e: Exception) {
            MlLog.w("TaskScheduler", "progress listener of '$name' threw", e)
        }
    }

    @Volatile
    internal var onFinished: Runnable? = null

    internal fun complete(finalState: TaskState, result: MlResult<T>) {
        if (resultRef.compareAndSet(null, result)) {
            stateRef.set(finalState)
            if (finalState == TaskState.SUCCEEDED) progress = 1f
            latch.countDown()
            onFinished?.run()
        }
    }

    /**
     * Requests cancellation. A pending task never runs; a running task sees [TaskContext.isCancelled]
     * and its thread is interrupted. Returns false if the task had already finished.
     */
    fun cancel(): Boolean {
        cancelRequested = true
        if (stateRef.compareAndSet(TaskState.PENDING, TaskState.CANCELLED)) {
            complete(TaskState.CANCELLED, cancelledResult())
            return true
        }
        if (stateRef.get() == TaskState.RUNNING) {
            runner?.interrupt()
            return true
        }
        return false
    }

    internal fun cancelledResult(): MlResult<T> =
        MlResult.Failure(MlException(ErrorCode.CANCELLED, "Task '$name' was cancelled"))

    /** Blocks until the task finishes. */
    fun await(): MlResult<T> {
        latch.await()
        return resultRef.get()!!
    }

    /** Returns null when [timeout] elapses first. */
    fun awaitFor(timeout: Long, unit: TimeUnit): MlResult<T>? =
        if (latch.await(timeout, unit)) resultRef.get() else null
}

/**
 * Priority-ordered background executor with cancellation and progress reporting.
 * Higher [TaskPriority] runs first; equal priorities run in submission order.
 */
class TaskScheduler(
    threads: Int = maxOf(2, Runtime.getRuntime().availableProcessors() - 1),
    threadNamePrefix: String = "ml-task",
) : AutoCloseable {
    private val sequence = AtomicLong(0)
    private val ids = AtomicLong(0)
    private val threadCounter = AtomicInteger(0)
    private val live: MutableSet<TaskHandle<*>> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private class PrioritizedRunnable(
        val weight: Int,
        val seq: Long,
        val body: Runnable,
    ) : Runnable, Comparable<PrioritizedRunnable> {
        override fun run() = body.run()
        override fun compareTo(other: PrioritizedRunnable): Int =
            if (weight != other.weight) other.weight.compareTo(weight) else seq.compareTo(other.seq)
    }

    private val executor = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(),
        ThreadFactory { r ->
            Thread(r, "$threadNamePrefix-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    fun <T> submit(
        name: String,
        priority: TaskPriority = TaskPriority.NORMAL,
        onProgress: ProgressListener? = null,
        block: (TaskContext) -> T,
    ): TaskHandle<T> {
        val handle = TaskHandle<T>(ids.incrementAndGet(), name, priority, onProgress)
        live.add(handle)
        handle.onFinished = Runnable { live.remove(handle) }
        val context = object : TaskContext {
            override val isCancelled: Boolean get() = handle.cancelRequested
            override fun throwIfCancelled() {
                if (handle.cancelRequested) throw CancellationException("Task '$name' cancelled")
            }

            override fun reportProgress(fraction: Float) = handle.updateProgress(fraction)
        }
        val body = Runnable {
            if (!handle.stateRef.compareAndSet(TaskState.PENDING, TaskState.RUNNING)) return@Runnable
            handle.runner = Thread.currentThread()
            try {
                if (handle.cancelRequested) throw CancellationException("Task '$name' cancelled")
                val value = block(context)
                handle.complete(TaskState.SUCCEEDED, MlResult.Success(value))
            } catch (e: CancellationException) {
                handle.complete(TaskState.CANCELLED, handle.cancelledResult())
            } catch (e: InterruptedException) {
                handle.complete(TaskState.CANCELLED, handle.cancelledResult())
            } catch (e: MlException) {
                handle.complete(TaskState.FAILED, MlResult.Failure(e))
            } catch (e: Exception) {
                handle.complete(
                    TaskState.FAILED,
                    MlResult.Failure(MlException(ErrorCode.UNKNOWN, e.message ?: e.javaClass.simpleName, e)),
                )
            } finally {
                handle.runner = null
                Thread.interrupted() // never leak an interrupt into the pooled thread
            }
        }
        try {
            executor.execute(PrioritizedRunnable(priority.weight, sequence.incrementAndGet(), body))
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            handle.complete(
                TaskState.FAILED,
                MlResult.Failure(MlException(ErrorCode.INVALID_ARGUMENT, "Scheduler is shut down", e)),
            )
        }
        return handle
    }

    /** Stops accepting tasks and waits up to [timeoutMs] for running tasks. Pending tasks are cancelled. */
    fun shutdown(timeoutMs: Long = 2000) {
        executor.shutdown()
        // Pending tasks will never run: cancel them so that await() callers are released.
        for (handle in live.toList()) {
            if (handle.state == TaskState.PENDING) handle.cancel()
        }
        executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() = shutdown()
}
