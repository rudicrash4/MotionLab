package id.motionlab.engine.animation

import java.util.Locale

/** A named easing curve the Ease editor can apply: it is just an [Interpolation] (the same data the renderer evaluates). */
data class EasePreset(val id: String, val name: String, val interpolation: Interpolation, val builtIn: Boolean = true)

/**
 * Pure helpers for the Ease / Bezier editor. Everything here works on the real [Interpolation] model and [BezierEasing], so
 * the curve drawn in the editor and the animation that is rendered share ONE source of truth; there is no separate "visual"
 * curve representation.
 */
object EaseCurves {
    /** Largest overshoot (above 1 / below 0) a handle may be dragged to: enough for anticipation and bounce, still a sane curve. */
    const val Y_MIN = -1.0
    const val Y_MAX = 2.0

    val BUILT_IN: List<EasePreset> = listOf(
        EasePreset("linear", "Linear", Interpolation.Linear),
        EasePreset("ease", "Ease", Interpolation.Bezier(0.25, 0.1, 0.25, 1.0)),
        EasePreset("ease-in", "Ease In", Easing.EASE_IN),
        EasePreset("ease-out", "Ease Out", Easing.EASE_OUT),
        EasePreset("ease-in-out", "Ease In Out", Easing.EASE_IN_OUT),
        EasePreset("ease-in-quad", "Ease In Quad", Interpolation.Bezier(0.55, 0.085, 0.68, 0.53)),
        EasePreset("ease-out-quad", "Ease Out Quad", Interpolation.Bezier(0.25, 0.46, 0.45, 0.94)),
        EasePreset("ease-in-back", "Ease In Back", Interpolation.Bezier(0.36, 0.0, 0.66, -0.56)),
        EasePreset("ease-out-back", "Ease Out Back", Interpolation.Bezier(0.34, 1.56, 0.64, 1.0)),
        EasePreset("ease-in-out-back", "Ease In Out Back", Interpolation.Bezier(0.68, -0.6, 0.32, 1.6)),
    )

    /** The control points of any smooth interpolation (Linear is the diagonal); null for Hold (a step has no curve). */
    fun controls(i: Interpolation): Interpolation.Bezier? = when (i) {
        Interpolation.Hold -> null
        Interpolation.Linear -> Interpolation.Bezier(1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0)
        is Interpolation.Bezier -> i
    }

    /** A bezier with legal control values: x within 0..1 (so time stays a function), y within [Y_MIN, Y_MAX]; NaN becomes the diagonal. */
    fun clamped(x1: Double, y1: Double, x2: Double, y2: Double): Interpolation.Bezier = Interpolation.Bezier(
        safe(x1, 1.0 / 3.0).coerceIn(0.0, 1.0), safe(y1, 1.0 / 3.0).coerceIn(Y_MIN, Y_MAX),
        safe(x2, 2.0 / 3.0).coerceIn(0.0, 1.0), safe(y2, 2.0 / 3.0).coerceIn(Y_MIN, Y_MAX),
    )

    private fun safe(v: Double, fallback: Double) = if (v.isNaN() || v.isInfinite()) fallback else v

    /** "cubic-bezier(0.33, 0.00, 0.66, 1.00)"; "linear" / "hold" for the non-bezier modes. */
    fun css(i: Interpolation): String = when (i) {
        Interpolation.Hold -> "hold"
        Interpolation.Linear -> "linear"
        is Interpolation.Bezier -> String.format(Locale.US, "cubic-bezier(%.2f, %.2f, %.2f, %.2f)", i.x1, i.y1, i.x2, i.y2)
    }

    /** Eased progress 0..1 -> 0..1 (may leave 0..1 for overshooting curves). Hold = a step at the end. */
    fun valueAt(i: Interpolation, x: Double): Double = when (i) {
        Interpolation.Hold -> if (x >= 1.0) 1.0 else 0.0
        Interpolation.Linear -> x.coerceIn(0.0, 1.0)
        is Interpolation.Bezier -> BezierEasing.solve(i, x)
    }

    /** [n]+1 evenly spaced samples of the curve over normalised time, for drawing and for tests (same maths as the renderer). */
    fun sample(i: Interpolation, n: Int = 48): DoubleArray = DoubleArray(n + 1) { valueAt(i, it.toDouble() / n) }

    /** Speed of the eased progress (d value / d time, normalised) by central difference. */
    fun speedAt(i: Interpolation, x: Double, h: Double = 1e-3): Double {
        val a = (x - h).coerceAtLeast(0.0)
        val b = (x + h).coerceAtMost(1.0)
        return if (b > a) (valueAt(i, b) - valueAt(i, a)) / (b - a) else 0.0
    }

    /** The preset whose curve equals [i] (within a rounding tolerance), searched in [presets]; null when it is a custom curve. */
    fun match(i: Interpolation, presets: List<EasePreset> = BUILT_IN): EasePreset? =
        presets.firstOrNull { same(it.interpolation, i) }

    fun same(a: Interpolation, b: Interpolation): Boolean {
        if (a === b || a == b) return true
        val ca = if (a is Interpolation.Hold || b is Interpolation.Hold) return false else controls(a)!!
        val cb = controls(b)!!
        val eps = 0.005
        return kotlin.math.abs(ca.x1 - cb.x1) < eps && kotlin.math.abs(ca.y1 - cb.y1) < eps &&
            kotlin.math.abs(ca.x2 - cb.x2) < eps && kotlin.math.abs(ca.y2 - cb.y2) < eps
    }

    /**
     * The keyframe interval the Ease editor works on: the segment that STARTS at the selected keyframe (the interpolation is
     * stored on that keyframe). Without a selection it is the first segment; selecting the last keyframe falls back to the
     * segment that ENDS there. Null when the track has fewer than two keyframes.
     */
    fun segmentFor(track: KeyframeTrack, selectedKeyframeId: String?): Pair<Keyframe, Keyframe>? {
        val ks = track.keyframes
        if (ks.size < 2) return null
        var i = ks.indexOfFirst { it.id == selectedKeyframeId }
        if (i < 0) i = 0
        if (i >= ks.size - 1) i = ks.size - 2
        return ks[i] to ks[i + 1]
    }

    // ---- user presets: a tiny, forgiving text format "name|x1,y1,x2,y2" per line (names may not contain '|' or newline) ----

    fun encodeCustom(list: List<EasePreset>): String = list.filter { !it.builtIn }.mapNotNull { p ->
        val b = p.interpolation as? Interpolation.Bezier ?: return@mapNotNull null
        p.name.replace('|', ' ').replace('\n', ' ').trim() + "|" + String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", b.x1, b.y1, b.x2, b.y2)
    }.joinToString("\n")

    /** Decodes [encodeCustom] output; malformed lines are skipped, values are clamped, ids are generated from position and name. */
    fun decodeCustom(text: String?): List<EasePreset> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<EasePreset>()
        for (line in text.lines()) {
            val bar = line.lastIndexOf('|')
            if (bar <= 0) continue
            val v = line.substring(bar + 1).split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (v.size != 4) continue
            val name = line.substring(0, bar).trim().ifEmpty { continue }
            out += EasePreset("custom-${out.size}-${name.lowercase().hashCode()}", name, clamped(v[0], v[1], v[2], v[3]), builtIn = false)
        }
        return out
    }

    /** "Custom 1", "Custom 2", ... the first name not yet used. */
    fun nextCustomName(existing: List<EasePreset>): String {
        var n = existing.count { !it.builtIn } + 1
        while (existing.any { it.name == "Custom $n" }) n++
        return "Custom $n"
    }
}
