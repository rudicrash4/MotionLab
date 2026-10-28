package id.motionlab.editor.track

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Frame grayscale 8-bit. [data] berisi nilai 0..255 (disimpan sebagai byte bertanda, baca dengan `and 0xFF`). */
class GrayFrame(val w: Int, val h: Int, val data: ByteArray) {
    fun at(x: Int, y: Int): Int = data[y * w + x].toInt() and 0xFF
}

/** Hasil pelacakan satu frame; [nx],[ny] ternormalisasi 0..1 terhadap frame video (kiri-atas = 0,0). */
data class TrackSample(val timeMs: Long, val nx: Float, val ny: Float, val confidence: Float)

/**
 * Pelacak objek ringan berbasis template matching (SAD) dengan:
 *  - jendela pencarian di sekitar posisi terprediksi (kecepatan konstan),
 *  - refinement sub-piksel (fit parabola),
 *  - pembaruan template adaptif perlahan agar tahan perubahan cahaya / rotasi kecil,
 *  - deteksi kehilangan target (confidence rendah -> posisi ditahan).
 * Murni Kotlin tanpa dependensi native, cukup cepat untuk dijalankan pada frame 192 px.
 */
class MotionTracker(private val half: Int = 10, private val searchRadius: Int = 28) {
    private val size = 2 * half + 1
    private var template = FloatArray(size * size)
    private var cx = 0f
    private var cy = 0f
    private var vx = 0f
    private var vy = 0f
    private var frameW = 1
    private var frameH = 1
    var lostFrames = 0
        private set

    /** Inisialisasi di frame pertama. Mengembalikan false bila area terlalu polos (tidak bisa dilacak). */
    fun init(frame: GrayFrame, nx: Float, ny: Float): Boolean {
        frameW = frame.w
        frameH = frame.h
        cx = (nx * frame.w).coerceIn(half.toFloat(), (frame.w - half - 1).toFloat())
        cy = (ny * frame.h).coerceIn(half.toFloat(), (frame.h - half - 1).toFloat())
        vx = 0f
        vy = 0f
        lostFrames = 0
        extract(frame, cx.roundToInt(), cy.roundToInt(), template)
        var mean = 0f
        for (v in template) mean += v
        mean /= template.size
        var variance = 0f
        for (v in template) variance += (v - mean) * (v - mean)
        variance /= template.size
        return sqrt(variance) >= 5f
    }

    fun update(frame: GrayFrame, timeMs: Long): TrackSample {
        val px = (cx + vx).roundToInt().coerceIn(half, frame.w - half - 1)
        val py = (cy + vy).roundToInt().coerceIn(half, frame.h - half - 1)
        var best = Float.MAX_VALUE
        var bx = px
        var by = py
        val r = searchRadius
        for (dy in -r..r) {
            val y = py + dy
            if (y < half || y > frame.h - half - 1) continue
            for (dx in -r..r) {
                val x = px + dx
                if (x < half || x > frame.w - half - 1) continue
                val s = sad(frame, x, y, best)
                if (s < best) {
                    best = s
                    bx = x
                    by = y
                }
            }
        }
        // Sub-piksel.
        var fx = bx.toFloat()
        var fy = by.toFloat()
        if (bx - 1 >= half && bx + 1 <= frame.w - half - 1) {
            val l = sad(frame, bx - 1, by, Float.MAX_VALUE)
            val rr = sad(frame, bx + 1, by, Float.MAX_VALUE)
            fx += parabola(l, best, rr)
        }
        if (by - 1 >= half && by + 1 <= frame.h - half - 1) {
            val u = sad(frame, bx, by - 1, Float.MAX_VALUE)
            val d = sad(frame, bx, by + 1, Float.MAX_VALUE)
            fy += parabola(u, best, d)
        }
        val meanSad = best / (size * size)
        val conf = (1f - meanSad / 40f).coerceIn(0f, 1f)
        if (conf >= 0.25f) {
            vx = 0.5f * vx + 0.5f * (fx - cx)
            vy = 0.5f * vy + 0.5f * (fy - cy)
            cx = fx
            cy = fy
            lostFrames = 0
            if (conf > 0.6f) blendTemplate(frame, bx, by, 0.08f)
        } else {
            // Target hilang: tahan posisi, redam kecepatan.
            vx *= 0.5f
            vy *= 0.5f
            lostFrames++
        }
        return TrackSample(timeMs, cx / frameW, cy / frameH, conf)
    }

    private fun parabola(a: Float, b: Float, c: Float): Float {
        val denom = a - 2f * b + c
        if (abs(denom) < 1e-6f) return 0f
        return (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f)
    }

    private fun sad(frame: GrayFrame, centerX: Int, centerY: Int, cutoff: Float): Float {
        var sum = 0f
        var ti = 0
        val data = frame.data
        val w = frame.w
        for (j in 0 until size) {
            var idx = (centerY - half + j) * w + (centerX - half)
            for (i in 0 until size) {
                sum += abs((data[idx].toInt() and 0xFF) - template[ti])
                idx++
                ti++
            }
            if (sum >= cutoff) return sum
        }
        return sum
    }

