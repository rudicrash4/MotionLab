package id.motionlab.engine.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.BoundedCache
import id.motionlab.core.resource.WaveformPeaks
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds and caches waveform peaks. The audio is decoded as a STREAM (MediaCodec output buffer by buffer), reduced to a few
 * thousand min/max pairs and thrown away, so a long file never sits in memory. Peaks are cached in memory (tiny) and on
 * disk (so they survive restarts). Blocking: run on a background dispatcher; [cancelled] is polled between buffers.
 */
class WaveformProvider(private val context: Context) {
    private val memory = BoundedCache<String, WaveformPeaks>(2L * 1024 * 1024, { it.toBytes().size.toLong() })
    private val dir: File get() = File(context.cacheDir, "waveforms").also { it.mkdirs() }

    fun cached(assetId: String): WaveformPeaks? = memory[assetId]

    fun drop(assetId: String) {
        memory.remove(assetId)
        File(dir, "$assetId.wf").delete()
    }

    fun load(asset: AssetRefDto, cancelled: () -> Boolean = { false }): WaveformPeaks? {
        memory[asset.id]?.let { return it }
        readDisk(asset)?.let { memory.put(asset.id, it); return it }
        val built = try {
            build(asset, cancelled)
        } catch (e: Exception) {
            MlLog.w("WaveformProvider", "waveform failed for ${asset.displayName}: ${e.message}")
            null
        } ?: return null
        memory.put(asset.id, built)
        writeDisk(asset, built)
        return built
    }

    private fun readDisk(asset: AssetRefDto): WaveformPeaks? = try {
        val f = File(dir, "${asset.id}.wf")
        if (!f.isFile) null else {
            val b = f.readBytes()
            val bb = ByteBuffer.wrap(b)
            val duration = bb.getLong(0)
            if (duration != asset.durationUs || b.size <= 8) null else WaveformPeaks.fromBytes(duration, b.copyOfRange(8, b.size))
        }
    } catch (e: Exception) {
        null
    }

    private fun writeDisk(asset: AssetRefDto, w: WaveformPeaks) {
        try {
            val body = w.toBytes()
            val bb = ByteBuffer.allocate(8 + body.size)
            bb.putLong(asset.durationUs)
            bb.put(body)
            val tmp = File(dir, "${asset.id}.wf.tmp")
            tmp.writeBytes(bb.array())
            tmp.renameTo(File(dir, "${asset.id}.wf"))
        } catch (e: Exception) {
            MlLog.w("WaveformProvider", "could not cache waveform: ${e.message}")
        }
    }

    private fun build(asset: AssetRefDto, cancelled: () -> Boolean): WaveformPeaks? {
        val uri = Uri.parse(asset.uri)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file" || uri.scheme == null) ex.setDataSource(uri.path ?: asset.uri) else ex.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = if (asset.durationUs > 0) asset.durationUs else (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L)
            if (durationUs <= 0) return null
            var builder = WaveformPeaks.Builder(WaveformPeaks.bucketsFor(durationUs), durationUs, rate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcm16 = true
            var scratch = ShortArray(0)
            while (!outputDone) {
                if (cancelled()) return null
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcm16 = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT
                        }
                        builder = WaveformPeaks.Builder(WaveformPeaks.bucketsFor(durationUs), durationUs, rate, channels)
                    }
                    ob >= 0 -> {
                        if (info.size > 0 && pcm16) {
                            val out = codec.getOutputBuffer(ob)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = shorts.remaining()
                            if (scratch.size < n) scratch = ShortArray(n)
                            shorts.get(scratch, 0, n)
                            builder.addPcm16(scratch, n)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(ob, false)
                    }
                }
            }
            if (!pcm16) {
                MlLog.w("WaveformProvider", "decoder produced non-16-bit PCM for ${asset.displayName}; waveform unavailable")
                return null
            }
            return builder.build()
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }
}
