package id.motionlab.engine.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMathTest {
    @Test fun handlesRoundTripThroughBezier() {
        val b = Interpolation.Bezier(0.25, 0.1, 0.75, 1.3)
        val (h1, h2) = GraphMath.handles(1_000_000, 10.0, 3_000_000, 50.0, b)!!
        val back = GraphMath.bezierFromHandles(1_000_000, 10.0, 3_000_000, 50.0, h1, h2)!!
        assertEquals(b.x1, back.x1, 1e-9); assertEquals(b.y1, back.y1, 1e-9)
        assertEquals(b.x2, back.x2, 1e-9); assertEquals(b.y2, back.y2, 1e-9)
    }

    @Test fun handleXIsClampedSoTheCurveStaysAFunctionOfTime() {
        val back = GraphMath.bezierFromHandles(0, 0.0, 1_000_000, 1.0, GraphPoint(-500_000.0, 0.2), GraphPoint(2_000_000.0, 0.8))!!
        assertEquals(0.0, back.x1, 0.0)
        assertEquals(1.0, back.x2, 0.0)
    }

    @Test fun handlesAreNotEditableWhenTheValueDoesNotChange() {
        assertNull(GraphMath.handles(0, 5.0, 1_000_000, 5.0, Easing.EASE_IN))
        assertNull(GraphMath.bezierFromHandles(0, 5.0, 1_000_000, 5.0, GraphPoint(0.0, 0.0), GraphPoint(1.0, 1.0)))
    }

    @Test fun overshootIsRepresentable() {
        val b = GraphMath.bezierFromHandles(0, 0.0, 1_000_000, 10.0, GraphPoint(100_000.0, -5.0), GraphPoint(900_000.0, 15.0))!!
        assertEquals(-0.5, b.y1, 1e-9)
        assertEquals(1.5, b.y2, 1e-9)
    }

    @Test fun fitRangesCoverTheKeyframes() {
        val t = KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(2_000_000, 10.0)))
        val (lo, hi) = GraphMath.fitValueRange(t)
        assertTrue(lo < 0.0 && hi > 10.0)
        val (a, b) = GraphMath.fitTimeRange(t)
        assertTrue(a < 0 && b > 2_000_000)
        val single = KeyframeTrack.of(listOf(Keyframe(1_000_000, 3.0)))
        val (l2, h2) = GraphMath.fitValueRange(single)
        assertTrue(h2 > l2)
        assertNotNull(GraphMath.fitTimeRange(single))
        assertEquals(0L to 1_000_000L, GraphMath.fitTimeRange(KeyframeTrack.EMPTY))
    }
}
