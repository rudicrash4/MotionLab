package id.motionlab.editor.core

import java.util.LinkedHashMap
import kotlin.math.abs

object SpeedLimits {
    const val MIN = 0.1f
    const val MAX = 8f
}

/**
 * Pemetaan waktu untuk Speed Ramp.
 *
 * Kurva kecepatan s(t) didefinisikan pada waktu LOKAL klip (ms di timeline, 0 = awal klip) lewat
 * keyframe properti [Props.SPEED]. Waktu sumber yang terpakai sejak awal klip adalah integral
 *
 *     S(t) = ∫ s(τ) dτ   (0..t)
 *
 * Kelas ini menyediakan S(t) (offset), s(t), dan invers S⁻¹ (untuk batas trim & ekspor).
 * Sebelum keyframe pertama kecepatan = nilai keyframe pertama, setelah keyframe terakhir = nilai terakhir.
 * Semua nilai dibatasi ke [SpeedLimits.MIN, SpeedLimits.MAX] sehingga S selalu naik tegas (invers valid).
 */
class SpeedMap private constructor(
    private val f: List<Keyframe>,
    private val base: Float,
    /** cum[i] = integral dari 0 sampai f[i].timeMs. */
    private val cum: DoubleArray
) {
    val isConstant: Boolean get() = f.isEmpty()

    fun speedAt(tMs: Double): Float {
        if (f.isEmpty()) return base
        if (tMs <= f[0].timeMs) return f[0].value
        val last = f[f.size - 1]
        if (tMs >= last.timeMs) return last.value
        val i = segmentOf(tMs)
        val a = f[i]
        val b = f[i + 1]
        val span = (b.timeMs - a.timeMs).toDouble()
        if (span <= 0.0) return b.value
        val u = ((tMs - a.timeMs) / span).toFloat()
        return clampSpeed(a.value + (b.value - a.value) * Easing.apply(a, u))
    }

    /** Milidetik sumber yang terpakai dari awal klip sampai waktu lokal [tMs]. */
    fun offset(tMs: Double): Double {
        if (f.isEmpty()) return tMs * base
        val first = f[0]
        if (tMs <= first.timeMs) return tMs * first.value
        val lastIdx = f.size - 1
        val last = f[lastIdx]
        if (tMs >= last.timeMs) return cum[lastIdx] + (tMs - last.timeMs) * last.value
        val i = segmentOf(tMs)
        return cum[i] + partial(f[i], f[i + 1], tMs - f[i].timeMs)
    }

    /** Waktu lokal (ms) tempat offset sumber mencapai [sourceMs]. Kebalikan dari [offset]. */
    fun timeForOffset(sourceMs: Double): Double {
        if (f.isEmpty()) return sourceMs / base
        val first = f[0]
        if (sourceMs <= cum[0]) return sourceMs / first.value
        val lastIdx = f.size - 1
        if (sourceMs >= cum[lastIdx]) return f[lastIdx].timeMs + (sourceMs - cum[lastIdx]) / f[lastIdx].value
        var lo = 0
        var hi = lastIdx
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= sourceMs) lo = mid else hi = mid
        }
        val a = f[lo]
        val b = f[lo + 1]
        val span = (b.timeMs - a.timeMs).toDouble()
        var l = 0.0
        var h = span
        for (k in 0 until 40) {
            val m = (l + h) * 0.5
            if (cum[lo] + partial(a, b, m) < sourceMs) l = m else h = m
        }
        return a.timeMs + (l + h) * 0.5
    }

    private fun segmentOf(tMs: Double): Int {
        var lo = 0
        var hi = f.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (f[mid].timeMs <= tMs) lo = mid else hi = mid
        }
        return lo
    }

    companion object {
        fun clampSpeed(v: Float): Float =
            if (v.isNaN()) 1f else if (v < SpeedLimits.MIN) SpeedLimits.MIN else if (v > SpeedLimits.MAX) SpeedLimits.MAX else v

        /** Integral kecepatan pada segmen a->b dari awal segmen sampai [dt] ms. */
        private fun partial(a: Keyframe, b: Keyframe, dt: Double): Double {
            if (dt <= 0.0) return 0.0
            val span = (b.timeMs - a.timeMs).toDouble()
            if (span <= 0.0) return 0.0
            val d = if (dt > span) span else dt
            val u = d / span
            val dv = (b.value - a.value).toDouble()
            return when (a.interp) {
                Interp.HOLD -> a.value.toDouble() * d
                Interp.LINEAR -> d * (a.value + dv * u * 0.5)
                else -> {
                    // Simpson 24 langkah pada kurva yang sudah di-ease.
                    val n = 24
                    val h = u / n
                    var sum = 0.0
                    for (k in 0..n) {
                        val x = k * h
                        val v = clampSpeed((a.value + dv * Easing.apply(a, x.toFloat())).toFloat()).toDouble()
                        val w = if (k == 0 || k == n) 1.0 else if (k % 2 == 1) 4.0 else 2.0
                        sum += w * v
                    }
                    sum * h / 3.0 * span
                }
            }
        }

        fun build(track: KeyframeTrack?, baseIn: Float): SpeedMap {
            val b = clampSpeed(baseIn)
            val raw = track?.frames ?: emptyList()
            if (raw.isEmpty()) return SpeedMap(emptyList(), b, DoubleArray(0))
            var frames = raw.sortedBy { it.timeMs }.map { it.copy(value = clampSpeed(it.value)) }
            if (frames[0].timeMs < 0L) {
                val v0 = KeyframeTrack(frames).evaluate(0L, b)
                val prev = frames.last { it.timeMs <= 0L }
                val rest = frames.filter { it.timeMs > 0L }
                frames = listOf(prev.copy(timeMs = 0L, value = clampSpeed(v0))) + rest
            }
            val cum = DoubleArray(frames.size)
            cum[0] = frames[0].timeMs.toDouble() * frames[0].value
            for (i in 1 until frames.size) {
                cum[i] = cum[i - 1] + partial(frames[i - 1], frames[i], (frames[i].timeMs - frames[i - 1].timeMs).toDouble())
            }
            return SpeedMap(frames, b, cum)
        }
    }
}

