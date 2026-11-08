package id.motionlab.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import id.motionlab.engine.timeline.PreviewMath
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import id.motionlab.core.common.MlException
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.TimelineViewport
import id.motionlab.engine.timeline.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Loads a project and shows the editor; saving goes through [ProjectRepository] (crash-safe file + Room index). */
@Composable
fun EditorScreen(services: AppServices, projectId: String, onClose: () -> Unit) {
    val repository = remember(services) { ProjectRepository(services.database.projectDao(), services.projectsDir) }
    var document by remember(projectId) { mutableStateOf<ProjectDocument?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) {
        try {
            document = withContext(Dispatchers.IO) { repository.open(projectId) }
        } catch (e: MlException) {
            error = e.message
        }
    }
    val doc = document
    when {
        error != null -> Column(Modifier.padding(16.dp)) {
            Text("Cannot open project: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor-error"))
            TextButton(onClick = onClose) { Text("Back") }
        }
        doc == null -> Text("Loading…", Modifier.padding(16.dp))
        else -> EditorContent(doc, onClose) { withContext(Dispatchers.IO) { repository.save(it) } }
    }
}

/** The editor itself; takes any [ProjectDocument] so it can be tested without a database. */
@Composable
fun EditorContent(document: ProjectDocument, onClose: () -> Unit, save: suspend (ProjectDocument) -> Unit) {
    val scope = rememberCoroutineScope()
    val session = remember(document.id) { EditorSession.fromDocument(document) }
    val controller = remember(session) { EditorController(session, document, scope, save) }
    MotionLabTheme { EditorLayout(controller, document.name, onClose) }
}

/** The editor UI for an existing controller (used by [EditorContent] and by the instrumented tests). */
@Composable
fun EditorLayout(controller: EditorController, projectName: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    DisposableEffect(controller) {
        onDispose {
            controller.dispose()
            // Last chance to persist: the screen is leaving composition. Small file, so a short blocking write is acceptable.
            runBlocking { controller.flush() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { controller.flush() } }
    // Playback poll: a plain delay loop (not withFrameNanos), so the UI stays idle-capable and tests can synchronise.
    LaunchedEffect(controller.playing) {
        while (controller.playing) {
            kotlinx.coroutines.delay(16)
            controller.pollPlayhead()
            val v = controller.viewport
            val r = v.revealing(controller.playheadUs, controller.timelineWidthPx)
            if (r.scrollUs != v.scrollUs) controller.viewport = r
        }
    }
    var panelOpen by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember(controller) { MediaLibraryController(context, controller.session, scope) }
    DisposableEffect(library) { onDispose { library.dispose() } }

    // Share of the screen height given to the square preview; the user can drag the handle under the toolbar to change it.
    var previewFraction by remember { mutableStateOf(0.38f) }
    Column(Modifier.fillMaxSize().background(MlColors.Black).testTag("editor-root")) {
        Header(controller, projectName, onClose)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // BoxWithConstraintsScope is layout-scope-marked: read constraints once into plain values.
            val availableHeight = maxHeight
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.3f).fillMaxHeight()) {
                        PreviewArea(controller, library, Modifier.weight(1f).fillMaxWidth())
                        PlaybackBar(controller)
                        TimelineToolbar(controller, library)
                        Box(Modifier.height(availableHeight * 0.40f)) { TimelinePanel(controller, media = library) }
                    }
                    PanelTabs(controller, library, true, {}, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PreviewArea(controller, library, Modifier.height(availableHeight * previewFraction).fillMaxWidth())
                    PlaybackBar(controller)
                    TimelineToolbar(controller, library)
                    ResizeHandle { dy -> previewFraction = (previewFraction + dy / availableHeight.value).coerceIn(0.22f, 0.55f) }
                    Box(Modifier.weight(1f)) { TimelinePanel(controller, media = library) }
                    val panelMod = if (panelOpen) Modifier.height(availableHeight * 0.32f) else Modifier
                    PanelTabs(controller, library, panelOpen, { panelOpen = !panelOpen }, panelMod.fillMaxWidth())
                }
            }
        }
    }
}

