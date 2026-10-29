package id.motionlab.editor.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
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
import id.motionlab.editor.ui.components.AddObjectSheet
import id.motionlab.editor.ui.components.LayerActionsSheet
import id.motionlab.editor.ui.screens.ProjectSettingsDialog
import kotlinx.coroutines.delay
import java.util.Locale

private fun mimeFor(type: TrackType): String = when (type) {
    TrackType.VIDEO -> "video/*"
    TrackType.AUDIO -> "audio/*"
    else -> "image/*"
}

private fun timeText(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val totalSec = t / 1000L
    val m = totalSec / 60L
    val s = totalSec % 60L
    val f = ((t % 1000L) * 30L / 1000L).coerceIn(0L, 29L) // frame number at ~30fps
    return String.format(Locale.US, "%02d:%02d:%02d", m, s, f)
}

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val controller = vm.controller
    val project by controller.project.collectAsState()
    val selectedId by controller.selectedClipId.collectAsState()
    val status by vm.status.collectAsState()
    val playing by vm.playing.collectAsState()
    val undoCount by controller.undoCount.collectAsState()
    val redoCount by controller.redoCount.collectAsState()
    val playhead by vm.playhead.collectAsState()

    var tab by remember { mutableStateOf<EditorTab?>(EditorTab.TRANSFORM) }
    var pxPerSec by remember { mutableFloatStateOf(120f) }
    var showText by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showLayerActions by remember { mutableStateOf(false) }
    val missing by vm.missingMedia.collectAsState()

    val scope = rememberCoroutineScope()
    val thumbs = remember { ThumbnailStore(context.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { thumbs.release() } }

    // Intercept hardware / gesture back button
    BackHandler {
        onNavigateBack()
    }

    // Media picker launcher
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

    // Frame synchronization loop
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { vm.tick() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.AppBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ================= Top Bar =================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Palette.Panel)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali ke Beranda",
                    tint = Palette.TextMain
                )
            }

            // Project name & time display
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showRename = true }
                    .padding(horizontal = 6.dp)
            ) {
                Text(
                    text = project.name,
                    fontSize = 15.sp,
                    color = Palette.TextMain,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${timeText(playhead)} / ${timeText(project.durationMs)}",
                    fontSize = 11.sp,
                    color = Palette.TextMuted,
                    maxLines = 1
                )
            }

            // Aspect ratio chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Panel2)
                    .border(1.dp, Palette.Border, RoundedCornerShape(6.dp))
                    .clickable { showSettings = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${project.aspectW}:${project.aspectH}",
                    fontSize = 11.sp,
                    color = Palette.AccentCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(4.dp))

            // Project settings gear
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Pengaturan Proyek",
                    tint = Palette.TextMain
                )
            }

            // Export button (Alight Motion inspired green pill)
            Button(
                onClick = { showExport = true },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.AccentGreen,
                    contentColor = Color.Black
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = "Export",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        // Missing media banner
        if (missing.isNotEmpty()) {
            Text(
                text = "⚠ ${missing.size} media tidak ditemukan: " + missing.take(2).joinToString(", ") +
                    (if (missing.size > 2) ", …" else "") + ". Impor ulang file untuk menggantinya.",
                color = Palette.Danger,
                fontSize = 11.sp,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A1518))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // ================= Preview Canvas Area =================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.AppBg)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            PreviewSurface(vm, project, selectedId)
        }

        // ================= Transport Bar (All Material Icons) =================
        TransportBar(
            vm = vm,
            project = project,
            playing = playing,
            selectedId = selectedId,
            undoCount = undoCount,
            redoCount = redoCount,
            onOpenLayers = { showLayerActions = true },
            onZoomIn = { pxPerSec = (pxPerSec * 1.4f).coerceAtMost(1500f) },
            onZoomOut = { pxPerSec = (pxPerSec / 1.4f).coerceAtLeast(8f) }
        )

        // ================= Timeline Area with Floating Add Button =================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Palette.Panel)
        ) {
            TimelineHost(
                vm = vm,
                project = project,
                selectedId = selectedId,
                pxPerSec = pxPerSec,
                thumbs = thumbs,
                onZoom = { f -> pxPerSec = (pxPerSec * f).coerceIn(8f, 1500f) }
            )

            // Floating Action Button (+) for Add Object (Alight Motion inspired)
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = Palette.AccentGreen,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Tambah Objek",
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // ================= Property Tabs Bar =================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.AppBg)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (t in EditorTab.values()) {
                Chip(
                    label = t.label,
                    selected = tab == t,
                    onClick = { tab = if (tab == t) null else t }
                )
            }
        }

        // ================= Inspector Panel (RulerSlider Enabled) =================
        val activeTab = tab
        if (activeTab != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(Palette.Panel)
            ) {
                PanelHost(
                    tab = activeTab,
                    vm = vm,
                    project = project,
                    selectedId = selectedId,
                    onPickMedia = pickMedia,
                    onPickLut = { lutPicker.launch(arrayOf("image/*")) },
                    onAddText = { showText = true },
                    onPickFont = {
                        fontPicker.launch(
                            arrayOf(
                                "font/ttf", "font/otf", "font/sfnt",
                                "application/x-font-ttf", "application/x-font-otf",
                                "application/octet-stream", "*/*"
                            )
                        )
                    }
                )
            }
        }

        // Bottom status info bar
        Text(
            text = status,
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.AppBg)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = Palette.TextMuted,
            fontSize = 11.sp,
            maxLines = 1
        )
    }

    // ================= Sheets & Dialogs =================

    // Add Object Sheet
    if (showAddSheet) {
        AddObjectSheet(
            onDismiss = { showAddSheet = false },
            onPickMedia = { type ->
                showAddSheet = false
                pickMedia(type)
            },
            onAddText = {
                showAddSheet = false
                showText = true
            },
            onAddShape = { kind, color ->
                showAddSheet = false
                vm.controller.addShape(kind, color)
            },
            onAddSolid = { color ->
                showAddSheet = false
                vm.controller.addSolid(color)
            }
        )
    }

    // Layer Actions Menu Sheet
    if (showLayerActions) {
        LayerActionsSheet(
            project = project,
            selectedClipId = selectedId,
            controller = controller,
            playheadMs = playhead,
            onDismiss = { showLayerActions = false }
        )
    }

    // Project Settings Dialog
    if (showSettings) {
        ProjectSettingsDialog(
            project = project,
            onDismiss = { showSettings = false },
            onSave = { name, aspectW, aspectH, shortSide, fps ->
                vm.updateProjectSettings(name, aspectW, aspectH, shortSide, fps)
                showSettings = false
            }
        )
    }

    // Text Input Dialog
    if (showText) {
        var draft by remember { mutableStateOf("Teks Baru") }
        AlertDialog(
            onDismissRequest = { showText = false },
            containerColor = Palette.Panel,
            title = { Text("Tambah Objek Teks", color = Palette.TextMain) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Isi teks") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (draft.isNotBlank()) vm.addText(draft.trim())
                        showText = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.AccentGreen)
                ) {
                    Text("Tambah", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showText = false }) {
                    Text("Batal", color = Palette.TextMuted)
                }
            }
        )
    }

    // Rename Dialog
    if (showRename) {
        var draft by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = Palette.Panel,
            title = { Text("Ubah Nama Proyek", color = Palette.TextMain) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (draft.isNotBlank()) vm.renameProject(project.id, draft.trim())
                        showRename = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.AccentGreen)
                ) {
                    Text("Simpan", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text("Batal", color = Palette.TextMuted)
                }
            }
        )
    }

    // Export Dialog
    val exportUi by vm.export.collectAsState()
    if (showExport || exportUi.phase != ExportPhase.IDLE) {
        ExportDialog(vm, project, exportUi) { showExport = false }
    }
}

