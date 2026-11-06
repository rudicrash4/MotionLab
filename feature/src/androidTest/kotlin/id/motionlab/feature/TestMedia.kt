package id.motionlab.feature

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Real media files generated ON THE DEVICE for the instrumented tests (no bundled sample content, nothing downloaded). */
object TestMedia {
    fun png(dir: File, name: String, w: Int, h: Int, color: Int = 0xFF336699.toInt()): File {
        val f = File(dir, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    fun wav(dir: File, name: String, seconds: Int, rate: Int = 8000): File {
        val n = seconds * rate
        val pcm = ByteArrayOutputStream()
        for (i in 0 until n) {
            val v = (sin(2 * PI * 440 * i / rate) * 20000).toInt()
            pcm.write(v and 0xFF); pcm.write((v shr 8) and 0xFF)
        }
        val data = pcm.toByteArray()
        val out = ByteArrayOutputStream()
        fun le(v: Int, bytes: Int) { for (i in 0 until bytes) out.write((v shr (8 * i)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(36 + data.size, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(data.size, 4); out.write(data)
        return File(dir, name).also { it.writeBytes(out.toByteArray()) }
    }

    /**
     * Encodes a real H.264 MP4 whose brightness rises with every frame (luma = 20 + 8 * frame), with a key frame only at the
     * start. Returns null when the device has no suitable encoder (the caller then skips the video tests).
     */
    fun mp4(dir: File, name: String, w: Int = 320, h: Int = 240, frames: Int = 30, fps: Int = 15): File? {
        val file = File(dir, name)
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 60)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mux = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxStarted = false
            val info = MediaCodec.BufferInfo()
            var sent = 0
            var inputDone = false
            var done = false
            val deadline = System.currentTimeMillis() + 20_000
            while (!done && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val pts = sent * 1_000_000L / fps
                        if (sent == frames) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            fill(enc.getInputImage(i)!!, w, h, 20 + 8 * sent)
                            enc.queueInputBuffer(i, 0, w * h * 3 / 2, pts, 0)
                            sent++
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start(); muxStarted = true }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxStarted) mux.writeSampleData(track, buf, info)
                        enc.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
            enc.stop(); enc.release()
            if (muxStarted) mux.stop()
            mux.release()
            if (done && file.length() > 0) file else null
        } catch (e: Exception) {
            null
        }
    }

    private fun fill(img: Image, w: Int, h: Int, luma: Int) {
        val y = img.planes[0]
        for (r in 0 until h) for (c in 0 until w) y.buffer.put(r * y.rowStride + c * y.pixelStride, luma.toByte())
        for (p in 1..2) {
            val pl = img.planes[p]
            for (r in 0 until h / 2) for (c in 0 until w / 2) pl.buffer.put(r * pl.rowStride + c * pl.pixelStride, 128.toByte())
        }
    }

    /** Mean brightness (0..255) of a bitmap, sampled on a coarse grid. */
    fun meanLuma(b: Bitmap): Double {
        var sum = 0.0
        var n = 0
        val sx = (b.width / 16).coerceAtLeast(1)
        val sy = (b.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                n++; x += sx
            }
            y += sy
        }
        return sum / n
    }
}
