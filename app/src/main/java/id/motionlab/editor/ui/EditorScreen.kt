package id.motionlab.editor.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.motionlab.editor.core.LayerGeometry
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.Props
import id.motionlab.editor.core.TrackMapping
import id.motionlab.editor.core.TrackType
import kotlinx.coroutines.delay
import java.util.Locale

private fun mimeFor(type: TrackType): String = when (type) {
    TrackType.VIDEO -> "video/*"
    TrackType.AUDIO -> "audio/*"
    else -> "image/*"
}

private fun timeText(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val m = t / 60000L
    val s = (t / 1000L) % 60L
    val d = (t % 1000L) / 100L
    return String.format(Locale.US, "%d:%02d.%d", m, s, d)
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    val controller = vm.controller
    val project by controller.project.collectAsState()
    val selectedId by controller.selectedClipId.collectAsState()
    val status by vm.status.collectAsState()
    val playing by vm.playing.collectAsState()
    val undoCount by controller.undoCount.collectAsState()
    val redoCount by controller.redoCount.collectAsState()

    var tab by remember { mutableStateOf<EditorTab?>(EditorTab.ADD) }
    var pxPerSec by remember { mutableFloatStateOf(120f) }
    var showText by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    val missing by vm.missingMedia.collectAsState()

    val scope = rememberCoroutineScope()
    val thumbs = remember { ThumbnailStore(context.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { thumbs.release() } }

    // ---- pemilih file ----
    var pendingType by remember { mutableStateOf(TrackType.VIDEO) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMedia(pendingType, uri)
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = selectedId
        if (uri != null && id != null) vm.importLut(id, uri)
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = selectedId
        if (uri != null && id != null) vm.importFont(id, uri)
    }
    val pickMedia: (TrackType) -> Unit = { type ->
        pendingType = type
        mediaPicker.launch(arrayOf(mimeFor(type)))
    }

    // ---- loop frame: sinkronisasi playhead & pemutar media ----
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { vm.tick() }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Palette.AppBg).statusBarsPadding().navigationBarsPadding()
    ) {
        // ================= Top bar =================
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).clickable { showRename = true }.padding(start = 8.dp)) {
                Text("MOTIONLAB", fontSize = 10.sp, color = Palette.Accent, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(project.name, fontSize = 14.sp, color = Palette.TextMain, maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { controller.undo() }, enabled = undoCount > 0) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo",
                    tint = if (undoCount > 0) Palette.TextMain else Palette.Panel3)
            }
            IconButton(onClick = { controller.redo() }, enabled = redoCount > 0) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo",
                    tint = if (redoCount > 0) Palette.TextMain else Palette.Panel3)
            }
            TextButton(onClick = { vm.refreshProjects(); showProjects = true }) { Text("Proyek", fontSize = 12.sp) }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Pengaturan proyek", tint = Palette.TextMain)
            }
            Button(onClick = { showExport = true }) { Text("Ekspor", fontSize = 13.sp) }
        }

        if (missing.isNotEmpty()) {
            Text(
                "⚠ ${missing.size} media tidak ditemukan: " + missing.take(3).joinToString(", ") +
                    (if (missing.size > 3) ", …" else "") + ". Klip tetap ada; impor ulang filenya untuk menggantinya.",
                color = Palette.Danger, fontSize = 11.sp, maxLines = 2,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1518)).padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // ================= Preview =================
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            PreviewSurface(vm, project, selectedId)
        }

        // ================= Transport =================
        TransportBar(
            vm = vm, project = project, playing = playing, selectedId = selectedId,
            onZoomIn = { pxPerSec = (pxPerSec * 1.4f).coerceAtMost(1500f) },
            onZoomOut = { pxPerSec = (pxPerSec / 1.4f).coerceAtLeast(8f) }
        )

        // ================= Timeline =================
        Box(modifier = Modifier.fillMaxWidth().height(184.dp).background(Palette.Panel)) {
            TimelineHost(
                vm = vm, project = project, selectedId = selectedId, pxPerSec = pxPerSec,
                thumbs = thumbs, onZoom = { f -> pxPerSec = (pxPerSec * f).coerceIn(8f, 1500f) }
            )
        }

        // ================= Panel inspector =================
        val activeTab = tab
        if (activeTab != null) {
            Box(modifier = Modifier.fillMaxWidth().height(206.dp).background(Palette.Panel)) {
                PanelHost(
                    tab = activeTab, vm = vm, project = project, selectedId = selectedId,
                    onPickMedia = pickMedia,
                    onPickLut = { lutPicker.launch(arrayOf("image/*")) },
                    onAddText = { showText = true },
                    onPickFont = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }
                )
            }
        }

        // ================= Tab =================
        Row(
            modifier = Modifier.fillMaxWidth().background(Palette.AppBg).horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (t in EditorTab.values()) {
                Chip(t.label, tab == t) { tab = if (tab == t) null else t }
            }
        }

        Text(
            status,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF11151E)).padding(horizontal = 12.dp, vertical = 6.dp),
            color = Palette.TextMuted, fontSize = 11.sp, maxLines = 2
        )
    }

    // ================= Dialog =================
    if (showText) {
        var draft by remember { mutableStateOf("Teks baru") }
        AlertDialog(
            onDismissRequest = { showText = false },
            title = { Text("Tambah teks") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Isi teks") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) vm.addText(draft.trim())
                    showText = false
                }) { Text("Tambah") }
            },
            dismissButton = { TextButton(onClick = { showText = false }) { Text("Batal") } }
        )
    }

    if (showRename) {
        var draft by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Nama proyek") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) controller.updateProject { it.copy(name = draft.trim()) }
                    showRename = false
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Batal") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Pengaturan proyek") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Rasio kanvas", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((w, h) in listOf(9 to 16, 16 to 9, 1 to 1, 4 to 5)) {
                            Chip("$w:$h", project.aspectW == w && project.aspectH == h) {
                                controller.updateProject { it.copy(aspectW = w, aspectH = h) }
                            }
                        }
                    }
                    Text("Resolusi ekspor (sisi pendek)", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, v) in listOf("720p" to 720, "1080p" to 1080, "4K" to 2160)) {
                            Chip(label, project.shortSide == v) { controller.updateProject { it.copy(shortSide = v) } }
                        }
                    }
                    Text("Frame rate", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in listOf(24, 30, 60)) {
                            Chip("$f FPS", project.fps == f) { controller.updateProject { it.copy(fps = f) } }
                        }
                    }
                    Text("Rasio memengaruhi preview dan hasil ekspor. Frame rate & resolusi dipakai oleh mesin ekspor.",
                        color = Palette.TextMuted, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Selesai") } }
        )
    }

    if (showProjects) ProjectsDialog(vm, project.id) { showProjects = false }
    val exportUi by vm.export.collectAsState()
    if (showExport || exportUi.phase != ExportPhase.IDLE) {
        ExportDialog(vm, project, exportUi) { showExport = false }
    }
}

