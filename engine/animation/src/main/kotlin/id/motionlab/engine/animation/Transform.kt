package id.motionlab.engine.animation

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * 2D affine matrix: x' = a*x + c*y + e, y' = b*x + d*y + f.
 *
 * Coordinate system used everywhere (preview, export, hierarchy): origin at the CENTRE of the canvas, x to the
 * right, y DOWN, pixels; positive rotation is clockwise on screen.
 */
data class Mat3(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0,
) {
    /** `this * o`: applies [o] first, then this. */
    operator fun times(o: Mat3) = Mat3(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        e = a * o.e + c * o.f + e,
        f = b * o.e + d * o.f + f,
    )

    val determinant: Double get() = a * d - b * c

    fun invert(): Mat3? {
        val det = determinant
        if (det == 0.0 || !det.isFinite() || kotlin.math.abs(det) < 1e-12) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Mat3(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    fun mapX(x: Double, y: Double): Double = a * x + c * y + e
    fun mapY(x: Double, y: Double): Double = b * x + d * y + f

    companion object {
        val IDENTITY = Mat3()

        fun translate(x: Double, y: Double) = Mat3(e = x, f = y)

        fun scale(sx: Double, sy: Double) = Mat3(a = sx, d = sy)

        fun rotateDegrees(deg: Double): Mat3 {
            val r = deg * PI / 180.0
            val cs = cos(r)
            val sn = sin(r)
            return Mat3(cs, sn, -sn, cs)
        }

        /** x' = x + tan(kx)*y, y' = y + tan(ky)*x. Angles are clamped below 90 degrees. */
        fun skewDegrees(kx: Double, ky: Double): Mat3 =
            Mat3(c = tan(kx.coerceIn(-MAX_SKEW, MAX_SKEW) * PI / 180.0), b = tan(ky.coerceIn(-MAX_SKEW, MAX_SKEW) * PI / 180.0))

        private const val MAX_SKEW = 85.0
    }
}

/** Evaluated local transform of one object at one instant. */
data class TransformValues(
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotationDeg: Double = 0.0,
    val anchorX: Double = 0.0,
    val anchorY: Double = 0.0,
    val skewXDeg: Double = 0.0,
    val skewYDeg: Double = 0.0,
    val opacity: Double = 1.0,
) {
    /** M = T(position) * R(rotation) * Skew * S(scale) * T(-anchor). */
    fun toMatrix(): Mat3 =
        Mat3.translate(posX, posY) *
            Mat3.rotateDegrees(rotationDeg) *
            Mat3.skewDegrees(skewXDeg, skewYDeg) *
            Mat3.scale(scaleX, scaleY) *
            Mat3.translate(-anchorX, -anchorY)

    companion object {
        /** Builds from a property-name map (missing names take the registry default). */
        fun from(values: Map<String, Double>): TransformValues {
            fun v(name: String) = values[name] ?: PropertyRegistry.defaultOf(name)
            return TransformValues(
                posX = v(PropertyNames.POSITION_X),
                posY = v(PropertyNames.POSITION_Y),
                scaleX = v(PropertyNames.SCALE_X),
                scaleY = v(PropertyNames.SCALE_Y),
                rotationDeg = v(PropertyNames.ROTATION),
                anchorX = v(PropertyNames.ANCHOR_X),
                anchorY = v(PropertyNames.ANCHOR_Y),
                skewXDeg = v(PropertyNames.SKEW_X),
                skewYDeg = v(PropertyNames.SKEW_Y),
                opacity = v(PropertyNames.OPACITY).coerceIn(0.0, 1.0),
            )
        }
    }
}

/** Result of splitting a matrix back into editable properties, see [TransformMath.decompose]. */
data class DecomposedTransform(
    val posX: Double,
    val posY: Double,
    val scaleX: Double,
    val scaleY: Double,
    val rotationDeg: Double,
    val skewXDeg: Double,
)

object TransformMath {
    /**
     * Splits [m] into position / rotation / scale / skewX so that
     * `T(pos) * R(rot) * Skew(skewX, 0) * S(scale) * T(-anchor) == m` for the given anchor.
     * It is exact for every invertible matrix; skewY is always 0 in the result, so an object that has a
     * non-zero skewY cannot be represented (callers must refuse that case). Returns null for a singular matrix.
     */
    fun decompose(m: Mat3, anchorX: Double, anchorY: Double): DecomposedTransform? {
        val sx = hypot(m.a, m.b)
        if (sx < 1e-12) return null
        val theta = atan2(m.b, m.a)
        val ct = cos(theta)
        val st = sin(theta)
        // U = R(-theta) * A  is upper triangular [[sx, sy*tan(k)], [0, sy]]
        val u12 = ct * m.c + st * m.d
        val sy = -st * m.c + ct * m.d
        if (kotlin.math.abs(sy) < 1e-12) return null
        val skew = atan(u12 / sy)
        // translation of M is pos - A * anchor  =>  pos = translation + A * anchor
        val posX = m.e + m.a * anchorX + m.c * anchorY
        val posY = m.f + m.b * anchorX + m.d * anchorY
        return DecomposedTransform(posX, posY, sx, sy, theta * 180.0 / PI, skew * 180.0 / PI)
    }
}
