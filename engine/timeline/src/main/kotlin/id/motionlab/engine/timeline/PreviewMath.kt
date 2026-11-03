package id.motionlab.engine.timeline

import id.motionlab.engine.animation.Mat3

/** Pure helpers that turn a resolved [RenderItem] into what the GL renderer needs. No Android types. */
object PreviewMath {
    /**
     * Column-major 4x4 matrix for the unit quad (-1..1, y up, texture row 0 at the top) so that it covers the clip's
     * content box of [contentW] x [contentH] canvas pixels, transformed by [world] (canvas-centre origin, y down)
     * and projected to clip space of a [canvasW] x [canvasH] canvas.
     */
    fun quadMatrix(world: Mat3, contentW: Double, contentH: Double, canvasW: Int, canvasH: Int, out: FloatArray = FloatArray(16)): FloatArray {
        require(out.size >= 16)
        // unit quad -> content px (y flips: quad +y is the TOP of the bitmap, which is -y in y-down space)
        val m = Mat3.scale(2.0 / canvasW, -2.0 / canvasH) * world * Mat3.scale(contentW / 2.0, -contentH / 2.0)
        out.fill(0f)
        out[0] = m.a.toFloat(); out[1] = m.b.toFloat()
        out[4] = m.c.toFloat(); out[5] = m.d.toFloat()
        out[10] = 1f
        out[12] = m.e.toFloat(); out[13] = m.f.toFloat()
        out[15] = 1f
        return out
    }

    /** Content box of a clip: explicit payload w/h, else a default tile (40% of canvas width, 16:9). */
    fun contentSize(clip: MediaClip, canvasW: Int): Pair<Double, Double> {
        val w = clip.payload["w"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: (canvasW * 0.4)
        val h = clip.payload["h"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: (w * 9.0 / 16.0)
        return w to h
    }

    /** Largest rectangle with the canvas aspect that fits a [surfaceW] x [surfaceH] surface: x, y, w, h in pixels. */
    fun letterbox(surfaceW: Int, surfaceH: Int, canvasW: Int, canvasH: Int): IntArray {
        val sa = surfaceW.toDouble() / surfaceH
        val ca = canvasW.toDouble() / canvasH
        val w: Int
        val h: Int
        if (sa >= ca) { h = surfaceH; w = (surfaceH * ca).toInt() } else { w = surfaceW; h = (surfaceW / ca).toInt() }
        return intArrayOf((surfaceW - w) / 2, (surfaceH - h) / 2, w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    /** Parses "#RRGGBB" / "#AARRGGBB"; returns [fallback] on anything else. Result is ARGB. */
    fun parseColor(text: String?, fallback: Int): Int {
        val t = text?.trim() ?: return fallback
        if (!t.startsWith("#")) return fallback
        val v = t.substring(1).toLongOrNull(16) ?: return fallback
        return when (t.length) {
            7 -> (0xFF000000L or v).toInt()
            9 -> v.toInt()
            else -> fallback
        }
    }

    /**
     * Content box in canvas pixels for UI overlays (selection outline, align). Text is estimated from font size and line
     * lengths because the exact layout is only known after the renderer has measured it; the renderer publishes the real
     * size and the UI prefers that when it exists.
     */
    fun estimateContentSize(clip: MediaClip, kind: TrackKind, canvasW: Int): Pair<Double, Double> {
        if (kind != TrackKind.TEXT) return contentSize(clip, canvasW)
        val size = clip.payload["size"]?.toDoubleOrNull()?.coerceIn(8.0, 600.0) ?: 96.0
        val lines = (clip.payload["text"]?.takeIf { it.isNotEmpty() } ?: "Text").split('\n')
        val w = clip.payload["w"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: (lines.maxOf { it.length } * size * 0.55).coerceAtLeast(size)
        return w to (lines.size * size * 1.2)
    }
}
