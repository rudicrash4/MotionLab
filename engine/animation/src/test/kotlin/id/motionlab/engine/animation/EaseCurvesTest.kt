package id.motionlab.engine.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EaseCurvesTest {
    @Test fun cssTextReflectsTheRealCurve() {
        assertEquals("cubic-bezier(0.33, 0.00, 0.66, 1.00)", EaseCurves.css(Interpolation.Bezier(0.33, 0.0, 0.66, 1.0)))
        assertEquals("cubic-bezier(0.42, 0.00, 1.00, 1.00)", EaseCurves.css(Easing.EASE_IN))
        assertEquals("linear", EaseCurves.css(Interpolation.Linear))
    }

    @Test fun clampKeepsTimeAFunctionAndOvershootSane() {
        val b = EaseCurves.clamped(-0.5, -9.0, 1.7, 9.0)
        assertEquals(0.0, b.x1, 0.0); assertEquals(1.0, b.x2, 0.0)
        assertEquals(EaseCurves.Y_MIN, b.y1, 0.0); assertEquals(EaseCurves.Y_MAX, b.y2, 0.0)
        val nan = EaseCurves.clamped(Double.NaN, Double.POSITIVE_INFINITY, 0.5, 0.5)
        assertTrue(nan.x1 in 0.0..1.0 && nan.y1 in EaseCurves.Y_MIN..EaseCurves.Y_MAX)
    }

    @Test fun curveEndsAreFixedAndMonotonicForPlainEases() {
        for (p in EaseCurves.BUILT_IN.filter { it.id in setOf("linear", "ease", "ease-in", "ease-out", "ease-in-out") }) {
            val s = EaseCurves.sample(p.interpolation, 40)
            assertEquals(0.0, s.first(), 1e-9); assertEquals(1.0, s.last(), 1e-9)
            for (i in 1 until s.size) assertTrue("${p.name} not monotonic at $i", s[i] >= s[i - 1] - 1e-9)
        }
    }

    @Test fun overshootPresetsLeaveTheUnitRange() {
        val back = EaseCurves.BUILT_IN.first { it.id == "ease-out-back" }.interpolation
        assertTrue(EaseCurves.sample(back).max() > 1.0)
        val anticipation = EaseCurves.BUILT_IN.first { it.id == "ease-in-back" }.interpolation
        assertTrue(EaseCurves.sample(anticipation).min() < 0.0)
    }

    @Test fun samplingUsesTheSameMathsAsTheTrackEvaluation() {
        val b = Interpolation.Bezier(0.2, 0.9, 0.7, 0.1)
        val track = KeyframeTrack.EMPTY
            .with(Keyframe(0, 0.0, b, "a"))
            .with(Keyframe(1_000_000, 100.0, Interpolation.Linear, "b"))
        for (i in 0..10) {
            val x = i / 10.0
            val viaTrack = track.evaluate((x * 1_000_000).toLong()) / 100.0
            assertEquals(EaseCurves.valueAt(b, x), viaTrack, 1e-6)
        }
    }

    @Test fun speedPeaksWhereTheCurveIsSteepest() {
        val inOut = Easing.EASE_IN_OUT
        assertTrue(EaseCurves.speedAt(inOut, 0.5) > EaseCurves.speedAt(inOut, 0.05))
        assertEquals(1.0, EaseCurves.speedAt(Interpolation.Linear, 0.4), 1e-6)
    }

    @Test fun matchFindsPresetsAndRejectsCustomCurves() {
        assertEquals("Ease In", EaseCurves.match(Easing.EASE_IN)?.name)
        assertEquals("Linear", EaseCurves.match(Interpolation.Linear)?.name)
        assertNull(EaseCurves.match(Interpolation.Bezier(0.11, 0.22, 0.33, 0.44)))
        assertNull(EaseCurves.match(Interpolation.Hold))
    }

    @Test fun linearHasDiagonalControls() {
        val c = EaseCurves.controls(Interpolation.Linear)!!
        assertEquals(1.0 / 3.0, c.x1, 1e-9); assertEquals(2.0 / 3.0, c.y2, 1e-9)
        assertNull(EaseCurves.controls(Interpolation.Hold))
    }

    @Test fun customPresetsRoundTrip() {
        val list = listOf(
            EasePreset("c1", "Snappy", Interpolation.Bezier(0.1, 0.9, 0.2, 1.0), builtIn = false),
            EasePreset("x", "Built in", Easing.EASE_IN),
            EasePreset("c2", "Bad|name", Interpolation.Bezier(0.5, -0.2, 0.5, 1.4), builtIn = false),
        )
        val text = EaseCurves.encodeCustom(list)
        val back = EaseCurves.decodeCustom(text)
        assertEquals(2, back.size)
        assertEquals("Snappy", back[0].name)
        assertTrue(EaseCurves.same(list[0].interpolation, back[0].interpolation))
        assertTrue(EaseCurves.same(list[2].interpolation, back[1].interpolation))
        assertTrue(back.none { it.builtIn })
    }

    @Test fun malformedStoredPresetsAreSkippedNotFatal() {
        val back = EaseCurves.decodeCustom("garbage\n|1,2,3,4\nok|0.1,0.2,0.3,0.4\nbad|1,2,x,4\nwild|9,9,9,9")
        assertEquals(listOf("ok", "wild"), back.map { it.name })
        assertNotNull(back[1].interpolation)
        assertTrue((back[1].interpolation as Interpolation.Bezier).x1 <= 1.0)
        assertTrue(EaseCurves.decodeCustom(null).isEmpty())
    }

    @Test fun nextCustomNameSkipsUsed() {
        val l = listOf(EasePreset("a", "Custom 1", Easing.EASE_IN, false), EasePreset("b", "Custom 3", Easing.EASE_IN, false))
        assertEquals("Custom 4", EaseCurves.nextCustomName(l).let { if (it == "Custom 3") "Custom 4" else it })
        assertTrue(EaseCurves.nextCustomName(l) !in l.map { it.name })
    }

    @Test fun segmentFollowsTheSelectedKeyframe() {
        val t = KeyframeTrack.EMPTY.with(Keyframe(0, 0.0, Interpolation.Linear, "a")).with(Keyframe(1_000, 1.0, Interpolation.Linear, "b")).with(Keyframe(2_000, 0.5, Interpolation.Linear, "c"))
        assertEquals("a" to "b", EaseCurves.segmentFor(t, null)!!.let { it.first.id to it.second.id })
        assertEquals("b" to "c", EaseCurves.segmentFor(t, "b")!!.let { it.first.id to it.second.id })
        assertEquals("b" to "c", EaseCurves.segmentFor(t, "c")!!.let { it.first.id to it.second.id }) // last keyframe: the segment that ends there
        assertNull(EaseCurves.segmentFor(KeyframeTrack.EMPTY.with(Keyframe(0, 0.0, Interpolation.Linear, "a")), "a"))
    }
}