/** Thin drag handle that trades preview height for timeline height (vertical drag, in dp). */
@Composable
private fun ResizeHandle(onDragDp: (Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(18.dp).background(MlColors.Charcoal).testTag("timeline-resize")
            .semantics { contentDescription = "Drag to resize preview and timeline" }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy -> change.consume(); onDragDp(with(density) { dy.toDp().value }) }
            },
        contentAlignment = Alignment.Center,
    ) { MlIcon(MlIcons.Handle, null, tint = MlColors.Muted, size = 16.dp) }
}

@Composable
internal fun IconBtn(icon: ImageVector, description: String, tag: String, enabled: Boolean = true, tint: Color = MlColors.OnSurface, onClick: () -> Unit) {
    MlIconButton(icon, description, tag, enabled = enabled, tint = tint, touch = 44.dp, onClick = onClick)
}

@Composable
private fun Header(c: EditorController, name: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var exportInfo by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MlColors.Charcoal)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(MlIcons.Back, "Back to projects", "editor-close") { scope.launch { c.flush(); onClose() } }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.saveStatus, style = MaterialTheme.typography.labelSmall, color = MlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("save-status"))
            }
            IconBtn(MlIcons.Save, "Save now", "editor-save") { scope.launch { c.flush() } }
            IconBtn(MlIcons.Undo, "Undo", "tb-undo", enabled = c.canUndo) { c.undo() }
            IconBtn(MlIcons.Redo, "Redo", "tb-redo", enabled = c.canRedo) { c.redo() }
            Box {
                IconBtn(MlIcons.Settings, "Preview settings", "editor-settings") { settings = true }
                DropdownMenu(expanded = settings, onDismissRequest = { settings = false }) {
                    PreviewBackground.values().forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b.label) },
                            leadingIcon = { MlIcon(if (c.previewSettings.background == b) MlIcons.RadioOn else MlIcons.RadioOff, null) },
                            modifier = Modifier.testTag("bg-${b.name}"),
                            onClick = { c.previewSettings.background = b; c.settingsVersion++; settings = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Grid") },
                        leadingIcon = { MlIcon(if (c.previewSettings.showGrid) MlIcons.CheckOn else MlIcons.CheckOff, null) },
                        modifier = Modifier.testTag("grid-toggle"),
                        onClick = { c.previewSettings.showGrid = !c.previewSettings.showGrid; c.settingsVersion++; settings = false },
                    )
                }
            }
            IconBtn(MlIcons.Export, "Export", "editor-export", tint = MlColors.Accent) { exportInfo = true }
        }
        c.message?.let {
            Text(it, color = MlColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).testTag("editor-message"))
        }
    }
    if (exportInfo) {
        AlertDialog(
            onDismissRequest = { exportInfo = false },
            confirmButton = { TextButton(onClick = { exportInfo = false }) { Text("OK") } },
            title = { Text("Export") },
            text = { Text("Video export is not wired into this editor screen yet. The export pipeline is tested separately; nothing is exported from here.") },
        )
    }
}

/**
 * The preview viewport is a SQUARE (1:1), as large as fits and centred. The project canvas keeps its own aspect ratio and is
 * letterboxed inside it by the renderer (the GL scene and the touch overlay use the same [PreviewMath.letterbox]), so a 16:9
 * or 9:16 project is neither cropped nor stretched, and handles stay on the content. The margin around the canvas also
 * lets a handle that was dragged past the canvas edge stay reachable.
 */
