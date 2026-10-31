package id.motionlab.engine.rendering

data class FrameStatsSnapshot(
    val totalFrames: Long,
    /** Frames that were skipped because a frame took clearly longer than the target period (see [FrameStats.record]). */
    val droppedFrames: Long,
    /** Number of frames the averages below are computed from (at most the window size). */
    val windowFrames: Int,
    val fps: Double,
    /** Time between the starts of consecutive frames (includes the wait for vsync). */
    val avgFrameMs: Double,
    val p95FrameMs: Double,
    val maxFrameMs: Double,
    /** CPU time spent recording GL commands for the frame, before eglSwapBuffers. Not GPU time. */
    val avgCpuMs: Double,
    /** Time eglSwapBuffers blocked. Includes the wait for vsync, so it is NOT GPU time either. */
    val avgSwapMs: Double,
    val targetFps: Double,
)

/**
 * Rolling frame statistics. [record] is called from the render thread once per frame and does not allocate;
 * [snapshot] may be called from any thread (it allocates, so call it a few times per second, not per frame).
 */
class FrameStats(private val windowSize: Int = 120) {
    init {
        require(windowSize >= 2) { "windowSize must be >= 2" }
    }

    private val intervals = LongArray(windowSize)
    private val cpu = LongArray(windowSize)
    private val swap = LongArray(windowSize)
    private var count = 0
    private var next = 0
    private var total = 0L
    private var dropped = 0L
    private var targetPeriodNs = 16_666_667L

    @Synchronized
    fun setTargetPeriod(periodNs: Long) {
        require(periodNs > 0) { "periodNs must be positive" }
        targetPeriodNs = periodNs
    }

    /**
     * @param intervalNs time since the previous frame started
     * @param cpuNs time spent before swapBuffers
     * @param swapNs time swapBuffers blocked
     *
     * A frame counts as dropped when its interval exceeds 1.5x the target period; an interval of N periods
     * then counts as N-1 dropped frames.
     */
    @Synchronized
    fun record(intervalNs: Long, cpuNs: Long, swapNs: Long) {
        intervals[next] = intervalNs
        cpu[next] = cpuNs
        swap[next] = swapNs
        next = (next + 1) % windowSize
        if (count < windowSize) count++
        total++
        if (intervalNs > targetPeriodNs * 3 / 2) {
            dropped += (intervalNs + targetPeriodNs / 2) / targetPeriodNs - 1
        }
    }

    @Synchronized
    fun reset() {
        count = 0
        next = 0
        total = 0
        dropped = 0
    }

    @Synchronized
    fun snapshot(): FrameStatsSnapshot {
        val n = count
        if (n == 0) {
            return FrameStatsSnapshot(total, dropped, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1e9 / targetPeriodNs)
        }
        var sumInterval = 0L
        var sumCpu = 0L
        var sumSwap = 0L
        var max = 0L
        val sorted = LongArray(n)
        for (i in 0 until n) {
            sumInterval += intervals[i]
            sumCpu += cpu[i]
            sumSwap += swap[i]
            if (intervals[i] > max) max = intervals[i]
            sorted[i] = intervals[i]
        }
        sorted.sort()
        val p95Index = (kotlin.math.ceil(0.95 * n).toInt() - 1).coerceIn(0, n - 1)
        val avgInterval = sumInterval.toDouble() / n
        return FrameStatsSnapshot(
            totalFrames = total,
            droppedFrames = dropped,
            windowFrames = n,
            fps = if (avgInterval > 0) 1e9 / avgInterval else 0.0,
            avgFrameMs = avgInterval / 1e6,
            p95FrameMs = sorted[p95Index] / 1e6,
            maxFrameMs = max / 1e6,
            avgCpuMs = sumCpu.toDouble() / n / 1e6,
            avgSwapMs = sumSwap.toDouble() / n / 1e6,
            targetFps = 1e9 / targetPeriodNs,
        )
    }
}
