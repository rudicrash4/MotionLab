package id.motionlab.editor.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface

/**
 * Decoder video berurutan untuk ekspor. Frame dirender ke [surface] (SurfaceTexture milik compositor)
 * hanya bila frame tersebut dipilih untuk waktu sumber target, sehingga ekspor akurat per-frame
 * (termasuk kecepatan variabel: frame di antara dilewati, atau frame yang sama diulang pada slow-motion).
 */
class VideoFrameSource(
    private val context: Context,
    private val uri: String,
    private val surface: Surface
) {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    var aspect: Float = 16f / 9f
        private set

    private var tolUs = 17_000L
    private var inputDone = false
    private var outputDone = false
    private var pendingIdx = -1
    private var pendingPts = 0L
    private var lastShownPts = Long.MIN_VALUE
    private var lastSeenPts = Long.MIN_VALUE
    private var started = false

    /** Mengembalikan null bila berhasil, atau pesan kesalahan. */
    fun open(): String? {
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return "tidak ada track video"
            extractor.selectTrack(track)
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            aspect = if (rot == 90 || rot == 270) h.toFloat() / w.toFloat() else w.toFloat() / h.toFloat()
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val fr = try { format.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0 }
                if (fr in 5..240) tolUs = (500_000L / fr)
            }
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            started = true
            return null
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
    }

    /** Pindah ke [sourceUs] (sync frame sebelumnya; frame sebelum target dibuang saat [advanceTo]). */
    fun seek(sourceUs: Long) {
        val c = codec ?: return
        if (pendingIdx >= 0) {
            try { c.releaseOutputBuffer(pendingIdx, false) } catch (_: Exception) { }
            pendingIdx = -1
        }
        extractor.seekTo(sourceUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        inputDone = false
        outputDone = false
        lastSeenPts = Long.MIN_VALUE
    }

    /**
     * Memajukan decoder sampai frame yang cocok dengan [targetUs] (waktu sumber). Mengembalikan true bila
     * sebuah frame BARU dirender ke surface (pemanggil harus memanggil consumeVideoFrame), false bila frame
     * sebelumnya tetap dipakai (slow-motion / frame berikutnya masih di masa depan).
     */
    fun advanceTo(targetUs: Long): Boolean {
        val c = codec ?: return false
        if (lastSeenPts != Long.MIN_VALUE && targetUs - lastSeenPts > 3_000_000L) seek(targetUs)
        var rendered = false
        while (true) {
            if (pendingIdx < 0) {
                if (!fetchNext(c)) break
            }
            if (pendingPts + tolUs < targetUs) {
                c.releaseOutputBuffer(pendingIdx, false)
                pendingIdx = -1
                continue
            }
            if (pendingPts <= targetUs + tolUs || lastShownPts == Long.MIN_VALUE) {
                c.releaseOutputBuffer(pendingIdx, true)
                pendingIdx = -1
                lastShownPts = pendingPts
                rendered = true
            }
            break
        }
        return rendered
    }

    private fun fetchNext(c: MediaCodec): Boolean {
        var stalls = 0
        while (true) {
            if (outputDone) return false
            var fed = 0
            while (!inputDone && fed < 4) {
                val ii = c.dequeueInputBuffer(0)
                if (ii < 0) break
                val buf = c.getInputBuffer(ii) ?: break
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) {
                    c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    c.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
                fed++
            }
            val oi = c.dequeueOutputBuffer(info, 5_000L)
            when {
                oi >= 0 -> {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        c.releaseOutputBuffer(oi, false)
                        outputDone = true
                        return false
                    }
                    if (info.size == 0) {
                        c.releaseOutputBuffer(oi, false)
                        continue
                    }
                    pendingIdx = oi
                    pendingPts = info.presentationTimeUs
                    lastSeenPts = pendingPts
                    return true
                }
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    stalls++
                    if (stalls > 3000) return false
                }
                else -> Unit // format / buffers changed
            }
        }
    }

    fun close() {
        try { if (pendingIdx >= 0) codec?.releaseOutputBuffer(pendingIdx, false) } catch (_: Exception) { }
        pendingIdx = -1
        try { if (started) codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { extractor.release() } catch (_: Exception) { }
    }
}