/** Cache kecil SpeedMap berdasarkan identitas daftar keyframe (klip immutable -> aman). */
object SpeedMaps {
    private class Key(val ref: Any?, val base: Float) {
        override fun equals(other: Any?): Boolean = other is Key && other.ref === ref && other.base == base
        override fun hashCode(): Int = System.identityHashCode(ref) * 31 + base.hashCode()
    }

    private val cache = object : LinkedHashMap<Key, SpeedMap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, SpeedMap>?): Boolean = size > 64
    }

    @Synchronized
    fun of(clip: Clip): SpeedMap {
        val kt = clip.keyframes[Props.SPEED]
        val base = clip.baseValue(Props.SPEED)
        if (kt == null || kt.frames.isEmpty()) return SpeedMap.build(null, base)
        val key = Key(kt.frames, base)
        val hit = cache[key]
        if (hit != null) return hit
        val m = SpeedMap.build(kt, base)
        cache[key] = m
        return m
    }
}

/** Preset Speed Ramp. Semua menghasilkan kurva nyata (keyframe kecepatan) pada durasi klip. */
enum class SpeedPreset(val label: String) {
    MONTAGE("Montage"),
    SLOW_MOTION("Slow Motion"),
    SPEED_UP("Speed Up"),
    SMOOTH_ACCEL("Smooth Acceleration"),
    SMOOTH_DECEL("Smooth Deceleration"),
    ACTION("Action"),
    CUSTOM("Custom");

