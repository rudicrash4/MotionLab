package id.motionlab.editor.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.TrackType
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

const val EXPORT_AUDIO_RATE = 44100

/** Hasil decode audio sumber ke file PCM16 stereo pada sample rate asli. */
class PcmFile(val file: File, val rate: Int, val startFrame: Long, val frames: Long)

class DecodeInfo(val rate: Int, val firstFrame: Long, val frames: Long)

object AudioDecoder {
    /**
     * Decode audio dari [uri] untuk rentang sumber [startUs]..[endUs]. Setiap potongan PCM16 stereo
     * (little-endian, interleaved) dikirim ke [sink] sebagai (bytes, jumlahFrame).
     * Mengembalikan null bila sumber tidak punya audio / tidak bisa didecode / dibatalkan.
     */
    fun decodeStereo16(
        context: Context, uri: String, startUs: Long, endUs: Long, cancelled: () -> Boolean,
        sink: (ByteArray, Int) -> Unit
    ): DecodeInfo? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            extractor.seekTo(startUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var firstFrame = -1L
            var frames = 0L
            var done = false
            var idle = 0
            var tmp = ByteArray(0)
            while (!done) {
                if (cancelled()) return null
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(5_000L)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val st = extractor.sampleTime
                        val n = if (st > endUs) -1 else extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, st, 0)
                            extractor.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000L)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    idle++
                    if (idle > 600) done = true
                } else if (oi >= 0) idle = 0
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val n = info.size / (bytesPerSample * channels)
                        if (firstFrame < 0) firstFrame = Math.round(info.presentationTimeUs * rate / 1_000_000.0)
                        if (tmp.size < n * 4) tmp = ByteArray(n * 4)
                        val bb = ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
                        for (f in 0 until n) {
                            val l: Short
                            val r: Short
                            if (floatPcm) {
                                val fl = ob.getFloat()
                                var fr = fl
                                for (ch in 1 until channels) { val v = ob.getFloat(); if (ch == 1) fr = v }
                                l = (fl.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                r = (fr.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            } else {
                                l = ob.getShort()
                                var rv = l
                                for (ch in 1 until channels) { val v = ob.getShort(); if (ch == 1) rv = v }
                                r = rv
                            }
                            bb.putShort(f * 4, l)
                            bb.putShort(f * 4 + 2, r)
                        }
                        sink(tmp, n)
                        frames += n
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val past = info.presentationTimeUs > endUs
                    c.releaseOutputBuffer(oi, false)
                    if (eos || past) done = true
                }
            }
            if (frames <= 0L || firstFrame < 0) return null
            return DecodeInfo(rate, firstFrame, frames)
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    /** Decode ke file PCM16 stereo (dipakai mixer ekspor). */
    fun decodeToFile(
        context: Context, uri: String, startUs: Long, endUs: Long, out: File, cancelled: () -> Boolean
    ): PcmFile? {
        var info: DecodeInfo? = null
        try {
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                info = decodeStereo16(context, uri, startUs, endUs, cancelled) { bytes, n -> os.write(bytes, 0, n * 4) }
            }
        } catch (e: Exception) {
            return null
        }
        val i = info ?: return null
        return PcmFile(out, i.rate, i.firstFrame, i.frames)
    }

    /**
     * Puncak amplitudo (0..1) per bucket 10 ms untuk SELURUH audio sumber; indeks = waktu sumber / 10 ms.
     * Dipakai untuk menggambar waveform asli di timeline.
     */
    fun peaks(context: Context, uri: String, cancelled: () -> Boolean): FloatArray? {
        var list = FloatArray(4096)
        var count = 0
        var curMax = 0
        var inBucket = 0
        var bucketFrames = 441
        var rateKnown = 0
        val info = decodeStereo16(context, uri, 0L, Long.MAX_VALUE / 4, cancelled) { bytes, n ->
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until n) {
                val a = abs(bb.getShort(f * 4).toInt())
                val b = abs(bb.getShort(f * 4 + 2).toInt())
                val m = if (a > b) a else b
                if (m > curMax) curMax = m
                inBucket++
                if (inBucket >= bucketFrames) {
                    if (count >= list.size) list = list.copyOf(list.size * 2)
                    list[count++] = curMax / 32768f
                    curMax = 0
                    inBucket = 0
                }
            }
            rateKnown++
        }
        if (info == null) return null
        if (inBucket > 0) {
            if (count >= list.size) list = list.copyOf(list.size + 1)
            list[count++] = curMax / 32768f
        }
        // Catatan: bucket dihitung 441 frame (10 ms pada 44,1 kHz). Untuk sumber 48 kHz skala dikoreksi di bawah.
        val scale = info.rate / 44100f
        if (kotlin.math.abs(scale - 1f) > 0.01f) {
            // Resample indeks ke 10 ms sebenarnya.
            val outN = Math.round(count * 441f / (info.rate / 100f)).toInt().coerceAtLeast(1)
            val out = FloatArray(outN)
            for (i in 0 until outN) out[i] = list[(i * count.toLong() / outN).toInt().coerceIn(0, count - 1)]
            return out
        }
        return list.copyOf(count)
    }
}