// =============================================================== preview

@Composable
private fun PreviewSurface(vm: EditorViewModel, project: Project, selectedId: String?) {
    val controller = vm.controller
    val projectS by rememberUpdatedState(project)
    val selS by rememberUpdatedState(selectedId)
    val overlayOn by vm.trackOverlay.collectAsState()
    val overlayS by rememberUpdatedState(overlayOn)
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, glView) {
        val v = glView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) v?.onPause()
            if (event == Lifecycle.Event.ON_RESUME) v?.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val glError by produceState("") {
        while (true) {
            value = vm.renderer.errorMessage
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(project.canvasAspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(vm.renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    vm.renderer.attach(this)
                    glView = this
                }
            },
            onRelease = { v ->
                vm.renderer.detach(v)
                if (glView === v) glView = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gestur langsung di preview: seret = geser, cubit = skala, dua jari putar = rotasi (auto-keyframe bila beranimasi).
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    val pw = size.width.toFloat().coerceAtLeast(1f)
                    val ph = size.height.toFloat().coerceAtLeast(1f)
                    if (overlayS) {
                        val p = controller.project.value
                        val t = vm.clock.positionMs()
                        val vclip = TrackMapping.videoClipAt(p, t) ?: return@detectTransformGestures
                        val aspect = vm.renderer.videoAspect(vclip.id) ?: return@detectTransformGestures
                        val local = t - vclip.startMs
                        val r = vm.trackRegion.value
                        val (cx, cy) = TrackMapping.videoToCanvas(vclip, local, aspect, p.canvasAspect, r.nx, r.ny)
                        val (nx, ny) = TrackMapping.canvasToVideo(vclip, local, aspect, p.canvasAspect, cx + pan.x / pw, cy + pan.y / ph)
                        vm.setTrackRegion(r.copy(nx = nx, ny = ny))
                        return@detectTransformGestures
                    }
                    val id = selS ?: return@detectTransformGestures
                    val p = controller.project.value
                    val c = p.findClip(id) ?: return@detectTransformGestures
                    if (c.type == TrackType.AUDIO) return@detectTransformGestures
                    val local = (vm.clock.positionMs() - c.startMs).coerceIn(0L, c.durationMs)
                    val values = HashMap<String, Float>()
                    if (pan.x != 0f || pan.y != 0f) {
                        values[Props.X] = c.valueAt(Props.X, local) + pan.x / pw
                        values[Props.Y] = c.valueAt(Props.Y, local) + pan.y / ph
                    }
                    if (zoom != 1f) values[Props.SCALE] = c.valueAt(Props.SCALE, local) * zoom
                    if (rotation != 0f) values[Props.ROTATION] = c.valueAt(Props.ROTATION, local) + rotation
                    if (values.isNotEmpty()) controller.setProperties(id, values, local, "xform:$id")
                }
            }
        )

        if (overlayOn) TrackOverlay(vm, project)

        if (project.tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Impor media untuk memulai", color = Palette.TextMain, fontSize = 14.sp)
                Text("Tab Tambah → Video / Gambar / Audio / Teks", color = Palette.TextMuted, fontSize = 11.sp)
            }
        }
        if (glError.isNotEmpty()) {
            Text(
                glError, color = Palette.Danger, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(6.dp)
            )
        }
    }
}

