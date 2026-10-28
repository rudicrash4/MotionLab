package id.motionlab.editor.core

import kotlin.math.abs

/** Daftar keyframe satu properti, selalu terurut menurut waktu. */
data class KeyframeTrack(val frames: List<Keyframe> = emptyList()) {

    /** Evaluasi nilai pada waktu lokal [tMs]. [base] dipakai bila tidak ada keyframe. */
    fun evaluate(tMs: Long, base: Float): Float {
        val f = frames
        if (f.isEmpty()) return base
        if (tMs <= f[0].timeMs) return f[0].value
        val last = f[f.size - 1]
        if (tMs >= last.timeMs) return last.value
        // Pencarian biner segmen [a, b] yang memuat tMs.
        var lo = 0
        var hi = f.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (f[mid].timeMs <= tMs) lo = mid else hi = mid
        }
        val a = f[lo]
        val b = f[hi]
        val span = (b.timeMs - a.timeMs).toFloat()
        if (span <= 0f) return b.value
        val u = (tMs - a.timeMs).toFloat() / span
        val e = Easing.apply(a, u)
        return a.value + (b.value - a.value) * e
    }

    fun indexAt(tMs: Long, toleranceMs: Long): Int {
        var best = -1
        var bestDist = Long.MAX_VALUE
        for ((i, k) in frames.withIndex()) {
            val d = abs(k.timeMs - tMs)
            if (d <= toleranceMs && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    /** Menyisipkan / mengganti keyframe pada waktu yang sama (toleransi 1 ms). */
    fun upsert(k: Keyframe): KeyframeTrack {
        val list = frames.toMutableList()
        val idx = list.indexOfFirst { abs(it.timeMs - k.timeMs) <= 1L }
        if (idx >= 0) {
            // Pertahankan kurva lama jika yang baru tidak membawa kurva khusus.
            list[idx] = k
        } else {
            list.add(k)
            list.sortBy { it.timeMs }
        }
        return KeyframeTrack(list)
    }

    fun removeAt(index: Int): KeyframeTrack {
        if (index !in frames.indices) return this
        val list = frames.toMutableList()
        list.removeAt(index)
        return KeyframeTrack(list)
    }

    /** Geser semua waktu keyframe sebesar [deltaMs]. */
    fun shifted(deltaMs: Long): KeyframeTrack =
        KeyframeTrack(frames.map { it.copy(timeMs = it.timeMs + deltaMs) })

    /** Skala semua waktu keyframe (dipakai saat kecepatan klip berubah). */
    fun scaled(factor: Float): KeyframeTrack =
        KeyframeTrack(frames.map { it.copy(timeMs = Math.round(it.timeMs * factor).toLong()) })

    /**
     * Memotong di waktu lokal [splitMs]: bagian kiri berisi keyframe <= splitMs,
     * bagian kanan (sudah digeser ke 0) berisi keyframe >= splitMs. Keyframe batas ditambahkan
     * agar nilai di titik potong tetap kontinu.
     */
    fun split(splitMs: Long, base: Float): Pair<KeyframeTrack, KeyframeTrack> {
        if (frames.isEmpty()) return Pair(this, this)
        val atSplit = evaluate(splitMs, base)
        val prevIdx = frames.indexOfLast { it.timeMs <= splitMs }
        val prev = frames.getOrNull(prevIdx)
        val next = if (prevIdx >= 0) frames.getOrNull(prevIdx + 1) else null
        var boundary = Keyframe(
            splitMs, atSplit,
            prev?.interp ?: Interp.LINEAR,
            prev?.bx1 ?: 0.42f, prev?.by1 ?: 0f, prev?.bx2 ?: 0.58f, prev?.by2 ?: 1f
        )
        var leftPrev: Keyframe? = null
        if (prev != null && next != null && prev.timeMs < splitMs && next.timeMs > prev.timeMs) {
            // Memotong di tengah segmen: kurva easing dibelah (de Casteljau) agar bentuk animasi tidak berubah.
            val u = (splitMs - prev.timeMs).toFloat() / (next.timeMs - prev.timeMs).toFloat()
            val cut = Easing.segmentCut(prev, u)
            if (cut != null) {
                leftPrev = prev.copy(interp = Interp.CUSTOM, bx1 = cut.first[0], by1 = cut.first[1], bx2 = cut.first[2], by2 = cut.first[3])
                boundary = boundary.copy(interp = Interp.CUSTOM, bx1 = cut.second[0], by1 = cut.second[1], bx2 = cut.second[2], by2 = cut.second[3])
            }
        }
        val left = frames.filter { it.timeMs < splitMs }.toMutableList()
        if (leftPrev != null && prev != null) {
            val idx = left.indexOfFirst { it.timeMs == prev.timeMs }
            if (idx >= 0) left[idx] = leftPrev
        }
        left.add(boundary)
        val right = frames.filter { it.timeMs > splitMs }.map { it.copy(timeMs = it.timeMs - splitMs) }.toMutableList()
        right.add(0, boundary.copy(timeMs = 0L))
        return Pair(KeyframeTrack(left), KeyframeTrack(right))
    }
}

/** Fungsi easing untuk interpolasi keyframe. */
object Easing {
    fun apply(k: Keyframe, u: Float): Float {
        val x = if (u < 0f) 0f else if (u > 1f) 1f else u
        return when (k.interp) {
            Interp.LINEAR -> x
            Interp.HOLD -> 0f
            Interp.EASE_IN -> cubicBezier(0.42f, 0f, 1f, 1f, x)
            Interp.EASE_OUT -> cubicBezier(0f, 0f, 0.58f, 1f, x)
            Interp.EASE_IN_OUT -> cubicBezier(0.42f, 0f, 0.58f, 1f, x)
            Interp.CUSTOM -> cubicBezier(k.bx1, k.by1, k.bx2, k.by2, x)
        }
    }

    /**
     * Cubic-bezier seperti CSS: titik kontrol (0,0), (x1,y1), (x2,y2), (1,1).
     * x dibatasi 0..1 agar fungsi tetap satu-ke-satu terhadap waktu; y boleh keluar 0..1 (overshoot).
     */
    fun cubicBezier(x1In: Float, y1: Float, x2In: Float, y2: Float, x: Float): Float {
        val x1 = x1In.coerceIn(0f, 1f)
        val x2 = x2In.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Cari parameter t sehingga bezierX(t) == x : Newton-Raphson, cadangan bisection.
        var t = x
        for (i in 0 until 6) {
            val cx = bez(x1, x2, t) - x
            if (abs(cx) < 1e-5f) return bez(y1, y2, t)
            val d = bezDeriv(x1, x2, t)
            if (abs(d) < 1e-6f) break
            t -= cx / d
            if (t < 0f || t > 1f) break
        }
        var lo = 0f
        var hi = 1f
        t = x
        for (i in 0 until 24) {
            val cx = bez(x1, x2, t)
            if (abs(cx - x) < 1e-5f) break
            if (cx < x) lo = t else hi = t
            t = (lo + hi) * 0.5f
        }
        return bez(y1, y2, t)
    }

    /**
     * Membelah kurva easing segmen [k] pada fraksi waktu [u] (0..1). Mengembalikan kontrol cubic-bezier
     * ternormalisasi (x1,y1,x2,y2) untuk bagian kiri dan kanan, atau null bila tidak bisa (HOLD / degenerate).
     */
    fun segmentCut(k: Keyframe, u: Float): Pair<FloatArray, FloatArray>? {
        if (k.interp == Interp.HOLD || u <= 0f || u >= 1f) return null
        val c = when (k.interp) {
            Interp.LINEAR -> floatArrayOf(0f, 0f, 1f, 1f)
            Interp.EASE_IN -> floatArrayOf(0.42f, 0f, 1f, 1f)
            Interp.EASE_OUT -> floatArrayOf(0f, 0f, 0.58f, 1f)
            Interp.EASE_IN_OUT -> floatArrayOf(0.42f, 0f, 0.58f, 1f)
            else -> floatArrayOf(k.bx1, k.by1, k.bx2, k.by2)
        }
        val x1 = c[0].coerceIn(0f, 1f).toDouble()
        val y1 = c[1].toDouble()
        val x2 = c[2].coerceIn(0f, 1f).toDouble()
        val y2 = c[3].toDouble()
        // Cari parameter t dengan bezierX(t) = u (bisection; x monoton karena x1,x2 di [0,1]).
        var lo = 0.0
        var hi = 1.0
        for (i in 0 until 50) {
            val mid = (lo + hi) / 2.0
            val iv = 1.0 - mid
            val bx = 3 * iv * iv * mid * x1 + 3 * iv * mid * mid * x2 + mid * mid * mid
            if (bx < u) lo = mid else hi = mid
        }
        val t = (lo + hi) / 2.0
        fun l(a: Double, b: Double) = a + (b - a) * t
        val q0x = l(0.0, x1); val q0y = l(0.0, y1)
        val q1x = l(x1, x2); val q1y = l(y1, y2)
        val q2x = l(x2, 1.0); val q2y = l(y2, 1.0)
        val r0x = l(q0x, q1x); val r0y = l(q0y, q1y)
        val r1x = l(q1x, q2x); val r1y = l(q1y, q2y)
        val sx = l(r0x, r1x); val sy = l(r0y, r1y)
        val dx = 1.0 - sx
        val dy = 1.0 - sy
        if (sx < 1e-6 || dx < 1e-6 || abs(sy) < 1e-6 || abs(dy) < 1e-6) return null
        val left = floatArrayOf((q0x / sx).toFloat(), (q0y / sy).toFloat(), (r0x / sx).toFloat(), (r0y / sy).toFloat())
        val right = floatArrayOf(
            ((r1x - sx) / dx).toFloat(), ((r1y - sy) / dy).toFloat(),
            ((q2x - sx) / dx).toFloat(), ((q2y - sy) / dy).toFloat()
        )
        return Pair(left, right)
    }

    private fun bez(p1: Float, p2: Float, t: Float): Float {
        val inv = 1f - t
        return 3f * inv * inv * t * p1 + 3f * inv * t * t * p2 + t * t * t
    }

    private fun bezDeriv(p1: Float, p2: Float, t: Float): Float {
        val inv = 1f - t
        return 3f * inv * inv * p1 + 6f * inv * t * (p2 - p1) + 3f * t * t * (1f - p2)
    }
}
