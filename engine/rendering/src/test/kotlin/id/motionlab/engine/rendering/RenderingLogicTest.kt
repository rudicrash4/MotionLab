package id.motionlab.engine.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsTest {
    private val ms = 1_000_000L

    @Test
    fun emptyStatsAreZeroNotNaN() {
        val s = FrameStats().snapshot()
        assertEquals(0, s.windowFrames)
        assertEquals(0.0, s.fps, 0.0)
        assertEquals(0L, s.totalFrames)
    }

    @Test
    fun steadySixtyFpsIsReportedAsSuch() {
        val st = FrameStats()
        repeat(100) { st.record(16_666_667L, 3 * ms, 13 * ms) }
        val s = st.snapshot()
        assertEquals(60.0, s.fps, 0.01)
        assertEquals(16.67, s.avgFrameMs, 0.01)
        assertEquals(3.0, s.avgCpuMs, 1e-9)
        assertEquals(13.0, s.avgSwapMs, 1e-9)
        assertEquals(0L, s.droppedFrames)
        assertEquals(100L, s.totalFrames)
    }

    @Test
    fun droppedFramesAreCountedFromIntervalLength() {
        val st = FrameStats()
        st.setTargetPeriod(16_666_667L)
        st.record(25 * ms, 1, 1)   // 1.5 periods exactly: not dropped
        st.record(34 * ms, 1, 1)   // ~2 periods: 1 dropped
        st.record(50 * ms, 1, 1)   // ~3 periods: 2 dropped
        assertEquals(3L, st.snapshot().droppedFrames)
    }

    @Test
    fun windowKeepsOnlyRecentFramesButTotalsKeepCounting() {
        val st = FrameStats(windowSize = 10)
        repeat(10) { st.record(100 * ms, 0, 0) }
        repeat(10) { st.record(20 * ms, 0, 0) }
        val s = st.snapshot()
        assertEquals(10, s.windowFrames)
        assertEquals(20.0, s.avgFrameMs, 1e-9)
        assertEquals(20L, s.totalFrames)
    }

    @Test
    fun p95AndMaxReflectOutliers() {
        val st = FrameStats(windowSize = 100)
        repeat(95) { st.record(16 * ms, 0, 0) }
        repeat(5) { st.record(80 * ms, 0, 0) }
        val s = st.snapshot()
        assertEquals(80.0, s.maxFrameMs, 1e-9)
        assertEquals(16.0, s.p95FrameMs, 1e-9)
        st.record(80 * ms, 0, 0) // push the 95th percentile into the outliers
        assertTrue(st.snapshot().p95FrameMs >= 16.0)
    }

    @Test
    fun resetClearsEverything() {
        val st = FrameStats()
        st.record(40 * ms, 1, 1)
        st.reset()
        val s = st.snapshot()
        assertEquals(0L, s.totalFrames)
        assertEquals(0L, s.droppedFrames)
    }
}

class AdaptiveFrameRateTest {
    private val ms = 1_000_000L

    private fun feed(a: AdaptiveFrameRate, frames: Int, intervalNs: Long, cpuNs: Long): Boolean {
        var changed = false
        repeat(frames) { if (a.onFrame(intervalNs, cpuNs)) changed = true }
        return changed
    }

    @Test
    fun targetsFollowRefreshRate() {
        assertEquals(1, AdaptiveFrameRate(60f).swapInterval)
        assertEquals(2, AdaptiveFrameRate(120f).swapInterval)
        assertEquals(2, AdaptiveFrameRate(90f).swapInterval) // nearest multiple to 60 fps is 45 fps
        assertEquals(60.0, AdaptiveFrameRate(60f).targetFps, 1e-6)
        assertEquals(60.0, AdaptiveFrameRate(120f).targetFps, 1e-6)
    }

    @Test
    fun healthyDeviceStaysAtFullRate() {
        val a = AdaptiveFrameRate(60f)
        assertFalse(feed(a, 600, 16_666_667L, 3 * ms))
        assertEquals(1, a.swapInterval)
    }

    @Test
    fun slowDeviceFallsBackToThirtyAndNoFurther() {
        val a = AdaptiveFrameRate(60f)
        assertTrue(feed(a, AdaptiveFrameRate.WINDOW_FRAMES, 40 * ms, 30 * ms))
        assertEquals(2, a.swapInterval)
        assertEquals(30.0, a.targetFps, 1e-6)
        // Still slow at 30 fps: must not go below minFps.
        assertFalse(feed(a, AdaptiveFrameRate.WINDOW_FRAMES * 5, 80 * ms, 60 * ms))
        assertEquals(2, a.swapInterval)
    }

