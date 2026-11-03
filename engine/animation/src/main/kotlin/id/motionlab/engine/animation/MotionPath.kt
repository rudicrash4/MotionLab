package id.motionlab.engine.animation

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One anchor of a motion path with its two Bezier handles. Handles are offsets relative to the point
 * ([inDx]/[inDy] point backwards, [outDx]/[outDy] forwards). Coordinates use the canvas system of [Mat3].
 */
data class PathPoint(
    val id: String,
    val x: Double,
    val y: Double,
    val inDx: Double = 0.0,
    val inDy: Double = 0.0,
    val outDx: Double = 0.0,
    val outDy: Double = 0.0,
) {
    init {
        require(id.isNotBlank()) { "path point id must not be blank" }
        require(listOf(x, y, inDx, inDy, outDx, outDy).all { it.isFinite() }) { "path point values must be finite" }
    }
}

data class PathSample(val x: Double, val y: Double, val tangentDeg: Double)

/**
 * A piecewise cubic Bezier path through [points]. [pointAt] is parameterised by ARC LENGTH, so progress 0..1
 * moves at constant speed; speed changes come from keyframing [PropertyNames.MOTION_PROGRESS], i.e. through the
 * same keyframe system as every other property.
 *
 * Immutable: every edit returns a new path.
 */
class MotionPath(val points: List<PathPoint>, val rotateAlongPath: Boolean = false) {
    init {
        if (points.size < 2) throw MlException(ErrorCode.INVALID_ARGUMENT, "A motion path needs at least 2 points")
        val ids = points.map { it.id }
        if (ids.size != ids.toSet().size) throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate path point ids")
    }

    private val segmentCount = points.size - 1

    // cumulative arc length at each sample; sampled per segment
    private val sampleT = DoubleArray(segmentCount * SAMPLES + 1)
    private val sampleLen = DoubleArray(segmentCount * SAMPLES + 1)

    /** Total length in pixels. */
    val lengthPx: Double

    init {
        var acc = 0.0
        var prevX = points[0].x
        var prevY = points[0].y
        var idx = 1
        for (s in 0 until segmentCount) {
            for (i in 1..SAMPLES) {
                val u = i.toDouble() / SAMPLES
                val px = cubic(s, u, true)
                val py = cubic(s, u, false)
                acc += hypot(px - prevX, py - prevY)
                prevX = px
                prevY = py
                sampleT[idx] = s + u
                sampleLen[idx] = acc
                idx++
            }
        }
        lengthPx = acc
    }

    private fun ctrl(s: Int, which: Int, isX: Boolean): Double {
        val p0 = points[s]
        val p1 = points[s + 1]
        return when (which) {
            0 -> if (isX) p0.x else p0.y
            1 -> if (isX) p0.x + p0.outDx else p0.y + p0.outDy
            2 -> if (isX) p1.x + p1.inDx else p1.y + p1.inDy
            else -> if (isX) p1.x else p1.y
        }
    }

    private fun cubic(s: Int, u: Double, isX: Boolean): Double {
        val v = 1 - u
        return v * v * v * ctrl(s, 0, isX) + 3 * v * v * u * ctrl(s, 1, isX) +
            3 * v * u * u * ctrl(s, 2, isX) + u * u * u * ctrl(s, 3, isX)
    }

    private fun derivative(s: Int, u: Double, isX: Boolean): Double {
        val v = 1 - u
        return 3 * v * v * (ctrl(s, 1, isX) - ctrl(s, 0, isX)) +
            6 * v * u * (ctrl(s, 2, isX) - ctrl(s, 1, isX)) +
            3 * u * u * (ctrl(s, 3, isX) - ctrl(s, 2, isX))
    }

