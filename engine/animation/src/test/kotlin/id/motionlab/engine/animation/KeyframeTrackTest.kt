package id.motionlab.engine.animation

import id.motionlab.core.common.MlException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyframeTrackTest {
    private val eps = 1e-6

    private fun track(vararg k: Keyframe) = KeyframeTrack.of(k.toList())

    @Test
    fun emptyTrackReturnsDefault() {
        assertEquals(5.0, KeyframeTrack.EMPTY.evaluate(100, default = 5.0), eps)
    }

    @Test
    fun linearInterpolatesAndClampsOutsideRange() {
        val t = track(Keyframe(1_000, 10.0), Keyframe(3_000, 30.0))
        assertEquals(10.0, t.evaluate(0), eps)
        assertEquals(10.0, t.evaluate(1_000), eps)
        assertEquals(20.0, t.evaluate(2_000), eps)
        assertEquals(25.0, t.evaluate(2_500), eps)
        assertEquals(30.0, t.evaluate(3_000), eps)
        assertEquals(30.0, t.evaluate(99_000), eps)
    }

    @Test
    fun holdStepsAtNextKeyframe() {
        val t = track(Keyframe(0, 1.0, Interpolation.Hold), Keyframe(1_000, 9.0))
        assertEquals(1.0, t.evaluate(999), eps)
        assertEquals(9.0, t.evaluate(1_000), eps)
    }

    @Test
    fun interpolationOfSegmentComesFromItsStartKeyframe() {
        val t = track(
            Keyframe(0, 0.0, Interpolation.Hold),
            Keyframe(1_000, 10.0, Interpolation.Linear),
            Keyframe(2_000, 20.0),
        )
        assertEquals(0.0, t.evaluate(500), eps)
        assertEquals(15.0, t.evaluate(1_500), eps)
    }

    @Test
    fun bezierPresetsAreSymmetricAndMonotonic() {
        assertEquals(0.5, BezierEasing.solve(Easing.EASE_IN_OUT, 0.5), 1e-5)
        var prev = 0.0
        for (i in 1..100) {
            val y = BezierEasing.solve(Easing.EASE_IN_OUT, i / 100.0)
            assertTrue("monotonic at $i", y >= prev - 1e-9)
            prev = y
        }
        assertEquals(0.0, BezierEasing.solve(Easing.EASE_IN, 0.0), eps)
        assertEquals(1.0, BezierEasing.solve(Easing.EASE_OUT, 1.0), eps)
        // ease-in starts slower than linear, ease-out faster
        assertTrue(BezierEasing.solve(Easing.EASE_IN, 0.25) < 0.25)
        assertTrue(BezierEasing.solve(Easing.EASE_OUT, 0.25) > 0.25)
    }

    @Test
    fun linearEquivalentBezierMatchesLinear() {
        val linear = Interpolation.Bezier(1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0)
        for (i in 0..10) assertEquals(i / 10.0, BezierEasing.solve(linear, i / 10.0), 1e-5)
    }

    @Test
    fun overshootingBezierExceedsRange() {
        val back = Interpolation.Bezier(0.34, 1.56, 0.64, 1.0)
        val t = track(Keyframe(0, 0.0, back), Keyframe(1_000, 100.0))
        val max = (1..999).maxOf { t.evaluate(it.toLong()) }
        assertTrue("expected overshoot, max=$max", max > 100.0)
        assertEquals(100.0, t.evaluate(1_000), eps)
    }

    @Test
    fun withReplacesSameTimeAndKeepsOrder() {
        val t = KeyframeTrack.EMPTY
            .with(Keyframe(2_000, 2.0))
            .with(Keyframe(1_000, 1.0))
            .with(Keyframe(2_000, 5.0))
        assertEquals(listOf(1_000L, 2_000L), t.keyframes.map { it.timeUs })
        assertEquals(5.0, t.evaluate(2_000), eps)
        assertEquals(listOf(2_000L), t.without(1_000).keyframes.map { it.timeUs })
    }

    @Test
    fun strictFactoryRejectsUnsortedAndDuplicates() {
        try {
            KeyframeTrack.of(listOf(Keyframe(2, 0.0), Keyframe(1, 0.0)))
            fail()
        } catch (_: MlException) {
        }
        try {
            KeyframeTrack.of(listOf(Keyframe(1, 0.0), Keyframe(1, 0.0)))
            fail()
        } catch (_: MlException) {
        }
        assertEquals(2, KeyframeTrack.ofUnsorted(listOf(Keyframe(2, 0.0), Keyframe(1, 0.0), Keyframe(1, 7.0))).keyframes.size)
    }

    @Test
    fun invalidBezierAndValuesRejected() {
        try {
            Interpolation.Bezier(1.5, 0.0, 0.5, 1.0)
            fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            Keyframe(0, Double.NaN)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun propertySetEvaluatesAnimatedAndDefaultValues() {
        val props = AnimatedProperties.NONE.with(
            PropertyNames.OPACITY,
            track(Keyframe(0, 0.0), Keyframe(1_000, 1.0)),
        )
        val values = props.evaluate(500, defaults = mapOf(PropertyNames.OPACITY to 1.0, PropertyNames.SCALE_X to 1.0))
        assertEquals(0.5, values.getValue(PropertyNames.OPACITY), eps)
        assertEquals(1.0, values.getValue(PropertyNames.SCALE_X), eps)
    }
}