@Composable
private fun PreviewArea(c: EditorController, library: MediaLibraryController, modifier: Modifier) {
    BoxWithConstraints(modifier.background(MlColors.Black), contentAlignment = Alignment.Center) {
        val side = PreviewMath.squareSide(maxWidth.value.toDouble(), maxHeight.value.toDouble()).dp
        Box(Modifier.size(side).testTag("preview-square")) {
            AndroidView(
                factory = { EditorPreviewView(it, c.session, c.previewSettings, c.contentSizes, library.preview, library.fonts) },
                modifier = Modifier.fillMaxSize().testTag("preview"),
            )
            PreviewOverlay(c, Modifier.fillMaxSize())
        }
        Row(Modifier.align(Alignment.TopStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Only tools that really work are listed (Mask / Effects are not implemented, so they are not shown).
            EditorTool.values().forEach { t ->
                FilterChip(selected = c.tool == t, onClick = { c.tool = t }, label = { Text(t.label) }, modifier = Modifier.testTag("tool-${t.name}"))
            }
        }
    }
}

@Composable
private fun PlaybackBar(c: EditorController) {
    val s = c.session
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    val frameUs = (1_000_000.0 / s.fps).toLong()
    Row(Modifier.fillMaxWidth().background(MlColors.Panel), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconBtn(MlIcons.SkipPrevious, "Go to start", "tb-start") { c.seek(0) }
        IconBtn(MlIcons.KeyframePrevious, "Previous keyframe", "tb-key-prev") { owner?.let { s.previousKeyframeTimeUs(it)?.let(c::seek) } }
        IconBtn(MlIcons.FramePrevious, "Previous frame", "tb-frame-prev") { c.seek((c.playheadUs - frameUs).coerceAtLeast(0)) }
        IconBtn(if (c.playing) MlIcons.Pause else MlIcons.Play, if (c.playing) "Pause" else "Play", "tb-play", tint = MlColors.Accent) { c.togglePlay() }
        IconBtn(MlIcons.FrameNext, "Next frame", "tb-frame-next") { c.seek(c.playheadUs + frameUs) }
        IconBtn(MlIcons.KeyframeNext, "Next keyframe", "tb-key-next") { owner?.let { s.nextKeyframeTimeUs(it)?.let(c::seek) } }
        IconBtn(MlIcons.SkipNext, "Go to end", "tb-end") { c.seek(s.timeline.durationUs) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineToolbar(c: EditorController, library: MediaLibraryController) {
    val s = c.session
    var advanced by remember { mutableStateOf(false) }
    var clipMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    Row(Modifier.fillMaxWidth().background(MlColors.Charcoal).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // The main action: opens the Media panel (Image / Video / Audio -> pick -> Add to Timeline).
        Button(
            onClick = { library.requestOpen() },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp).testTag("tb-add-media"),
        ) {
            MlIcon(MlIcons.AddMedia, null, size = 18.dp, tint = Color.Black)
            Text(" Media", maxLines = 1)
        }
        Box {
            MlIconButton(MlIcons.Layers, "Add text or shape layer", "tb-add-clip", touch = 40.dp) { clipMenu = true }
            DropdownMenu(expanded = clipMenu, onDismissRequest = { clipMenu = false }) {
                DropdownMenuItem(text = { Text("Text") }, modifier = Modifier.testTag("add-clip-text"), onClick = {
                    clipMenu = false; c.run { addGenerated(c, TrackKind.TEXT, mapOf("text" to "Text", "color" to "#FFFFFF")) }
                })
                DropdownMenuItem(text = { Text("Rectangle") }, modifier = Modifier.testTag("add-clip-rect"), onClick = {
                    clipMenu = false; c.run { addGenerated(c, TrackKind.SHAPE, mapOf("shape" to "rect", "color" to "#4F8CFF", "w" to "480", "h" to "270")) }
                })
                DropdownMenuItem(text = { Text("Ellipse") }, modifier = Modifier.testTag("add-clip-ellipse"), onClick = {
                    clipMenu = false; c.run { addGenerated(c, TrackKind.SHAPE, mapOf("shape" to "ellipse", "color" to "#FF7043", "w" to "360", "h" to "360")) }
                })
                DropdownMenuItem(text = { Text("Object tile") }, modifier = Modifier.testTag("add-clip-object"), onClick = {
                    clipMenu = false; c.run { addGenerated(c, TrackKind.OBJECT, emptyMap()) }
                })
            }
        }
        MlIconButton(MlIcons.Split, "Split at playhead", "tb-split", touch = 40.dp) { c.run { s.splitAtPlayhead() } }
        MlIconButton(MlIcons.Delete, "Delete selection", "tb-delete", touch = 40.dp) { c.run { s.deleteSelection() } }
        FilterChip(selected = c.snapEnabled, onClick = { c.snapEnabled = !c.snapEnabled }, label = { Text("Snap") }, modifier = Modifier.height(32.dp).testTag("tb-snap"))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconBtn(MlIcons.More, "More timeline actions", "tb-more") { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                @Composable
                fun item(label: String, tag: String, action: () -> Unit) {
                    DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag(tag), onClick = { more = false; action() })
                }
                item("Duplicate", "tb-duplicate") { c.run { s.duplicateSelection() } }
                item("Copy", "tb-copy") { c.run { s.copySelection() } }
                item("Paste", "tb-paste") { c.run { s.paste(keyframeOwner = owner) } }
                item(if (c.multiSelect) "Multi-select: on" else "Multi-select: off", "tb-multi") { c.multiSelect = !c.multiSelect }
                item("Select all", "tb-select-all") {
                    s.select(Selection(clipIds = s.timeline.tracks.flatMap { t -> t.clips.map { it.id } }.toSet()))
                }
                // Advanced only: importing media creates the right track by itself, so empty tracks are not part of the primary UI.
                item(if (advanced) "Advanced: hide empty tracks" else "Advanced: add empty track…", "tb-advanced") { advanced = !advanced; more = true }
                if (advanced) TrackKind.values().filter { it != TrackKind.OVERLAY }.forEach { k ->
                    DropdownMenuItem(text = { Text("Empty ${k.label} track") }, modifier = Modifier.testTag("add-track-${k.name}"), onClick = {
                        more = false; advanced = false
                        c.run { s.addTrack(k) }
                    })
                }
                item("Zoom out", "tb-zoom-out") { c.viewport = c.viewport.zoomed(1 / 1.5, c.timelineWidthPx / 2f) }
                item("Zoom in", "tb-zoom-in") { c.viewport = c.viewport.zoomed(1.5, c.timelineWidthPx / 2f) }
                item("Fit timeline", "tb-zoom-fit") {
                    val d = s.timeline.durationUs.coerceAtLeast(1_000_000)
                    val px = (c.timelineWidthPx.coerceAtLeast(100f) - 24f) * 1_000_000.0 / d
                    c.viewport = TimelineViewport(px.coerceIn(TimelineViewport.MIN_PX_PER_SECOND, TimelineViewport.MAX_PX_PER_SECOND), 0)
                }
            }
        }
    }
}

/** Adds a generated clip (text / shape / object) at the playhead on the top-most unlocked track of that kind, creating one if needed. */
private fun addGenerated(c: EditorController, kind: TrackKind, payload: Map<String, String>) {
    val s = c.session
    val track = s.timeline.tracks.lastOrNull { it.kind == kind && !it.locked }?.id ?: s.addTrack(kind)
    val id = s.addClip(track, c.playheadUs, 3_000_000, payload = payload)
    s.select(Selection(clipIds = setOf(id)))
}

@Composable
private fun PanelTabs(c: EditorController, library: MediaLibraryController, open: Boolean, toggle: () -> Unit, modifier: Modifier) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Attributes", "Anchor & Align", "Graph", "Path", "Layers", "Media")
    // "Add media" (toolbar) asks for the Media tab; open the panel if it was collapsed.
    LaunchedEffect(library.openRequest) {
        if (library.openRequest > 0) { tab = titles.lastIndex; if (!open) toggle() }
    }
    Column(modifier.background(MlColors.Panel)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                titles.forEachIndexed { i, t ->
                    Text(
                        t,
                        color = if (tab == i) MlColors.Accent else MlColors.Muted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { tab = i; if (!open) toggle() }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("tab-$t"),
                    )
                }
            }
            IconBtn(if (open) MlIcons.ExpandMore else MlIcons.ExpandLess, if (open) "Collapse panel" else "Expand panel", "panel-toggle") { toggle() }
        }
        if (open) {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> PropertyPanel(c, library = library)
                    1 -> AnchorAlignPanel(c)
                    2 -> GraphEditor(c)
                    3 -> PathEditor(c)
                    4 -> LayersPanel(c)
                    else -> MediaLibraryPanel(library, c)
                }
            }
        }
    }
}