    @Test
    fun recoversOnlyAfterSustainedHeadroomAndBacksOffAfterFallback() {
        val a = AdaptiveFrameRate(60f)
        feed(a, AdaptiveFrameRate.WINDOW_FRAMES, 40 * ms, 30 * ms) // fall back (requires 6 stable windows afterwards)
        assertEquals(2, a.swapInterval)
        val w = AdaptiveFrameRate.WINDOW_FRAMES
        assertFalse(feed(a, w * 5, 33_333_333L, 4 * ms)) // 5 good windows: not enough
        assertEquals(2, a.swapInterval)
        assertTrue(feed(a, w, 33_333_333L, 4 * ms))       // 6th good window: recover
        assertEquals(1, a.swapInterval)
    }

    @Test
    fun highCpuTimePreventsRecovery() {
        val a = AdaptiveFrameRate(60f)
        feed(a, AdaptiveFrameRate.WINDOW_FRAMES, 40 * ms, 30 * ms)
        // Intervals are fine at 30 fps but CPU time (20 ms) would not fit a 16.7 ms frame.
        assertFalse(feed(a, AdaptiveFrameRate.WINDOW_FRAMES * 30, 33_333_333L, 20 * ms))
        assertEquals(2, a.swapInterval)
    }

    @Test
    fun rejectsInvalidArguments() {
        for (bad in listOf<() -> Unit>(
            { AdaptiveFrameRate(1f) },
            { AdaptiveFrameRate(60f, maxFps = 20, minFps = 30) },
            { AdaptiveFrameRate(60f, maxFps = 60, minFps = 0) },
        )) {
            try {
                bad()
                throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

class LruByteCacheTest {
    private fun cache(max: Long, evicted: MutableList<String>) =
        LruByteCache<String, String>(max, { it.length.toLong() }) { k, _ -> evicted += k }

    @Test
    fun evictsLeastRecentlyUsedUntilNewEntryFits() {
        val ev = ArrayList<String>()
        val c = cache(10, ev)
        c.put("a", "1234")
        c.put("b", "1234")
        c.get("a") // a is now the most recent
        c.put("c", "1234") // 12 > 10 -> evict b
        assertEquals(listOf("b"), ev)
        assertTrue(c.contains("a") && c.contains("c"))
        assertEquals(8L, c.bytes)
    }

    @Test
    fun evictsSeveralWhenNeeded() {
        val ev = ArrayList<String>()
        val c = cache(10, ev)
        c.put("a", "123")
        c.put("b", "123")
        c.put("c", "123")
        c.put("big", "12345678")
        assertEquals(listOf("a", "b", "c"), ev)
        assertEquals(8L, c.bytes)
        assertEquals(1, c.size)
    }

    @Test
    fun oversizedValueIsRejectedWithoutSideEffects() {
        val ev = ArrayList<String>()
        val c = cache(5, ev)
        c.put("a", "123")
        assertFalse(c.put("huge", "123456"))
        assertTrue(ev.isEmpty())
        assertEquals(3L, c.bytes)
    }

    @Test
    fun replacingAKeyDisposesTheOldValue() {
        val ev = ArrayList<String>()
        val c = cache(10, ev)
        c.put("a", "123")
        c.put("a", "12345")
        assertEquals(listOf("a"), ev)
        assertEquals(5L, c.bytes)
        assertEquals(1, c.size)
    }

    @Test
    fun removeHandsBackWithoutEvictCallback() {
        val ev = ArrayList<String>()
        val c = cache(10, ev)
        c.put("a", "123")
        assertEquals("123", c.remove("a"))
        assertNull(c.remove("a"))
        assertTrue(ev.isEmpty())
        assertEquals(0L, c.bytes)
    }

    @Test
    fun clearDisposesEverythingExactlyOnce() {
        val ev = ArrayList<String>()
        val c = cache(100, ev)
        c.put("a", "1")
        c.put("b", "22")
        c.clear()
        assertEquals(setOf("a", "b"), ev.toSet())
        assertEquals(2, ev.size)
        assertEquals(0L, c.bytes)
        assertEquals(0, c.size)
    }
}
