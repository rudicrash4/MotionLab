package id.motionlab.engine.animation

/** A point in graph space: time in microseconds, value in property units. */
data class GraphPoint(val timeUs: Double, val value: Double)

/**
 * Maths of the graph editor's bezier handles. The handles are just another view of the same
 * [Interpolation.Bezier] stored on the keyframe: nothing here is stored separately.
 * A segment runs from keyframe A (t0, v0) to keyframe B (t1, v1); the easing control points are in unit space
 * (x = time fraction, y = value fraction).
 */
object GraphMath {
    /** True when handles are meaningful: time must advance and the value must change. */
    fun editable(t0: Long, v0: Double, t1: Long, v1: Double): Boolean = t1 > t0 && v1 != v0

    /** Outgoing handle of A and incoming handle of B in graph space. Null when [editable] is false. */
    fun handles(t0: Long, v0: Double, t1: Long, v1: Double, b: Interpolation.Bezier): Pair<GraphPoint, GraphPoint>? {
        if (!editable(t0, v0, t1, v1)) return null
        val dt = (t1 - t0).toDouble()
        val dv = v1 - v0
        return GraphPoint(t0 + b.x1 * dt, v0 + b.y1 * dv) to GraphPoint(t0 + b.x2 * dt, v0 + b.y2 * dv)
    }

    /** Inverse of [handles]: x is clamped to 0..1 (the curve must stay a function of time), y may overshoot. */
    fun bezierFromHandles(t0: Long, v0: Double, t1: Long, v1: Double, h1: GraphPoint, h2: GraphPoint): Interpolation.Bezier? {
        if (!editable(t0, v0, t1, v1)) return null
        val dt = (t1 - t0).toDouble()
        val dv = v1 - v0
        return Interpolation.Bezier(
            ((h1.timeUs - t0) / dt).coerceIn(0.0, 1.0),
            (h1.value - v0) / dv,
            ((h2.timeUs - t0) / dt).coerceIn(0.0, 1.0),
            (h2.value - v0) / dv,
        )
    }

    /** Value range that shows every keyframe and the curve between them with some padding; never degenerate. */
    fun fitValueRange(track: KeyframeTrack, samples: Int = 64): Pair<Double, Double> {
        val kfs = track.keyframes
        if (kfs.isEmpty()) return -1.0 to 1.0
        var lo = kfs.minOf { it.value }
        var hi = kfs.maxOf { it.value }
        if (kfs.size > 1) {
            val a = kfs.first().timeUs
            val b = kfs.last().timeUs
            for (i in 0..samples) {
                val v = track.evaluate(a + (b - a) * i / samples)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        if (hi - lo < 1e-9) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.15
        return (lo - pad) to (hi + pad)
    }

    /** Range of time that shows all keyframes with padding; a single keyframe gets a one-second window. */
    fun fitTimeRange(track: KeyframeTrack): Pair<Long, Long> {
        val kfs = track.keyframes
        if (kfs.isEmpty()) return 0L to 1_000_000L
        var lo = kfs.first().timeUs
        var hi = kfs.last().timeUs
        if (hi - lo < 100_000) { lo -= 500_000; hi += 500_000 }
        val pad = (hi - lo) / 10
        return (lo - pad) to (hi + pad)
    }
}
