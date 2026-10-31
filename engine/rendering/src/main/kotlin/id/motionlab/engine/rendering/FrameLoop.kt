package id.motionlab.engine.rendering

import id.motionlab.core.common.MlLog
import java.util.concurrent.locks.LockSupport

/** Draws one frame. Called on the render thread with the context current; do not call swap, [FrameLoop] does. */
fun interface FrameRenderer {
    fun renderFrame(ctx: RenderContext, frameTimeNs: Long, width: Int, height: Int)
}

/** Everything [FrameLoop] measured. All values come from System.nanoTime on this device; nothing is estimated. */
data class LoopDiagnostics(
    val stats: FrameStatsSnapshot,
    val refreshHz: Float,
    val swapInterval: Int,
    /** Frames where the driver did not block on vsync and the loop slept to keep the target rate. */
    val pacingSleeps: Long,
    val renderErrors: Long,
    /** Ticks skipped because there was no window surface. */
    val idleTicks: Long,
    val running: Boolean,
)

/**
 * Drives [renderer] from the render thread itself, paced by eglSwapInterval (the swap blocks until the display is
 * ready), instead of the old UI-thread Choreographer callback that posted one task per frame to the render thread.
 * That design had three problems: it competed with UI-thread jank, it queued frames when rendering was slow
 * (latency grows, motion looks laggy) and it ran at the display rate even when the device could not keep up.
 *
 * Per frame: render -> swap (blocks for vsync) -> record timing -> maybe adapt the rate (see [AdaptiveFrameRate]).
 * Pacing guard: if swap returned in less than half the target period, the driver is not throttling (some
 * emulators), so the loop sleeps the remainder instead of spinning at 100% CPU.
 * Without a window surface the loop idles at 20 Hz instead of spinning.
 *
 * Animation must be driven by the `frameTimeNs` argument, not by a per-frame increment, so motion speed stays
 * correct when frames are dropped.
 */
class FrameLoop(
    private val thread: RenderThread,
    refreshHz: Float,
    private val renderer: FrameRenderer,
    maxFps: Int = 60,
    minFps: Int = 30,
) {
    private val refresh = if (refreshHz in 10f..500f) refreshHz else 60f
    private val adaptive = AdaptiveFrameRate(refresh, maxFps, minFps)
    private val stats = FrameStats()
    private val statsLock = Any()

    @Volatile
    var width = 1
        private set

    @Volatile
    var height = 1
        private set

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    @Volatile
    private var tick: Runnable? = null

    // Render thread only:
    private var lastEndNs = 0L
    private var appliedInterval = 0
    private var consecutiveErrors = 0

    @Volatile
    private var pacingSleeps = 0L

    @Volatile
    private var renderErrors = 0L

    @Volatile
    private var idleTicks = 0L

    fun setViewport(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running || thread.isReleased) return
        running = true
        generation++
        val gen = generation
        lastEndNs = 0L
        appliedInterval = 0
        consecutiveErrors = 0
        synchronized(statsLock) { stats.reset() }
        val task = Runnable { step(gen) }
        tick = task
        thread.postRaw(task)
    }

    @Synchronized
    fun stop() {
        running = false
        tick?.let { thread.removeRaw(it) }
        tick = null
    }

    fun diagnostics(): LoopDiagnostics = LoopDiagnostics(
        stats = synchronized(statsLock) { stats.snapshot() },
        refreshHz = refresh,
        swapInterval = adaptive.swapInterval,
        pacingSleeps = pacingSleeps,
        renderErrors = renderErrors,
        idleTicks = idleTicks,
        running = running,
    )

    private fun step(gen: Int) {
        if (!running || gen != generation || thread.isReleased) return
        val t0 = System.nanoTime()
        val ctx = try {
            thread.currentContext()
        } catch (e: Exception) {
            MlLog.e("FrameLoop", "no render context, stopping", e)
            running = false
            return
        }
        if (!thread.status().hasWindowSurface) {
            // Nothing to present to: do not burn CPU/GPU on invisible frames.
            idleTicks++
            lastEndNs = 0L
            reschedule(gen, IDLE_DELAY_MS)
            return
        }
        if (appliedInterval != adaptive.swapInterval) {
            ctx.setSwapInterval(adaptive.swapInterval)
            appliedInterval = adaptive.swapInterval
            synchronized(statsLock) { stats.setTargetPeriod(adaptive.targetPeriodNs) }
        }

        try {
            renderer.renderFrame(ctx, t0, width, height)
            consecutiveErrors = 0
        } catch (e: Exception) {
            renderErrors++
            consecutiveErrors++
            MlLog.e("FrameLoop", "renderFrame failed ($consecutiveErrors in a row)", e)
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                MlLog.e("FrameLoop", "too many consecutive render errors, stopping the loop")
                running = false
                return
            }
            lastEndNs = 0L
            reschedule(gen, 16)
            return
        }

        val t1 = System.nanoTime()
        val result = ctx.swap()
        val t2 = System.nanoTime()

        when (result) {
            SwapResult.OK -> {
                var end = t2
                val last = lastEndNs
                if (last != 0L) {
                    val period = adaptive.targetPeriodNs
                    val sinceLast = t2 - last
                    if (sinceLast < period / 2) {
                        pacingSleeps++
                        LockSupport.parkNanos(period - sinceLast)
                        end = System.nanoTime()
                    }
                    val interval = end - last
                    synchronized(statsLock) { stats.record(interval, t1 - t0, t2 - t1) }
                    if (adaptive.onFrame(interval, t1 - t0)) {
                        MlLog.i("FrameLoop", "target changed to ${adaptive.targetFps} fps (swap interval ${adaptive.swapInterval})")
                    }
                }
                lastEndNs = end
                reschedule(gen, 0)
            }
            SwapResult.NO_SURFACE, SwapResult.SURFACE_LOST -> {
                idleTicks++
                lastEndNs = 0L
                reschedule(gen, IDLE_DELAY_MS)
            }
            SwapResult.CONTEXT_LOST -> {
                lastEndNs = 0L
                reschedule(gen, 0) // the next step creates the new context
            }
        }
    }

    private fun reschedule(gen: Int, delayMs: Long) {
        val task = tick ?: return
        if (!running || gen != generation) return
        if (delayMs <= 0) thread.postRaw(task) else thread.postRawDelayed(task, delayMs)
    }

    private companion object {
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val IDLE_DELAY_MS = 50L
    }
}
