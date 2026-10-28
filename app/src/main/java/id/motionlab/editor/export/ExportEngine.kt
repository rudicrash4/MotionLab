package id.motionlab.editor.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.FileProvider
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.TrackType
import id.motionlab.editor.render.GlCompositor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pengaturan ekspor yang dipilih pengguna. */
data class ExportSettings(
    val shortSide: Int = 1080,
    val fps: Int = 30,
    /** 0 = rendah, 1 = sedang, 2 = tinggi. */
    val quality: Int = 1
)

data class ExportResult(
    val uri: Uri?,
    val displayPath: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val warnings: List<String>
)

class ExportCancelled : RuntimeException("Ekspor dibatalkan")

/** Kemampuan encoder H.264 perangkat ini. */
object ExportCaps {
    private fun encoder(): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val all = list.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
        return all.firstOrNull { !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.") } ?: all.firstOrNull()
    }

    fun videoCaps(): MediaCodecInfo.VideoCapabilities? = try {
        encoder()?.getCapabilitiesForType("video/avc")?.videoCapabilities
    } catch (_: Exception) { null }

    /** Ukuran ekspor untuk proyek, disesuaikan dengan alignment encoder. */
    fun sizeFor(project: Project, shortSide: Int): Pair<Int, Int> {
        val (w0, h0) = project.copy(shortSide = shortSide).exportSize()
        val caps = videoCaps() ?: return Pair(w0, h0)
        val wa = caps.widthAlignment.coerceAtLeast(2)
        val ha = caps.heightAlignment.coerceAtLeast(2)
        return Pair(maxOf(wa, (w0 / wa) * wa), maxOf(ha, (h0 / ha) * ha))
    }

    fun supports(w: Int, h: Int, fps: Int): Boolean {
        val caps = videoCaps() ?: return false
        return try {
            caps.isSizeSupported(w, h) && caps.areSizeAndRateSupported(w, h, fps.toDouble())
        } catch (_: Exception) { false }
    }

    /** Resolusi (sisi pendek) yang didukung perangkat pada setidaknya satu frame rate. */
    fun supportedShortSides(project: Project): List<Int> =
        listOf(720, 1080, 1440, 2160).filter { s ->
            val (w, h) = sizeFor(project, s)
            listOf(24, 25, 30, 60).any { supports(w, h, it) }
        }

    fun supportedFps(project: Project, shortSide: Int): List<Int> {
        val (w, h) = sizeFor(project, shortSide)
        return listOf(24, 25, 30, 60).filter { supports(w, h, it) }
    }

    fun bitrateFor(w: Int, h: Int, fps: Int, quality: Int): Int {
        val bpp = when (quality) { 0 -> 0.06 else -> if (quality == 1) 0.10 else 0.16 }
        var br = (w.toDouble() * h * fps * bpp).toInt()
        val range = videoCaps()?.bitrateRange
        if (range != null) br = br.coerceIn(range.lower, range.upper)
        return br.coerceAtLeast(500_000)
    }
}

/** Tujuan file hasil: MediaStore (Android 10+) atau folder Movies milik aplikasi (Android 8-9). */
class ExportOutput(private val context: Context, projectName: String) {
    private val displayName: String
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var file: File? = null
    var displayPath: String = ""
        private set

    init {
        val safe = projectName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "MotionLab" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "${safe}_$stamp.mp4"
    }

    fun createMuxer(): MediaMuxer {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MotionLab")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("Tidak bisa membuat file di galeri (MediaStore).")
            uri = u
            val p = context.contentResolver.openFileDescriptor(u, "rw")
                ?: throw RuntimeException("Tidak bisa membuka file keluaran.")
            pfd = p
            displayPath = "Movies/MotionLab/$displayName"
            return MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val f = File(dir, displayName)
        file = f
        displayPath = f.absolutePath
        return MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Menutup keluaran. Bila gagal/dibatalkan, file parsial (yang dibuat ekspor ini) dihapus. */
    fun finish(success: Boolean): Uri? {
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
        if (Build.VERSION.SDK_INT >= 29) {
            val u = uri ?: return null
            return if (success) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                try { context.contentResolver.update(u, v, null, null) } catch (_: Exception) { }
                u
            } else {
                try { context.contentResolver.delete(u, null, null) } catch (_: Exception) { }
                null
            }
        }
        val f = file ?: return null
        if (!success) { f.delete(); return null }
        return try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        } catch (_: Exception) { null }
    }
}

