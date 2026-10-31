package id.motionlab.engine.animation

/**
 * How a value moves from one keyframe to the next. The interpolation stored on a keyframe describes
 * the segment that STARTS at that keyframe.
 */
sealed interface Interpolation {
    /** Value stays at the keyframe's value until the next keyframe (step). */
    data object Hold : Interpolation

    data object Linear : Interpolation

    /**
     * CSS-style cubic-bezier easing with fixed end points (0,0) and (1,1) and control points
     * (x1,y1), (x2,y2). x values must be within 0..1 so the curve is a function of time;
     * y values may overshoot (anticipation / bounce).
     */
    data class Bezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Interpolation {
        init {
            require(x1 in 0.0..1.0 && x2 in 0.0..1.0) { "Bezier x control values must be within 0..1" }
            require(!x1.isNaN() && !y1.isNaN() && !x2.isNaN() && !y2.isNaN()) { "Bezier values must not be NaN" }
        }
    }
}

/** Common easing presets. */
object Easing {
    val EASE_IN = Interpolation.Bezier(0.42, 0.0, 1.0, 1.0)
    val EASE_OUT = Interpolation.Bezier(0.0, 0.0, 0.58, 1.0)
    val EASE_IN_OUT = Interpolation.Bezier(0.42, 0.0, 0.58, 1.0)
}

/** Solves cubic-bezier easing: progress (x, 0..1) -> eased value (y). */
object BezierEasing {
    fun solve(b: Interpolation.Bezier, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        return sample(b.y1, b.y2, solveParameterForX(b, x))
    }

    private fun sample(c1: Double, c2: Double, t: Double): Double {
        // B(t) with P0 = 0, P3 = 1: 3(1-t)^2 t c1 + 3(1-t) t^2 c2 + t^3
        val u = 1.0 - t
        return 3.0 * u * u * t * c1 + 3.0 * u * t * t * c2 + t * t * t
    }

    private fun derivative(c1: Double, c2: Double, t: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * c1 + 6.0 * u * t * (c2 - c1) + 3.0 * t * t * (1.0 - c2)
    }

    private fun solveParameterForX(b: Interpolation.Bezier, x: Double): Double {
        // Newton-Raphson first (fast), bisection as a guaranteed fallback.
        var t = x
        for (i in 0 until 8) {
            val err = sample(b.x1, b.x2, t) - x
            if (kotlin.math.abs(err) < EPSILON) return t
            val d = derivative(b.x1, b.x2, t)
            if (kotlin.math.abs(d) < 1e-6) break
            t -= err / d
        }
        var lo = 0.0
        var hi = 1.0
        t = x
        for (i in 0 until 60) {
            val v = sample(b.x1, b.x2, t)
            if (kotlin.math.abs(v - x) < EPSILON) return t
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2.0
        }
        return t
    }

    private const val EPSILON = 1e-7
}