    private fun extract(frame: GrayFrame, centerX: Int, centerY: Int, out: FloatArray) {
        var o = 0
        for (j in 0 until size) {
            for (i in 0 until size) {
                out[o++] = frame.at(centerX - half + i, centerY - half + j).toFloat()
            }
        }
    }

    private fun blendTemplate(frame: GrayFrame, centerX: Int, centerY: Int, rate: Float) {
        var o = 0
        for (j in 0 until size) {
            for (i in 0 until size) {
                val v = frame.at(centerX - half + i, centerY - half + j).toFloat()
                template[o] = template[o] * (1f - rate) + v * rate
                o++
            }
        }
    }
}

object TrackPostProcess {
    /** Rata-rata bergerak 3 titik untuk meredam getar sub-piksel. */
    fun smooth(s: List<TrackSample>): List<TrackSample> {
        if (s.size < 3) return s
        val out = ArrayList<TrackSample>(s.size)
        for (i in s.indices) {
            val a = s[max(0, i - 1)]
            val b = s[i]
            val c = s[min(s.size - 1, i + 1)]
            out.add(b.copy(nx = (a.nx + b.nx + c.nx) / 3f, ny = (a.ny + b.ny + c.ny) / 3f))
        }
        return out
    }

    /**
     * Mengurangi jumlah keyframe: titik dibuang bila bisa direkonstruksi dengan interpolasi linear
     * antara titik yang dipertahankan dalam toleransi [tol] (dalam satuan ternormalisasi).
     */
    fun decimate(s: List<TrackSample>, tol: Float = 0.002f): List<TrackSample> {
        if (s.size <= 2) return s
        val keep = ArrayList<TrackSample>()
        keep.add(s[0])
        var anchor = 0
        var i = 2
        while (i < s.size) {
            val a = s[anchor]
            val b = s[i]
            var ok = true
            for (k in anchor + 1 until i) {
                val t = (s[k].timeMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs).toFloat()
                val ex = a.nx + (b.nx - a.nx) * t
                val ey = a.ny + (b.ny - a.ny) * t
                if (abs(ex - s[k].nx) > tol || abs(ey - s[k].ny) > tol) { ok = false; break }
            }
            if (!ok) {
                keep.add(s[i - 1])
                anchor = i - 1
            }
            i++
        }
        keep.add(s[s.size - 1])
        return keep
    }
}

/** Menjalankan pelacakan pada file video menggunakan MediaMetadataRetriever (di thread IO). */
object VideoTrackingJob {
    private const val TRACK_WIDTH = 192

    sealed class Result {
        data class Ok(val samples: List<TrackSample>, val lostFrames: Int) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * @param startMs waktu awal pada file sumber video (ms)
     * @param endMs waktu akhir pada file sumber video (ms)
     * @param nx,ny titik awal objek (0..1) pada frame video
     * @param patchHalf setengah lebar patch (px pada lebar 192)
     * @param hz frekuensi sampling
     * @param onProgress dipanggil 0..1
     * Waktu pada [TrackSample.timeMs] adalah waktu SUMBER video.
     */
    suspend fun run(
        context: Context, uri: Uri, startMs: Long, endMs: Long,
        nx: Float, ny: Float, patchHalf: Int, hz: Int,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val stepMs = (1000 / hz).coerceAtLeast(33)
            val tracker = MotionTracker(half = patchHalf)
            val samples = ArrayList<TrackSample>()
            var t = startMs
            var first = true
            val total = (endMs - startMs).coerceAtLeast(1L).toFloat()
            while (t <= endMs) {
                currentCoroutineContext().ensureActive()
                val gray = grabGray(retriever, t) ?: break
                if (first) {
                    if (!tracker.init(gray, nx, ny)) {
                        return@withContext Result.Error("Area terlalu polos / tidak punya detail. Pilih area dengan tekstur atau kontras jelas.")
                    }
                    samples.add(TrackSample(t, nx, ny, 1f))
                    first = false
                } else {
                    samples.add(tracker.update(gray, t))
                }
                onProgress(((t - startMs) / total).coerceIn(0f, 1f))
                t += stepMs
            }
            if (samples.size < 2) Result.Error("Frame video tidak dapat dibaca.")
            else Result.Ok(samples, tracker.lostFrames)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Error("Gagal melacak: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun grabGray(r: MediaMetadataRetriever, tMs: Long): GrayFrame? {
        val us = tMs * 1000L
        var bmp: Bitmap? = null
        try {
            val full = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val ratio = full.height.toFloat() / full.width.toFloat()
            val th = max(16, (TRACK_WIDTH * ratio).roundToInt())
            bmp = if (full.width == TRACK_WIDTH && full.height == th) full
            else Bitmap.createScaledBitmap(full, TRACK_WIDTH, th, true)
            if (bmp !== full) full.recycle()
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val out = ByteArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                out[i] = l.toByte()
            }
            return GrayFrame(w, h, out)
        } finally {
            bmp?.recycle()
        }
    }
}