/** EGL untuk menggambar ke Surface input encoder. */
private class EglCore(private val target: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("EGL display tidak tersedia")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("eglInitialize gagal")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0 || configs[0] == null) {
            throw RuntimeException("Konfigurasi EGL perekaman tidak tersedia")
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext gagal")
        surface = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface gagal")
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw RuntimeException("eglMakeCurrent gagal")
    }

    fun setPresentationTime(ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, ns)
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        try { target.release() } catch (_: Exception) { }
    }
}

/**
 * Mesin ekspor MP4 (H.264 + AAC). Memakai [GlCompositor] yang SAMA dengan preview untuk menggambar semua layer,
 * efek, transisi dan kurva Speed Ramp; hanya targetnya berbeda (Surface input encoder). Fungsi [run]
 * bersifat blocking dan HARUS dijalankan di satu thread khusus (EGL context terikat thread).
 */
class ExportEngine(private val context: Context) {

    private class Active(val clip: Clip, val source: VideoFrameSource, var started: Boolean = false)

    fun run(
        project: Project,
        settings: ExportSettings,
        progress: (Float, String) -> Unit,
        cancelled: () -> Boolean
    ): ExportResult {
        val durationMs = project.durationMs
        if (durationMs <= 0L) throw RuntimeException("Proyek kosong — tidak ada yang diekspor.")
        val (w, h) = ExportCaps.sizeFor(project, settings.shortSide)
        if (!ExportCaps.supports(w, h, settings.fps)) {
            throw RuntimeException("Perangkat ini tidak mendukung encoder H.264 ${w}x$h @${settings.fps} fps.")
        }
        val fps = settings.fps
        val totalFrames = Math.ceil(durationMs * fps / 1000.0).toLong().coerceAtLeast(1L)
        val warnings = ArrayList<String>()
        val tmpDir = File(context.cacheDir, "export_tmp").apply { mkdirs() }
        tmpDir.listFiles()?.forEach { it.delete() }

        // ---------------- Tahap 1: audio ----------------
        progress(0f, "Menyiapkan audio…")
        val totalAudioFrames = Math.round(durationMs * EXPORT_AUDIO_RATE / 1000.0)
        val aac = try {
            AudioMixer.mixToAac(context, project, totalAudioFrames, tmpDir,
                { p -> progress(p * 0.2f, "Memproses audio…") }, cancelled, warnings)
        } catch (e: ExportCancelled) { throw e } catch (e: Exception) {
            warnings.add("Audio dilewati: " + (e.message ?: e.javaClass.simpleName))
            null
        }
        if (cancelled()) throw ExportCancelled()

        // ---------------- Tahap 2: video ----------------
        val output = ExportOutput(context, project.name)
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var egl: EglCore? = null
        var compositor: GlCompositor? = null
        val actives = HashMap<String, Active>()
        var success = false
        var resultUri: Uri? = null
        try {
            val format = MediaFormat.createVideoFormat("video/avc", w, h)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, ExportCaps.bitrateFor(w, h, fps, settings.quality))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val enc = MediaCodec.createEncoderByType("video/avc")
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            enc.start()
            val core = EglCore(inputSurface)
            egl = core
            core.makeCurrent()

            val comp = GlCompositor(context.applicationContext) { }
            compositor = comp
            comp.blockingLoads = true
            comp.initGl()
            if (comp.lastError.isNotEmpty()) throw RuntimeException(comp.lastError)

            val mux = output.createMuxer()
            muxer = mux
            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false
            var audioIdx = 0
            val info = MediaCodec.BufferInfo()

            fun writeAudioUpTo(limitUs: Long) {
                if (!muxerStarted || aac == null) return
                while (audioIdx < aac.packets.size && aac.packets[audioIdx].ptsUs <= limitUs) {
                    val pk = aac.packets[audioIdx]
                    val bi = MediaCodec.BufferInfo()
                    bi.set(0, pk.data.size, pk.ptsUs, pk.flags)
                    mux.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(pk.data), bi)
                    audioIdx++
                }
            }