/** Kotak area pelacakan di atas preview (koordinat frame video -> kanvas). */
@Composable
private fun TrackOverlay(vm: EditorViewModel, project: Project) {
    val playhead by vm.playhead.collectAsState()
    val region by vm.trackRegion.collectAsState()
    val vclip = TrackMapping.videoClipAt(project, playhead) ?: return
    val aspect = vm.renderer.videoAspect(vclip.id) ?: return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val (cx, cy) = TrackMapping.videoToCanvas(vclip, playhead - vclip.startMs, aspect, project.canvasAspect, region.nx, region.ny)
        val fit = LayerGeometry.fit(aspect, project.canvasAspect)
        val vw = fit.first * vclip.valueAt(Props.SCALE, playhead - vclip.startMs) *
            vclip.valueAt(Props.STRETCH, playhead - vclip.startMs) * size.width
        val half = (region.size * vw / 2f).coerceAtLeast(8f)
        val px = (0.5f + cx) * size.width
        val py = (0.5f + cy) * size.height
        drawRect(Color(0x33FFD54F), Offset(px - half, py - half), Size(half * 2, half * 2))
        drawRect(Color(0xFFFFD54F), Offset(px - half, py - half), Size(half * 2, half * 2), style = Stroke(3f))
        drawLine(Color(0xFFFFD54F), Offset(px - 10f, py), Offset(px + 10f, py), 2f)
        drawLine(Color(0xFFFFD54F), Offset(px, py - 10f), Offset(px, py + 10f), 2f)
    }
}

// =============================================================== transport, timeline, panel

@Composable
private fun TransportBar(
    vm: EditorViewModel, project: Project, playing: Boolean, selectedId: String?,
    onZoomIn: () -> Unit, onZoomOut: () -> Unit
) {
    val playhead by vm.playhead.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            timeText(playhead) + " / " + timeText(project.durationMs),
            color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(112.dp)
        )
        IconButton(onClick = { vm.seekTo(0L) }) { Icon(Icons.Filled.SkipPrevious, "Ke awal", tint = Palette.TextMain) }
        IconButton(onClick = { vm.togglePlay() }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Jeda" else "Putar", tint = Palette.Accent)
        }
        IconButton(onClick = { vm.splitSelected() }) { Icon(Icons.Filled.ContentCut, "Potong", tint = Palette.TextMain) }
        IconButton(onClick = { selectedId?.let { vm.controller.duplicateClip(it) } }, enabled = selectedId != null) {
            Icon(Icons.Filled.ContentCopy, "Duplikat", tint = if (selectedId != null) Palette.TextMain else Palette.Panel3)
        }
        IconButton(onClick = { selectedId?.let { vm.controller.deleteClip(it) } }, enabled = selectedId != null) {
            Icon(Icons.Filled.Delete, "Hapus", tint = if (selectedId != null) Palette.Danger else Palette.Panel3)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onZoomOut) { Icon(Icons.Filled.ZoomOut, "Perkecil timeline", tint = Palette.TextMain) }
        IconButton(onClick = onZoomIn) { Icon(Icons.Filled.ZoomIn, "Perbesar timeline", tint = Palette.TextMain) }
    }
}

@Composable
private fun TimelineHost(
    vm: EditorViewModel, project: Project, selectedId: String?, pxPerSec: Float,
    thumbs: ThumbnailStore, onZoom: (Float) -> Unit
) {
    val playhead by vm.playhead.collectAsState()
    TimelineView(
        project = project,
        selectedClipId = selectedId,
        playheadMs = playhead,
        pxPerSec = pxPerSec,
        thumbs = thumbs,
        controller = vm.controller,
        onSeek = { vm.seekTo(it) },
        onZoom = onZoom,
        onHeaderTap = { track ->
            // Ketuk header: sembunyikan layer visual / bisukan audio.
            if (track.type == TrackType.AUDIO) vm.controller.toggleTrackMuted(track.id)
            else vm.controller.toggleTrackHidden(track.id)
        }
    )
}

@Composable
private fun PanelHost(
    tab: EditorTab, vm: EditorViewModel, project: Project, selectedId: String?,
    onPickMedia: (TrackType) -> Unit, onPickLut: () -> Unit, onAddText: () -> Unit, onPickFont: () -> Unit
) {
    val playhead by vm.playhead.collectAsState()
    InspectorPanel(
        tab = tab, vm = vm, project = project, clip = project.findClip(selectedId), playhead = playhead,
        onPickMedia = onPickMedia, onPickLut = onPickLut, onAddText = onAddText, onPickFont = onPickFont
    )
}
