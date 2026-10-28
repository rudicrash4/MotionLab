package id.motionlab.editor.ui

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.motionlab.editor.core.PlaybackClock
import id.motionlab.editor.export.ExportCancelled
import id.motionlab.editor.export.ExportEngine
import id.motionlab.editor.export.ExportResult
import id.motionlab.editor.export.ExportSettings
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.ProjectInfo
import id.motionlab.editor.core.ProjectStore
import id.motionlab.editor.core.Props
import id.motionlab.editor.core.ShapeKind
import id.motionlab.editor.core.TimelineController
import id.motionlab.editor.core.TrackMapping
import id.motionlab.editor.core.TrackType
import id.motionlab.editor.render.PreviewRenderer
import id.motionlab.editor.render.VideoSourceManager
import id.motionlab.editor.track.TrackPostProcess
import id.motionlab.editor.track.VideoTrackingJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Area pelacakan pada frame video (ternormalisasi): pusat (nx, ny) dan lebar patch sebagai pecahan lebar frame. */
data class TrackRegion(val nx: Float = 0.5f, val ny: Float = 0.5f, val size: Float = 0.12f)

enum class ExportPhase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

data class ExportUi(
    val phase: ExportPhase = ExportPhase.IDLE,
    val progress: Float = 0f,
    val message: String = "",
    val result: ExportResult? = null
)