            fun drain(endOfStream: Boolean) {
                if (endOfStream) enc.signalEndOfInputStream()
                var idle = 0
                while (true) {
                    val oi = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                        idle++
                        if (idle > 1000) throw RuntimeException("Encoder video tidak merespons.")
                        continue
                    }
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw RuntimeException("Format encoder berubah di tengah ekspor.")
                        videoTrack = mux.addTrack(enc.outputFormat)
                        if (aac != null) audioTrack = mux.addTrack(aac.format)
                        mux.start()
                        muxerStarted = true
                        continue
                    }
                    if (oi < 0) continue
                    val ob = enc.getOutputBuffer(oi) ?: throw RuntimeException("Buffer encoder null")
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0) {
                        if (!muxerStarted) throw RuntimeException("Muxer belum dimulai")
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        writeAudioUpTo(info.presentationTimeUs)
                        mux.writeSampleData(videoTrack, ob, info)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    enc.releaseOutputBuffer(oi, false)
                    if (eos) break
                }
            }

            val videoClips = ArrayList<Clip>()
            for (t in project.tracks) if (t.type == TrackType.VIDEO && !t.hidden) videoClips.addAll(t.clips)

            for (frame in 0 until totalFrames) {
                if (cancelled()) throw ExportCancelled()
                val tMs = Math.round(frame * 1000.0 / fps)
                val tExact = frame * 1000.0 / fps
                val ptsNs = Math.round(frame * 1_000_000_000.0 / fps)

                // Decoder: buat saat klip mulai dipakai, lepas saat selesai.
                val liveIds = HashSet<String>()
                for (c in videoClips) {
                    if (tMs < c.startMs || tMs >= c.endMs) continue
                    liveIds.add(c.id)
                    var a = actives[c.id]
                    if (a == null) {
                        val surface = comp.createVideoSurface(c.id)
                        val src = VideoFrameSource(context, c.uri, surface)
                        val err = src.open()
                        if (err != null) {
                            src.close()
                            comp.releaseVideoSurface(c.id)
                            throw RuntimeException("Tidak bisa membuka video \"${c.name}\": $err")
                        }
                        comp.setVideoAspect(c.id, src.aspect)
                        a = Active(c, src)
                        actives[c.id] = a
                    }
                    val local = tExact - c.startMs
                    val srcUs = Math.round(c.sourceTimeAt(local) * 1000.0)
                    if (!a.started) { a.source.seek(srcUs); a.started = true }
                    if (a.source.advanceTo(srcUs)) comp.consumeVideoFrame(c.id, 2000L)
                }
                for (id in actives.keys.toList()) {
                    if (!liveIds.contains(id)) {
                        actives.remove(id)?.source?.close()
                        comp.releaseVideoSurface(id)
                    }
                }

                comp.renderFrame(project, tMs, w, h)
                core.setPresentationTime(ptsNs)
                if (!core.swap()) throw RuntimeException("eglSwapBuffers gagal")
                drain(false)
                if (frame % 4L == 0L) {
                    progress(0.2f + 0.78f * (frame.toFloat() / totalFrames.toFloat()), "Merender frame ${frame + 1} / $totalFrames")
                }
            }
            if (cancelled()) throw ExportCancelled()
            progress(0.98f, "Menyelesaikan file…")
            drain(true)
            if (!muxerStarted) throw RuntimeException("Tidak ada frame video yang dihasilkan.")
            writeAudioUpTo(Long.MAX_VALUE)
            mux.stop()
            muxer = null
            mux.release()
            for (f in comp.failedAssets()) warnings.add("Aset gagal dimuat: " + f.substringAfter(':').takeLast(40))
            success = true
        } finally {
            for (a in actives.values) { try { a.source.close() } catch (_: Exception) { } }
            try { compositor?.shutdown() } catch (_: Exception) { }
            try { encoder?.stop() } catch (_: Exception) { }
            try { encoder?.release() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            try { egl?.release() } catch (_: Exception) { }
            resultUri = output.finish(success)
            tmpDir.listFiles()?.forEach { it.delete() }
        }
        progress(1f, "Selesai")
        return ExportResult(resultUri, output.displayPath, w, h, durationMs, aac != null, warnings)
    }
}