/**
 * Time-stretch WSOLA (pitch dipertahankan) untuk satu klip, mengikuti kurva Speed Ramp.
 * Setiap grain 1024 sampel (hop 512, jendela Hann) diambil dari posisi sumber hasil integral kecepatan
 * pada pusat grain, lalu disejajarkan (korelasi) dengan kelanjutan grain sebelumnya. Pada 1x grain
 * berurutan sama persis sehingga sinyal tidak berubah. Output 44.1 kHz stereo float.
 */
class AudioStretcher(private val clip: Clip, private val pcm: PcmFile) : Closeable {
    companion object {
        private const val N = 1024
        private const val HA = 512
        private const val SEARCH = 384
        private const val STEP = 4
        private const val CORR_LEN = 256
        const val MAX_READ = 8192
    }

    private val raf = RandomAccessFile(pcm.file, "r")
    private val map: MappedByteBuffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, pcm.frames * 4)
    init { map.order(ByteOrder.LITTLE_ENDIAN) }

    private val ratio = pcm.rate.toDouble() / EXPORT_AUDIO_RATE
    private val speedMap = clip.speedMap
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }
    val totalFrames: Long = Math.round(clip.durationMs * EXPORT_AUDIO_RATE / 1000.0)

    private val cap = MAX_READ + 2 * N
    private val buf = FloatArray(cap * 2)
    private var bufBase = -HA.toLong()
    private var complete = -HA.toLong()
    private var hop = -1L
    private var prevStart = Double.NaN
    private var readPos = 0L
    private val tgt = FloatArray(CORR_LEN / 2)

    private fun sampleAt(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= pcm.frames) return 0f
        return map.getShort((frame * 4 + ch * 2).toInt()) / 32768f
    }

    private fun mono(absFrame: Double): Float {
        val f = Math.round(absFrame) - pcm.startFrame
        if (f < 0 || f >= pcm.frames) return 0f
        return (map.getShort((f * 4).toInt()) + map.getShort((f * 4 + 2).toInt())) / 65536f
    }

    private fun synthesize() {
        val k = hop
        val outStart = k * HA
        val centerMs = (outStart + N / 2).toDouble() * 1000.0 / EXPORT_AUDIO_RATE
        val centerSrcMs = clip.sourceTimeAt(centerMs)
        val nominal = centerSrcMs * pcm.rate / 1000.0 - (N / 2) * ratio
        var start = nominal
        if (!prevStart.isNaN()) {
            val natural = prevStart + HA * ratio
            if (abs(nominal - natural) > 0.5) {
                for (j in tgt.indices) tgt[j] = mono(natural + j * 2 * ratio)
                var best = Double.NEGATIVE_INFINITY
                var bestD = 0
                var d = -SEARCH
                while (d <= SEARCH) {
                    val c = nominal + d
                    var dot = 0.0
                    var en = 1e-6
                    for (j in tgt.indices) {
                        val v = mono(c + j * 2 * ratio)
                        dot += tgt[j] * v
                        en += v * v
                    }
                    val score = dot / sqrt(en)
                    if (score > best) { best = score; bestD = d }
                    d += STEP
                }
                start = nominal + bestD
            }
        }
        val base = (outStart - bufBase).toInt()
        for (i in 0 until N) {
            val pos = start + i * ratio - pcm.startFrame
            val i0 = floor(pos).toLong()
            val fr = (pos - i0).toFloat()
            val w = window[i]
            val l = sampleAt(i0, 0) * (1f - fr) + sampleAt(i0 + 1, 0) * fr
            val r = sampleAt(i0, 1) * (1f - fr) + sampleAt(i0 + 1, 1) * fr
            val idx = (base + i) * 2
            buf[idx] += l * w
            buf[idx + 1] += r * w
        }
        prevStart = start
        hop++
        complete = hop * HA
    }

    /** Membaca [count] frame stereo berikutnya (berurutan dari awal klip) ke [dst] (interleaved). */
    fun read(count: Int, dst: FloatArray) {
        val startOut = readPos
        val endOut = startOut + count
        while (complete < endOut && (hop * HA) < totalFrames + HA) synthesize()
        for (i in 0 until count) {
            val o = startOut + i
            val bi = (o - bufBase).toInt()
            if (o < totalFrames && o < complete && bi >= 0 && bi < cap) {
                dst[i * 2] = buf[bi * 2]
                dst[i * 2 + 1] = buf[bi * 2 + 1]
            } else {
                dst[i * 2] = 0f
                dst[i * 2 + 1] = 0f
            }
        }
        readPos = endOut
        val shift = (endOut - bufBase).toInt()
        if (shift > 0) {
            val s = if (shift > cap) cap else shift
            System.arraycopy(buf, s * 2, buf, 0, (cap - s) * 2)
            java.util.Arrays.fill(buf, (cap - s) * 2, cap * 2, 0f)
            bufBase += s
        }
    }

    override fun close() {
        try { raf.close() } catch (_: Exception) { }
    }
}