    /** Position and heading at [progress] (0..1 of the total arc length; clamped). */
    fun pointAt(progress: Double): PathSample {
        val p = progress.coerceIn(0.0, 1.0)
        val target = p * lengthPx
        // binary search in the cumulative length table
        var lo = 0
        var hi = sampleLen.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (sampleLen[mid] <= target) lo = mid else hi = mid
        }
        val span = sampleLen[hi] - sampleLen[lo]
        val f = if (span <= 1e-12) 0.0 else (target - sampleLen[lo]) / span
        val param = sampleT[lo] + (sampleT[hi] - sampleT[lo]) * f
        val seg = param.toInt().coerceIn(0, segmentCount - 1)
        val u = (param - seg).coerceIn(0.0, 1.0)
        val x = cubic(seg, u, true)
        val y = cubic(seg, u, false)
        var dx = derivative(seg, u, true)
        var dy = derivative(seg, u, false)
        if (hypot(dx, dy) < 1e-9) {
            // Zero-length handle at a segment end: fall back to the chord so the heading is still defined.
            dx = points[seg + 1].x - points[seg].x
            dy = points[seg + 1].y - points[seg].y
        }
        return PathSample(x, y, Math.toDegrees(atan2(dy, dx)))
    }

    /** Polyline for drawing the path preview: [stepsPerSegment] points per segment, including both ends. */
    fun flatten(stepsPerSegment: Int = 24): List<Pair<Double, Double>> {
        require(stepsPerSegment >= 1)
        val out = ArrayList<Pair<Double, Double>>(segmentCount * stepsPerSegment + 1)
        out += points[0].x to points[0].y
        for (s in 0 until segmentCount) {
            for (i in 1..stepsPerSegment) {
                val u = i.toDouble() / stepsPerSegment
                out += cubic(s, u, true) to cubic(s, u, false)
            }
        }
        return out
    }

    fun point(id: String): PathPoint? = points.firstOrNull { it.id == id }

    // ---- editing (each returns a new path) ----

    fun addPoint(point: PathPoint, index: Int = points.size): MotionPath {
        if (point(point.id) != null) throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate path point ${point.id}")
        val i = index.coerceIn(0, points.size)
        return MotionPath(points.take(i) + point + points.drop(i), rotateAlongPath)
    }

    fun removePoint(id: String): MotionPath {
        if (point(id) == null) throw MlException(ErrorCode.NOT_FOUND, "Unknown path point $id")
        return MotionPath(points.filter { it.id != id }, rotateAlongPath) // throws if fewer than 2 remain
    }

    fun updatePoint(updated: PathPoint): MotionPath {
        if (point(updated.id) == null) throw MlException(ErrorCode.NOT_FOUND, "Unknown path point ${updated.id}")
        return MotionPath(points.map { if (it.id == updated.id) updated else it }, rotateAlongPath)
    }

    fun withRotateAlongPath(enabled: Boolean) = MotionPath(points, enabled)

    override fun equals(other: Any?) = other is MotionPath && other.points == points && other.rotateAlongPath == rotateAlongPath
    override fun hashCode() = 31 * points.hashCode() + rotateAlongPath.hashCode()

    companion object {
        private const val SAMPLES = 256

        /**
         * A smooth path through the given positions with automatically computed handles (Catmull-Rom style,
         * tension 1/6), a good starting point for the user to edit.
         */
        fun smoothThrough(positions: List<Pair<Double, Double>>, idPrefix: String = "pp"): MotionPath {
            if (positions.size < 2) throw MlException(ErrorCode.INVALID_ARGUMENT, "A motion path needs at least 2 points")
            val pts = positions.mapIndexed { i, (x, y) ->
                val prev = positions[maxOf(i - 1, 0)]
                val next = positions[minOf(i + 1, positions.size - 1)]
                val tx = (next.first - prev.first) / 6.0
                val ty = (next.second - prev.second) / 6.0
                PathPoint(
                    "$idPrefix$i", x, y,
                    inDx = if (i == 0) 0.0 else -tx, inDy = if (i == 0) 0.0 else -ty,
                    outDx = if (i == positions.size - 1) 0.0 else tx, outDy = if (i == positions.size - 1) 0.0 else ty,
                )
            }
            return MotionPath(pts)
        }
    }
}
