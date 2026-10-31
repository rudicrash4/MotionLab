package id.motionlab.engine.compositing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CPU reference implementation of separable blend modes (W3C Compositing and Blending Level 1 formulas).
 * It defines the expected result for GPU shaders and is used to unit-test them later; it is not a renderer.
 */
enum class BlendMode { NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, ADD }

object Blend {
    /** Blend function B(Cb, Cs) for one color channel; all values are straight (non-premultiplied) 0..1. */
    fun channel(mode: BlendMode, backdrop: Float, source: Float): Float = when (mode) {
        BlendMode.NORMAL -> source
        BlendMode.MULTIPLY -> backdrop * source
        BlendMode.SCREEN -> backdrop + source - backdrop * source
        BlendMode.OVERLAY -> hardLight(source, backdrop) // overlay(Cb, Cs) = hardLight(Cs, Cb)
        BlendMode.DARKEN -> min(backdrop, source)
        BlendMode.LIGHTEN -> max(backdrop, source)
        BlendMode.ADD -> min(1f, backdrop + source)
    }

    private fun hardLight(backdrop: Float, source: Float): Float =
        if (source <= 0.5f) backdrop * (2f * source)
        else {
            val s = 2f * source - 1f
            backdrop + s - backdrop * s
        }

    /**
     * Composites straight-alpha [source] over [backdrop] (both RGBA, 0..1) using [mode],
     * returning straight-alpha RGBA.
     */
    fun rgba(mode: BlendMode, backdrop: FloatArray, source: FloatArray): FloatArray {
        require(backdrop.size == 4 && source.size == 4) { "RGBA arrays must have 4 elements" }
        val ab = backdrop[3]
        val asrc = source[3]
        val ar = asrc + ab * (1f - asrc)
        val out = FloatArray(4)
        out[3] = ar
        if (ar <= 0f) return out
        for (i in 0 until 3) {
            val cb = backdrop[i]
            val cs = source[i]
            val blended = channel(mode, cb, cs)
            val mixedSource = (1f - ab) * cs + ab * blended
            out[i] = ((1f - asrc) * ab * cb + asrc * mixedSource) / ar
        }
        return out
    }
}

/** A mask returns per-point coverage (0 = hidden, 1 = fully visible). Coordinates are normalized 0..1. */
interface Mask {
    fun coverage(x: Float, y: Float): Float
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Rectangle with an inner feather: coverage ramps from 0 at the edge to 1 at [feather] inside it. */
class RectMask(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val feather: Float = 0f,
) : Mask {
    init {
        require(left < right && top < bottom) { "RectMask must have positive size" }
        require(feather >= 0f) { "feather must be >= 0" }
    }

    override fun coverage(x: Float, y: Float): Float {
        if (x < left || x > right || y < top || y > bottom) return 0f
        if (feather == 0f) return 1f
        val edgeDistance = min(min(x - left, right - x), min(y - top, bottom - y))
        return smoothstep(0f, feather, edgeDistance)
    }
}

/** Ellipse with an inner feather expressed in normalized radius units (0 = hard edge). */
class EllipseMask(
    private val cx: Float,
    private val cy: Float,
    private val rx: Float,
    private val ry: Float,
    private val feather: Float = 0f,
) : Mask {
    init {
        require(rx > 0f && ry > 0f) { "radii must be positive" }
        require(feather in 0f..1f) { "feather must be within 0..1" }
    }

    override fun coverage(x: Float, y: Float): Float {
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d > 1f) return 0f
        if (feather == 0f) return 1f
        return 1f - smoothstep(1f - feather, 1f, d)
    }
}

class InvertedMask(private val inner: Mask) : Mask {
    override fun coverage(x: Float, y: Float): Float = 1f - inner.coverage(x, y)
}

/** Multiplies the coverage of several masks (intersection). */
class IntersectionMask(private val masks: List<Mask>) : Mask {
    override fun coverage(x: Float, y: Float): Float {
        var c = 1f
        for (m in masks) {
            c *= m.coverage(x, y)
            if (abs(c) < 1e-9f) return 0f
        }
        return c
    }
}