    /** Membuat keyframe untuk klip berdurasi [d] ms. [existing] dipakai oleh CUSTOM. */
    fun frames(d: Long, existing: List<Keyframe>, currentBase: Float): List<Keyframe> {
        fun at(p: Float) = Math.round(d * p).toLong()
        fun k(p: Float, v: Float, i: Interp = Interp.EASE_IN_OUT) = Keyframe(at(p), v, i)
        val raw: List<Keyframe> = when (this) {
            MONTAGE -> listOf(k(0f, 1f), k(0.22f, 1f), k(0.38f, 0.25f), k(0.58f, 0.25f), k(0.74f, 3f), k(1f, 1f))
            SLOW_MOTION -> listOf(k(0f, 1f), k(0.15f, 0.3f), k(0.85f, 0.3f), k(1f, 1f))
            SPEED_UP -> listOf(k(0f, 1f, Interp.EASE_OUT), k(0.3f, 2.5f, Interp.LINEAR), k(1f, 2.5f))
            SMOOTH_ACCEL -> listOf(k(0f, 0.5f), k(1f, 3f))
            SMOOTH_DECEL -> listOf(k(0f, 3f), k(1f, 0.5f))
            ACTION -> listOf(k(0f, 3f), k(0.3f, 3f), k(0.45f, 0.2f), k(0.6f, 0.2f), k(0.75f, 3f), k(1f, 3f))
            CUSTOM ->
                if (existing.size >= 2) existing
                else listOf(Keyframe(0L, currentBase, Interp.EASE_IN_OUT), Keyframe(d, currentBase, Interp.EASE_IN_OUT))
        }
        return SpeedEdit.normalize(raw)
    }
}

/** Operasi penyuntingan titik kurva kecepatan (murni, tanpa efek samping). */
object SpeedEdit {
    /** Urutkan, batasi nilai, buang titik berwaktu sama (simpan yang terakhir). */
    fun normalize(frames: List<Keyframe>): List<Keyframe> {
        val sorted = frames.sortedBy { it.timeMs }.map { it.copy(value = SpeedMap.clampSpeed(it.value)) }
        val out = ArrayList<Keyframe>()
        for (k in sorted) {
            if (out.isNotEmpty() && abs(out[out.size - 1].timeMs - k.timeMs) < 1L) out[out.size - 1] = k else out.add(k)
        }
        return out
    }

    /** Menambah titik di [timeMs] dengan nilai kurva saat ini (kurva tidak berubah bentuk). */
    fun addPoint(frames: List<Keyframe>, timeMs: Long, base: Float, value: Float? = null): List<Keyframe> {
        val cur = KeyframeTrack(frames).evaluate(timeMs, base)
        return normalize(frames + Keyframe(timeMs, value ?: cur, Interp.EASE_IN_OUT))
    }

    fun removePoint(frames: List<Keyframe>, index: Int): List<Keyframe> =
        if (index !in frames.indices) frames else frames.filterIndexed { i, _ -> i != index }

    /** Mengubah titik [index]; waktu dibatasi di antara tetangga agar urutan tidak berubah. */
    fun updatePoint(
        frames: List<Keyframe>, index: Int, timeMs: Long? = null, value: Float? = null,
        interp: Interp? = null, bezier: FloatArray? = null, maxTimeMs: Long = Long.MAX_VALUE
    ): List<Keyframe> {
        if (index !in frames.indices) return frames
        val k = frames[index]
        val lo = if (index > 0) frames[index - 1].timeMs + 1L else 0L
        val hi = if (index < frames.size - 1) frames[index + 1].timeMs - 1L else maxTimeMs
        var nt = timeMs ?: k.timeMs
        if (nt < lo) nt = lo
        if (nt > hi) nt = hi
        var nk = k.copy(
            timeMs = nt,
            value = SpeedMap.clampSpeed(value ?: k.value),
            interp = interp ?: k.interp
        )
        if (bezier != null && bezier.size == 4) nk = nk.copy(bx1 = bezier[0], by1 = bezier[1], bx2 = bezier[2], by2 = bezier[3])
        return frames.mapIndexed { i, o -> if (i == index) nk else o }
    }
}