// =============================================================== Preview Surface

@Composable
private fun PreviewSurface(vm: EditorViewModel, project: Project, selectedId: String?) {
    val controller = vm.controller
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

    val canvasBg = if (project.bgColor != 0) Color(project.bgColor) else Color.Black

    Box(
        modifier = Modifier
            .aspectRatio(project.canvasAspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(8.dp))
            .background(canvasBg)
            .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
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

        // Direct Preview Touch Gestures
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Kanvas Kosong", color = Palette.TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Ketuk tombol (+) untuk menambahkan layer", color = Palette.TextMuted, fontSize = 12.sp)
            }
        }

        if (glError.isNotEmpty()) {
            Text(
                glError,
                color = Palette.Danger,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(6.dp)
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

// =============================================================== Transport, Timeline, Panel

@Composable
private fun TransportBar(
    vm: EditorViewModel,
    project: Project,
    playing: Boolean,
    selectedId: String?,
    undoCount: Int,
    redoCount: Int,
    onOpenLayers: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Palette.Panel2)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Undo
        IconButton(
            onClick = { vm.controller.undo() },
            enabled = undoCount > 0,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                tint = if (undoCount > 0) Palette.TextMain else Palette.Panel3
            )
        }

        // Redo
        IconButton(
            onClick = { vm.controller.redo() },
            enabled = redoCount > 0,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                tint = if (redoCount > 0) Palette.TextMain else Palette.Panel3
            )
        }

        // Jump to Start
        IconButton(
            onClick = { vm.seekTo(0L) },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Ke Awal",
                tint = Palette.TextMain
            )
        }

        // Play / Pause (Circular Green Accent)
        IconButton(
            onClick = { vm.togglePlay() },
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (playing) Palette.AccentGreen.copy(alpha = 0.2f) else Color.Transparent,
                    CircleShape
                )
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Jeda" else "Putar",
                tint = if (playing) Palette.AccentGreen else Palette.TextMain
            )
        }

        // Jump to End
        IconButton(
            onClick = { vm.seekTo(project.durationMs) },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Ke Akhir",
                tint = Palette.TextMain
            )
        }

        // Split at Playhead (Scissors / CallSplit)
        IconButton(
            onClick = { vm.splitSelected() },
            enabled = selectedId != null,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CallSplit,
                contentDescription = "Bagi Layer di Playhead",
                tint = if (selectedId != null) Palette.AccentCyan else Palette.Panel3
            )
        }

        // Duplicate
        IconButton(
            onClick = { selectedId?.let { vm.controller.duplicateClip(it) } },
            enabled = selectedId != null,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "Duplikat Layer",
                tint = if (selectedId != null) Palette.TextMain else Palette.Panel3
            )
        }

        // Layer Actions Menu
        IconButton(
            onClick = onOpenLayers,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = "Menu Aksi Layer",
                tint = Palette.AccentCyan
            )
        }

        // Delete Clip
        IconButton(
            onClick = { selectedId?.let { vm.controller.deleteClip(it) } },
            enabled = selectedId != null,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Hapus Layer",
                tint = if (selectedId != null) Palette.Danger else Palette.Panel3
            )
        }

        // Zoom Out
        IconButton(
            onClick = onZoomOut,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ZoomOut,
                contentDescription = "Perkecil Timeline",
                tint = Palette.TextMain
            )
        }

        // Zoom In
        IconButton(
            onClick = onZoomIn,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ZoomIn,
                contentDescription = "Perbesar Timeline",
                tint = Palette.TextMain
            )
        }
    }
}

@Composable
private fun TimelineHost(
    vm: EditorViewModel,
    project: Project,
    selectedId: String?,
    pxPerSec: Float,
    thumbs: ThumbnailStore,
    onZoom: (Float) -> Unit
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
            if (track.type == TrackType.AUDIO) vm.controller.toggleTrackMuted(track.id)
            else vm.controller.toggleTrackHidden(track.id)
        }
    )
}

@Composable
private fun PanelHost(
    tab: EditorTab,
    vm: EditorViewModel,
    project: Project,
    selectedId: String?,
    onPickMedia: (TrackType) -> Unit,
    onPickLut: () -> Unit,
    onAddText: () -> Unit,
    onPickFont: () -> Unit
) {
    val playhead by vm.playhead.collectAsState()
    InspectorPanel(
        tab = tab,
        vm = vm,
        project = project,
        clip = project.findClip(selectedId),
        playhead = playhead,
        onPickMedia = onPickMedia,
        onPickLut = onPickLut,
        onAddText = onAddText,
        onPickFont = onPickFont
    )
}
