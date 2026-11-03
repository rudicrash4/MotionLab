package id.motionlab.core.task

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSchedulerTest {
    private val scheduler = TaskScheduler(threads = 1, threadNamePrefix = "test")

    @After
    fun tearDown() = scheduler.shutdown()

    @Test
    fun returnsResultAndReportsFinalProgress() {
        val seen = Collections.synchronizedList(ArrayList<Float>())
        val h = scheduler.submit("sum", onProgress = { _, f -> seen += f }) { ctx ->
            ctx.reportProgress(0.5f)
            21 * 2
        }
        val result = h.await()
        assertEquals(42, (result as MlResult.Success).value)
        assertEquals(TaskState.SUCCEEDED, h.state)
        assertEquals(1f, h.progress, 0f)
        assertEquals(0.5f, seen.first(), 0f)
    }

    @Test
    fun progressIsClamped() {
        val h = scheduler.submit("clamp") { ctx ->
            ctx.reportProgress(7f)
            ctx.reportProgress(-3f)
        }
        h.await()
        // final SUCCEEDED forces 1.0
        assertEquals(1f, h.progress, 0f)
    }

    @Test
    fun failureIsCapturedNotThrown() {
        val h = scheduler.submit<Int>("bad") { throw IllegalStateException("nope") }
        val r = h.await() as MlResult.Failure
        assertEquals(ErrorCode.UNKNOWN, r.error.code)
        assertEquals(TaskState.FAILED, h.state)
    }

    @Test
    fun higherPriorityRunsFirst() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        val blocker = scheduler.submit("blocker") { started.countDown(); gate.await() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val low = scheduler.submit("low", TaskPriority.LOW) { order += "low" }
        val normal = scheduler.submit("normal", TaskPriority.NORMAL) { order += "normal" }
        val high = scheduler.submit("high", TaskPriority.HIGH) { order += "high" }
        val normal2 = scheduler.submit("normal2", TaskPriority.NORMAL) { order += "normal2" }
        gate.countDown()
        listOf(blocker, low, normal, high, normal2).forEach { it.await() }
        assertEquals(listOf("high", "normal", "normal2", "low"), order)
    }

    @Test
    fun pendingTaskCancelledNeverRuns() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        var ran = false
        val blocker = scheduler.submit("blocker") { started.countDown(); gate.await() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val victim = scheduler.submit("victim") { ran = true }
        assertTrue(victim.cancel())
        assertEquals(TaskState.CANCELLED, victim.state)
        gate.countDown()
        blocker.await()
        val r = victim.await() as MlResult.Failure
        assertEquals(ErrorCode.CANCELLED, r.error.code)
        assertFalse(ran)
    }

    @Test
    fun runningTaskObservesCancellation() {
        val started = CountDownLatch(1)
        val h = scheduler.submit<Unit>("loop") { ctx ->
            started.countDown()
            while (true) {
                ctx.throwIfCancelled()
                Thread.sleep(2)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(h.cancel())
        val r = h.awaitFor(5, TimeUnit.SECONDS)
        assertNotNull(r)
        assertEquals(ErrorCode.CANCELLED, (r as MlResult.Failure).error.code)
        assertEquals(TaskState.CANCELLED, h.state)
    }

    @Test
    fun cancelAfterCompletionReturnsFalse() {
        val h = scheduler.submit("quick") { 1 }
        h.await()
        assertFalse(h.cancel())
        assertEquals(TaskState.SUCCEEDED, h.state)
    }

    @Test
    fun awaitForReturnsNullOnTimeout() {
        val gate = CountDownLatch(1)
        val h = scheduler.submit("slow") { gate.await() }
        assertNull(h.awaitFor(50, TimeUnit.MILLISECONDS))
        gate.countDown()
        h.await()
    }

    @Test
    fun shutdownReleasesPendingWaiters() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        scheduler.submit("blocker") { started.countDown(); gate.await() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val pending = scheduler.submit("pending") { 1 }
        gate.countDown()
        scheduler.shutdown()
        val r = pending.awaitFor(5, TimeUnit.SECONDS)
        assertNotNull(r) // either ran before shutdown completed or was cancelled; never hangs
    }
}