class AacPacket(val data: ByteArray, val flags: Int, val ptsUs: Long)

class AacResult(val format: MediaFormat, val packets: List<AacPacket>)

object AudioMixer {
    private const val BLOCK = 2048

    /**
     * Mixdown semua klip audio/video (track yang terdengar) lalu encode AAC-LC 192 kbps.
     * Mengembalikan null bila tidak ada audio yang bisa dipakai. [warnings] diisi untuk klip yang gagal.
     */
    fun mixToAac(
        context: Context, project: Project, totalFrames: Long, tmpDir: File,
        progress: (Float) -> Unit, cancelled: () -> Boolean, warnings: MutableList<String>
    ): AacResult? {
        class Item(val clip: Clip, val stretcher: AudioStretcher, val startFrame: Long, val endFrame: Long, val pcm: PcmFile)
        val items = ArrayList<Item>()
        val clips = ArrayList<Clip>()
        for (t in project.tracks) {
            if (t.type != TrackType.VIDEO && t.type != TrackType.AUDIO) continue
            if (!project.isTrackAudible(t)) continue
            for (c in t.clips) if (c.uri.isNotEmpty() && c.volume > 0.001f) clips.add(c)
        }
        try {
            for ((n, c) in clips.withIndex()) {
                if (cancelled()) return null
                val srcStartUs = (c.sourceInMs - 200L).coerceAtLeast(0L) * 1000L
                val srcEndUs = (c.sourceEndMs + 300L) * 1000L
                val f = File(tmpDir, "aud_${n}_${System.nanoTime()}.pcm")
                val pcm = AudioDecoder.decodeToFile(context, c.uri, srcStartUs, srcEndUs, f) { cancelled() }
                if (pcm == null) {
                    f.delete()
                    if (c.type == TrackType.AUDIO) warnings.add("Audio \"${c.name}\" tidak bisa didecode / tidak punya audio.")
                    continue
                }
                val s = Math.round(c.startMs * EXPORT_AUDIO_RATE / 1000.0)
                val st = AudioStretcher(c, pcm)
                items.add(Item(c, st, s, s + st.totalFrames, pcm))
                progress(0.4f * (n + 1) / clips.size)
            }
            if (items.isEmpty()) return null

            val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", EXPORT_AUDIO_RATE, 2)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val packets = ArrayList<AacPacket>()
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()

            fun drain(): Boolean {
                var eos = false
                while (true) {
                    val oi = enc.dequeueOutputBuffer(info, 0)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = enc.outputFormat; continue }
                    if (oi < 0) continue
                    val ob = enc.getOutputBuffer(oi)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val arr = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(arr)
                        packets.add(AacPacket(arr, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME, info.presentationTimeUs))
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
                    enc.releaseOutputBuffer(oi, false)
                    if (eos) break
                }
                return eos
            }

            val mix = FloatArray(BLOCK * 2)
            val tmp = FloatArray(BLOCK * 2)
            val pcm16 = ByteArray(BLOCK * 4)
            var pos = 0L
            var feedEos = false
            while (pos < totalFrames) {
                if (cancelled()) { try { enc.stop() } catch (_: Exception) { }; enc.release(); return null }
                val n = minOf(BLOCK.toLong(), totalFrames - pos).toInt()
                java.util.Arrays.fill(mix, 0, n * 2, 0f)
                for (it in items) {
                    val lo = maxOf(pos, it.startFrame)
                    val hi = minOf(pos + n, it.endFrame)
                    if (lo >= hi) continue
                    val cnt = (hi - lo).toInt()
                    it.stretcher.read(cnt, tmp)
                    val c = it.clip
                    val vol = if (c.isMuted) 0f else c.volume.coerceIn(0f, 4f)
                    for (i in 0 until cnt) {
                        val localMs = (lo + i - it.startFrame) * 1000.0 / EXPORT_AUDIO_RATE
                        val g = vol * c.fadeGain(localMs)
                        val mi = (lo - pos + i).toInt() * 2
                        mix[mi] += tmp[i * 2] * g
                        mix[mi + 1] += tmp[i * 2 + 1] * g
                    }
                }
                for (i in 0 until n * 2) {
                    val v = softClip(mix[i])
                    val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                    pcm16[i * 2] = (s and 0xFF).toByte()
                    pcm16[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                var queued = false
                while (!queued) {
                    val ii = enc.dequeueInputBuffer(5_000L)
                    if (ii >= 0) {
                        val ib = enc.getInputBuffer(ii)!!
                        ib.clear()
                        ib.put(pcm16, 0, n * 4)
                        enc.queueInputBuffer(ii, 0, n * 4, pos * 1_000_000L / EXPORT_AUDIO_RATE, 0)
                        queued = true
                    }
                    drain()
                }
                pos += n
                progress(0.4f + 0.6f * (pos.toFloat() / totalFrames.toFloat()))
            }
            while (!feedEos) {
                val ii = enc.dequeueInputBuffer(5_000L)
                if (ii >= 0) {
                    enc.queueInputBuffer(ii, 0, 0, totalFrames * 1_000_000L / EXPORT_AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    feedEos = true
                }
                drain()
            }
            var guard = 0
            while (!drain() && guard < 2000) { guard++; try { Thread.sleep(5) } catch (_: InterruptedException) { break } }
            try { enc.stop() } catch (_: Exception) { }
            enc.release()
            val of = outFormat ?: return null
            return AacResult(of, packets)
        } finally {
            for (it in items) {
                it.stretcher.close()
                it.pcm.file.delete()
            }
        }
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val s = if (x < 0f) -1f else 1f
        val over = (a - 0.9f) / 0.1f
        val t = (kotlin.math.tanh(over.toDouble())).toFloat()
        return s * (0.9f + 0.1f * t)
    }
}
