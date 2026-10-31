package id.motionlab.engine.rendering

import kotlin.math.max
import kotlin.math.round

/**
 * Chooses the EGL swap interval so the loop runs at [maxFps] when the device keeps up and falls back towards
 * [minFps] when it does not. The display refresh rate must be an integer multiple of the resulting rate for even
 * pacing, so the target is expressed as a swap interval (vsyncs per frame):
 *
 *   60 Hz -> interval 1 = 60 fps, 2 = 30 fps;  120 Hz -> 2 = 60 fps, 4 = 30 fps;  90 Hz -> 2 = 45 fps (nearest to 60).
 *
 * Fall back after a window where more than 25% of frames were slower than 1.3x the target period.
 * Recover to the faster rate only after several consecutive windows with CPU headroom; every fall back doubles the
 * number of windows required (capped), so a device that cannot sustain the faster rate does not flip-flop.
 * Nothing here measures the GPU; it reacts only to observed frame intervals and CPU time.
 */
class AdaptiveFrameRate(refreshHz: Float, val maxFps: Int = 60, val minFps: Int = 30) {
    init {
        require(refreshHz in 10f..500f) { "refreshHz out of range" }
        require(minFps > 0 && maxFps >= minFps) { "need 0 < minFps <= maxFps" }
    }

    val refreshHz: Float = refreshHz
    private val baseInterval = max(1, round(refreshHz / maxFps).toInt())
    private val maxInterval = max(baseInterval, round(refreshHz / minFps).toInt())

    @Volatile
    var swapInterval: Int = baseInterval
        private set

    val targetFps: Double get() = refreshHz.toDouble() / swapInterval
    val targetPeriodNs: Long get() = (1e9 * swapInterval / refreshHz).toLong()

    private var frames = 0
    private var slowFrames = 0
    private var cpuSumNs = 0L
    private var stableWindows = 0
    private var requiredStableWindows = INITIAL_STABLE_WINDOWS

    /** Feed one frame. Returns true when [swapInterval] changed. Render thread only. */
    fun onFrame(intervalNs: Long, cpuNs: Long): Boolean {
        frames++
        cpuSumNs += cpuNs
        if (intervalNs > targetPeriodNs * 13 / 10) slowFrames++
        if (frames < WINDOW_FRAMES) return false

        val slowRatio = slowFrames.toDouble() / frames
        val cpuAvgNs = cpuSumNs.toDouble() / frames
        frames = 0
        slowFrames = 0
        cpuSumNs = 0

        if (slowRatio > 0.25 && swapInterval < maxInterval) {
            swapInterval++
            stableWindows = 0
            requiredStableWindows = (requiredStableWindows * 2).coerceAtMost(MAX_STABLE_WINDOWS)
            return true
        }
        if (swapInterval > baseInterval) {
            val fasterPeriodNs = 1e9 * (swapInterval - 1) / refreshHz
            if (slowRatio <= 0.05 && cpuAvgNs < fasterPeriodNs * 0.6) stableWindows++ else stableWindows = 0
            if (stableWindows >= requiredStableWindows) {
                swapInterval--
                stableWindows = 0
                return true
            }
        }
        return false
    }

    companion object {
        const val WINDOW_FRAMES = 60
        private const val INITIAL_STABLE_WINDOWS = 3
        private const val MAX_STABLE_WINDOWS = 24
    }
}