data class TrackingUi(val running: Boolean = false, val progress: Float = 0f, val message: String = "")

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val controller = TimelineController()
    val clock = PlaybackClock()
    private val store = ProjectStore(app)

    val renderer = PreviewRenderer(app, clock) { controller.project.value }

    private val _status = MutableStateFlow("Siap mengedit")
    val status: StateFlow<String> = _status.asStateFlow()

    val videoManager = VideoSourceManager(app, renderer) { msg -> _status.value = msg }

    private val _playhead = MutableStateFlow(0L)
    val playhead: StateFlow<Long> = _playhead.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _tracking = MutableStateFlow(TrackingUi())
    val tracking: StateFlow<TrackingUi> = _tracking.asStateFlow()

    private val _trackRegion = MutableStateFlow(TrackRegion())
    val trackRegion: StateFlow<TrackRegion> = _trackRegion.asStateFlow()

    private val _trackOverlay = MutableStateFlow(false)
    val trackOverlay: StateFlow<Boolean> = _trackOverlay.asStateFlow()

    private var trackJob: Job? = null

    // ---- ekspor ----
    private val _export = MutableStateFlow(ExportUi())
    val export: StateFlow<ExportUi> = _export.asStateFlow()
    private val exportExecutor = Executors.newSingleThreadExecutor()
    private val exportDispatcher = exportExecutor.asCoroutineDispatcher()
    @Volatile private var exportCancel = false
    private var exportJob: Job? = null
    private var lastDirtyAt = 0L

    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    private val _missing = MutableStateFlow<List<String>>(emptyList())
    /** Nama media yang tidak bisa dibuka lagi (file dihapus / izin hilang). Klipnya tetap ada di proyek. */
    val missingMedia: StateFlow<List<String>> = _missing.asStateFlow()

    init {
        val loaded = store.loadInitial()
        if (loaded != null) {
            controller.load(loaded.project)
            if (loaded.recovered) _status.value = "Proyek dipulihkan dari cadangan (file utama rusak; salinan rusak disimpan sebagai .corrupt)."
        }
        store.lastOpenedId = controller.project.value.id
        checkMissingMedia(controller.project.value)
        // Autosave dengan debounce 700 ms di thread IO + minta render ulang tiap proyek berubah.
        viewModelScope.launch {
            controller.project.collectLatest { p ->
                markDirty()
                renderer.requestRender()
                delay(700)
                withContext(Dispatchers.IO) { saveStamped(p) }
            }
        }
    }

    private fun saveStamped(p: Project) {
        // Proyek kosong yang belum pernah disimpan tidak perlu menghasilkan file.
        if (p.tracks.isEmpty() && !store.exists(p.id)) return
        store.save(p.copy(updatedAt = System.currentTimeMillis()))
    }

    // ---------------------------------------------------------------- manajemen proyek

    private val _fxClipboard = MutableStateFlow<id.motionlab.editor.core.EffectSet?>(null)
    val fxClipboard: StateFlow<id.motionlab.editor.core.EffectSet?> = _fxClipboard.asStateFlow()

    fun copyEffects(clipId: String) {
        _fxClipboard.value = controller.captureEffects(clipId)
        _status.value = "Efek disalin. Pilih klip lain lalu tekan Tempel."
    }

    fun refreshProjects() {
        viewModelScope.launch { _projects.value = withContext(Dispatchers.IO) { store.list() } }
    }

    fun checkMissingMedia(p: Project) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _missing.value = withContext(Dispatchers.IO) {
                val out = LinkedHashSet<String>()
                for (t in p.tracks) for (c in t.clips) {
                    if (c.uri.isNotEmpty() && c.type != TrackType.TEXT && c.type != TrackType.SHAPE && !canOpen(ctx, c.uri)) out.add(c.name)
                    if (c.lutUri.isNotEmpty() && !canOpen(ctx, c.lutUri)) out.add("LUT: " + c.name)
                    if (c.fontUri.isNotEmpty() && !java.io.File(c.fontUri).exists()) out.add("Font: " + c.fontName)
                }
                out.toList()
            }
        }
    }

    private fun canOpen(ctx: Application, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == null || uri.scheme == "file") {
                java.io.File(uri.path ?: uriString).exists()
            } else {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Menyimpan proyek aktif segera (dipanggil saat berpindah proyek / aplikasi berhenti). */
    fun flushSave() {
        val p = controller.project.value
        viewModelScope.launch(Dispatchers.IO) { saveStamped(p) }
    }

    private suspend fun switchTo(p: Project, status: String) {
        pause()
        withContext(Dispatchers.IO) { saveStamped(controller.project.value) }
        videoManager.release()
        controller.load(p)
        store.lastOpenedId = p.id
        clock.seek(0L)
        _playhead.value = 0L
        _status.value = status
        renderer.requestRender()
        markDirty()
        checkMissingMedia(p)
        _projects.value = withContext(Dispatchers.IO) { store.list() }
    }

    fun newProject() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { store.list().size } + 1
            switchTo(Project(name = "Proyek " + n.toString().padStart(3, '0')), "Proyek baru dibuat.")
        }
    }

    fun openProject(id: String) {
        viewModelScope.launch {
            if (id == controller.project.value.id) return@launch
            val r = withContext(Dispatchers.IO) { store.load(id) }
            if (r == null) {
                _status.value = "Proyek tidak bisa dibuka (file hilang atau rusak)."
                return@launch
            }
            switchTo(r.project, if (r.recovered) "Proyek dipulihkan dari cadangan." else "Proyek \"${r.project.name}\" dibuka.")
        }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (id == controller.project.value.id) saveStamped(controller.project.value)
                store.duplicate(id)
            }
            _projects.value = withContext(Dispatchers.IO) { store.list() }
            _status.value = "Proyek diduplikasi."
        }
    }

    /** Hapus proyek (UI sudah meminta konfirmasi). Bila yang dihapus proyek aktif, proyek kosong baru dibuka. */
    fun deleteProject(id: String) {
        viewModelScope.launch {
            val isCurrent = id == controller.project.value.id
            withContext(Dispatchers.IO) { store.delete(id) }
            if (isCurrent) {
                controller.load(Project(name = "Proyek 001"))
                store.lastOpenedId = controller.project.value.id
                videoManager.release()
                clock.seek(0L)
                _playhead.value = 0L
                renderer.requestRender()
                _missing.value = emptyList()
            }
            _projects.value = withContext(Dispatchers.IO) { store.list() }
            _status.value = "Proyek dihapus."
        }
    }

    // ---------------------------------------------------------------- font

    /** Mengimpor font TTF/OTF: disalin ke penyimpanan aplikasi agar tetap ada walau file asal dihapus. */
    fun importFont(clipId: String, uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = queryName(uri)
                    val dir = java.io.File(ctx.filesDir, "fonts").apply { mkdirs() }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = java.io.File(dir, System.currentTimeMillis().toString() + "_" + safe)
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: return@withContext Pair<String?, String>(null, "File font tidak bisa dibaca.")
                    try {
                        android.graphics.Typeface.createFromFile(out)
                    } catch (e: Exception) {
                        out.delete()
                        return@withContext Pair<String?, String>(null, "File bukan font TTF/OTF yang valid.")
                    }
                    Pair<String?, String>(out.absolutePath, name.substringBeforeLast('.'))
                } catch (e: Exception) {
                    Pair<String?, String>(null, "Gagal mengimpor font: " + (e.message ?: ""))
                }
            }
            val path = result.first
            if (path == null) {
                _status.value = result.second
            } else {
                controller.updateClip(clipId) { it.copy(fontUri = path, fontName = result.second) }
                _status.value = "Font \"${result.second}\" dipasang."
            }
        }
    }

    fun setStatus(msg: String) {
        _status.value = msg
    }

    private fun markDirty() {
        lastDirtyAt = SystemClock.elapsedRealtime()
    }

    // ---------------------------------------------------------------- transport

    /** Dipanggil setiap frame UI (vsync) dari Compose. Murah bila tidak ada perubahan. */
    fun tick() {
        val p = controller.project.value
        var t = clock.positionMs()
        val dur = p.durationMs
        if (clock.playing() && t >= dur) {
            clock.pause()
            clock.seek(dur)
            t = dur
            _playing.value = false
            renderer.setContinuous(false)
            videoManager.pauseAll()
            markDirty()
        }
        if (_playhead.value != t) _playhead.value = t
        val isPlaying = clock.playing()
        if (isPlaying || SystemClock.elapsedRealtime() - lastDirtyAt < 700L) {
            videoManager.sync(p, t, isPlaying)
        }
    }

    fun togglePlay() {
        if (clock.playing()) pause() else play()
    }

    fun startExport(settings: ExportSettings) {
        if (_export.value.phase == ExportPhase.RUNNING) return
        pause()
        videoManager.release() // bebaskan decoder preview agar ekspor memakai decoder hardware
        exportCancel = false
        _export.value = ExportUi(ExportPhase.RUNNING, 0f, "Memulai…")
        val project = controller.project.value
        val app = getApplication<Application>()
        exportJob = viewModelScope.launch {
            try {
                val res = withContext(exportDispatcher) {
                    ExportEngine(app).run(
                        project, settings,
                        { p, m -> _export.value = ExportUi(ExportPhase.RUNNING, p, m) },
                        { exportCancel }
                    )
                }
                _export.value = ExportUi(ExportPhase.DONE, 1f, "Selesai", res)
            } catch (e: ExportCancelled) {
                _export.value = ExportUi(ExportPhase.CANCELLED, 0f, "Ekspor dibatalkan. File parsial dihapus.")
            } catch (e: Throwable) {
                _export.value = ExportUi(ExportPhase.FAILED, 0f, e.message ?: e.javaClass.simpleName)
            } finally {
                markDirty()
                renderer.requestRender()
            }
        }
    }

    fun cancelExport() {
        exportCancel = true
        if (_export.value.phase == ExportPhase.RUNNING) {
            _export.value = _export.value.copy(message = "Membatalkan…")
        }
    }

    fun dismissExport() {
        if (_export.value.phase != ExportPhase.RUNNING) _export.value = ExportUi()
    }

    fun play() {
        if (_export.value.phase == ExportPhase.RUNNING) {
            _status.value = "Ekspor sedang berjalan; pemutaran dinonaktifkan sementara."
            return
        }
        val dur = controller.project.value.durationMs
        if (dur <= 0L) {
            _status.value = "Belum ada klip. Tambahkan video, gambar, teks, atau audio."
            return
        }
        if (clock.positionMs() >= dur) clock.seek(0L)
        clock.play()
        _playing.value = true
        renderer.setContinuous(true)
        markDirty()
    }

    fun pause() {
        clock.pause()
        _playing.value = false
        renderer.setContinuous(false)
        videoManager.pauseAll()
        markDirty()
    }

    fun seekTo(ms: Long) {
        val dur = controller.project.value.durationMs
        val t = ms.coerceIn(0L, if (dur > 0L) dur else 0L)
        clock.seek(t)
        _playhead.value = t
        videoManager.invalidate()
        markDirty()
        renderer.requestRender()
    }

    // ---------------------------------------------------------------- impor media

    private fun keepPermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Sebagian provider tidak mendukung izin persisten; file tetap bisa dibaca selama sesi ini.
        }
    }

    private fun queryName(uri: Uri): String {
        val ctx = getApplication<Application>()
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Media"
    }

    private fun queryDurationMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(getApplication<Application>(), uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun importMedia(type: TrackType, uri: Uri) {
        keepPermission(uri)
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { queryName(uri) }
            val needsDuration = type == TrackType.VIDEO || type == TrackType.AUDIO
            val dur = if (needsDuration) withContext(Dispatchers.IO) { queryDurationMs(uri) } else 0L
            if (needsDuration && dur <= 0L) {
                _status.value = "File \"$name\" tidak dapat dibaca atau formatnya tidak didukung."
                return@launch
            }
            controller.addMedia(type, name, uri.toString(), dur, clock.positionMs())
            _status.value = "\"$name\" ditambahkan."
        }
    }

    fun importLut(clipId: String, uri: Uri) {
        keepPermission(uri)
        controller.updateClip(clipId) { it.copy(lutUri = uri.toString()) }
        _status.value = "LUT dipasang (format PNG 512x512, 8x8 kotak)."
        renderer.requestRender()
    }

    fun addText(text: String) {
        controller.addText(text, clock.positionMs())
    }

    fun addShape(kind: ShapeKind) {
        controller.addShape(kind, clock.positionMs())
    }

    fun splitSelected() {
        val id = controller.selectedClipId.value ?: run {
            _status.value = "Pilih klip dulu."
            return
        }
        val r = controller.splitAt(id, clock.positionMs())
        _status.value = if (r == null) "Letakkan playhead di dalam klip (jangan terlalu dekat tepi) untuk memotong." else "Klip dipotong."
    }

    // ---------------------------------------------------------------- motion tracking

    fun setTrackOverlay(on: Boolean) {
        _trackOverlay.value = on
    }

    fun setTrackRegion(r: TrackRegion) {
        _trackRegion.value = r.copy(
            nx = r.nx.coerceIn(0.02f, 0.98f),
            ny = r.ny.coerceIn(0.02f, 0.98f),
            size = r.size.coerceIn(0.05f, 0.35f)
        )
    }

    fun cancelTracking() {
        trackJob?.cancel()
        trackJob = null
        _tracking.value = TrackingUi(false, 0f, "Pelacakan dibatalkan.")
    }

    /**
     * Melacak objek pada video di bawah playhead mulai dari area [trackRegion], lalu mengisi keyframe
     * posisi X/Y pada klip [targetClipId] agar mengikuti objek (dengan mempertahankan offset awalnya).
     */
    fun startTracking(targetClipId: String) {
        if (_tracking.value.running) return
        val project = controller.project.value
        val target = project.findClip(targetClipId)
        if (target == null) {
            _tracking.value = TrackingUi(false, 0f, "Pilih klip teks / bentuk / gambar yang akan mengikuti objek.")
            return
        }
        val t0 = clock.positionMs()
        val video = TrackMapping.videoClipAt(project, t0)
        if (video == null) {
            _tracking.value = TrackingUi(false, 0f, "Tidak ada klip video di posisi playhead. Geser playhead ke atas video yang akan dilacak.")
            return
        }
        val aspect = renderer.videoAspect(video.id)
        if (aspect == null) {
            _tracking.value = TrackingUi(false, 0f, "Video belum siap. Putar sebentar lalu coba lagi.")
            return
        }
        pause()
        val region = _trackRegion.value
        val speedMap = video.speedMap
        val localStart = (t0 - video.startMs).coerceIn(0L, video.durationMs)
        val srcStart = video.sourceTimeAt(localStart)
        val srcEnd = video.sourceTimeAt(minOf(video.durationMs, localStart + 15000L))
        // Waktu sumber -> waktu timeline lewat invers integral kurva kecepatan.
        fun srcToTimeline(srcMs: Long): Long =
            video.startMs + Math.round(speedMap.timeForOffset((srcMs - video.sourceInMs).toDouble()))
        val half = (region.size * 192f / 2f).roundToInt().coerceIn(6, 28)
        val canvasAspect = project.canvasAspect

        _tracking.value = TrackingUi(true, 0f, "Melacak…")
        trackJob = viewModelScope.launch {
            val result = VideoTrackingJob.run(
                getApplication<Application>(), Uri.parse(video.uri), srcStart, srcEnd,
                region.nx, region.ny, half, 15
            ) { prog -> _tracking.value = TrackingUi(true, prog, "Melacak… ${(prog * 100).toInt()}%") }

            when (result) {
                is VideoTrackingJob.Result.Error -> {
                    _tracking.value = TrackingUi(false, 0f, result.message)
                }
                is VideoTrackingJob.Result.Ok -> {
                    val samples = TrackPostProcess.decimate(TrackPostProcess.smooth(result.samples))
                    // Posisi awal objek di kanvas & posisi awal target -> offset konstan.
                    val first = samples[0]
                    val tl0 = srcToTimeline(first.timeMs)
                    val (ox, oy) = TrackMapping.videoToCanvas(video, tl0 - video.startMs, aspect, canvasAspect, first.nx, first.ny)
                    val tgtLocal0 = (tl0 - target.startMs).coerceIn(0L, target.durationMs)
                    val offX = target.valueAt(Props.X, tgtLocal0) - ox
                    val offY = target.valueAt(Props.Y, tgtLocal0) - oy
                    val xs = ArrayList<Pair<Long, Float>>()
                    val ys = ArrayList<Pair<Long, Float>>()
                    for (s in samples) {
                        val tl = srcToTimeline(s.timeMs)
                        val local = tl - target.startMs
                        if (local < 0L || local > target.durationMs) continue
                        val (cx, cy) = TrackMapping.videoToCanvas(video, tl - video.startMs, aspect, canvasAspect, s.nx, s.ny)
                        xs.add(Pair(local, cx + offX))
                        ys.add(Pair(local, cy + offY))
                    }
                    if (xs.size < 2) {
                        _tracking.value = TrackingUi(false, 0f, "Klip target tidak tumpang-tindih dengan rentang pelacakan. Posisikan klip target di atas bagian video yang dilacak.")
                    } else {
                        controller.setKeyframeSeriesMulti(targetClipId, mapOf(Props.X to xs, Props.Y to ys))
                        val warn = if (result.lostFrames > 0) " (target sempat hilang ${result.lostFrames} frame; periksa hasilnya)" else ""
                        _tracking.value = TrackingUi(false, 1f, "Selesai: ${xs.size} keyframe X/Y dibuat$warn")
                    }
                }
            }
            trackJob = null
        }
    }

    // ---------------------------------------------------------------- siklus hidup

    override fun onCleared() {
        exportCancel = true
        exportExecutor.shutdown()
        trackJob?.cancel()
        videoManager.release()
        renderer.shutdown()
        super.onCleared()
    }
}
