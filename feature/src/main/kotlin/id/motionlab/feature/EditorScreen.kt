package id.motionlab.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
    EditorLayout(controller, document.name, onClose)
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
    LaunchedEffect(controller.playing) {
        while (controller.playing) {
            androidx.compose.runtime.withFrameNanos { }
            controller.pollPlayhead()
            val v = controller.viewport
            val r = v.revealing(controller.playheadUs, controller.timelineWidthPx)
            if (r.scrollUs != v.scrollUs) controller.viewport = r
        }
    }

    Column(Modifier.fillMaxSize().testTag("editor-root")) {
        TopBar(controller, projectName, onClose)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.3f).fillMaxHeight()) {
                        Preview(controller, Modifier.weight(1f).fillMaxWidth())
                        Toolbar(controller)
                        Box(Modifier.height(maxHeight * 0.5f)) { TimelinePanel(controller) }
                    }
                    PanelTabs(controller, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Preview(controller, Modifier.height(maxHeight * 0.27f).fillMaxWidth())
                    Toolbar(controller)
                    Box(Modifier.weight(1f)) { TimelinePanel(controller) }
                    PanelTabs(controller, Modifier.height(maxHeight * 0.36f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TopBar(c: EditorController, name: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { scope.launch { c.flush(); onClose() } }, modifier = Modifier.testTag("editor-close")) { Text("← Projects") }
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(c.saveStatus, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("save-status"))
        TextButton(onClick = { scope.launch { c.flush() } }, modifier = Modifier.testTag("editor-save")) { Text("Save") }
    }
    c.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).testTag("editor-message"))
    }
}

@Composable
private fun Preview(c: EditorController, modifier: Modifier) {
    Box(modifier.background(Color0), contentAlignment = Alignment.Center) {
        val aspect = c.session.canvasWidth.toFloat() / c.session.canvasHeight
        AndroidView(
            factory = { EditorPreviewView(it, c.session) },
            modifier = Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true).testTag("preview"),
        )
    }
}

private val Color0 = androidx.compose.ui.graphics.Color(0xFF0B0B0F)

@Composable
private fun Toolbar(c: EditorController) {
    val s = c.session
    @Suppress("UNUSED_VARIABLE") val version = c.state
    var trackMenu by remember { mutableStateOf(false) }
    var clipMenu by remember { mutableStateOf(false) }
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCell("⏮", "Go to start", "tb-start") { c.seek(0) }
        IconCell(if (c.playing) "⏸" else "▶", if (c.playing) "Pause" else "Play", "tb-play") { c.togglePlay() }
        IconCell("⏭", "Go to end", "tb-end") { c.seek(s.timeline.durationUs) }
        IconCell("◁", "Previous frame", "tb-frame-prev") { c.seek((c.playheadUs - (1_000_000.0 / s.fps).toLong()).coerceAtLeast(0)) }
        IconCell("▷", "Next frame", "tb-frame-next") { c.seek(c.playheadUs + (1_000_000.0 / s.fps).toLong()) }
        IconCell("◆◀", "Previous keyframe", "tb-key-prev") { owner?.let { s.previousKeyframeTimeUs(it)?.let(c::seek) } }
        IconCell("▶◆", "Next keyframe", "tb-key-next") { owner?.let { s.nextKeyframeTimeUs(it)?.let(c::seek) } }
        IconCell("↶", "Undo", "tb-undo") { if (s.canUndo) { s.undo(); c.message = null } }
        IconCell("↷", "Redo", "tb-redo") { if (s.canRedo) { s.redo(); c.message = null } }
        Box {
            OutlinedButton(onClick = { trackMenu = true }, modifier = Modifier.testTag("tb-add-track")) { Text("+ Track") }
            DropdownMenu(expanded = trackMenu, onDismissRequest = { trackMenu = false }) {
                TrackKind.values().filter { it != TrackKind.OVERLAY }.forEach { k ->
                    DropdownMenuItem(text = { Text(k.label) }, modifier = Modifier.testTag("add-track-${k.name}"), onClick = {
                        trackMenu = false
                        c.run { s.addTrack(k) }
                    })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { clipMenu = true }, modifier = Modifier.testTag("tb-add-clip")) { Text("+ Clip") }
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
        OutlinedButton(onClick = { c.run { s.splitAtPlayhead() } }, modifier = Modifier.testTag("tb-split")) { Text("Split") }
        OutlinedButton(onClick = { c.run { s.duplicateSelection() } }, modifier = Modifier.testTag("tb-duplicate")) { Text("Duplicate") }
        OutlinedButton(onClick = { c.run { s.deleteSelection() } }, modifier = Modifier.testTag("tb-delete")) { Text("Delete") }
        OutlinedButton(onClick = { c.run { s.copySelection() } }, modifier = Modifier.testTag("tb-copy")) { Text("Copy") }
        OutlinedButton(onClick = { c.run { s.paste(keyframeOwner = owner) } }, modifier = Modifier.testTag("tb-paste")) { Text("Paste") }
        FilterChip(selected = c.snapEnabled, onClick = { c.snapEnabled = !c.snapEnabled }, label = { Text("Snap") }, modifier = Modifier.testTag("tb-snap"))
        FilterChip(selected = c.multiSelect, onClick = { c.multiSelect = !c.multiSelect }, label = { Text("Multi") }, modifier = Modifier.testTag("tb-multi"))
        OutlinedButton(onClick = {
            val all = s.timeline.tracks.flatMap { t -> t.clips.map { it.id } }.toSet()
            s.select(Selection(clipIds = all))
        }, modifier = Modifier.testTag("tb-select-all")) { Text("Select all") }
        IconCell("−", "Zoom out", "tb-zoom-out") { c.viewport = c.viewport.zoomed(1 / 1.5, c.timelineWidthPx / 2f) }
        IconCell("+", "Zoom in", "tb-zoom-in") { c.viewport = c.viewport.zoomed(1.5, c.timelineWidthPx / 2f) }
        OutlinedButton(onClick = {
            val d = s.timeline.durationUs.coerceAtLeast(1_000_000)
            val px = (c.timelineWidthPx.coerceAtLeast(100f) - 24f) * 1_000_000.0 / d
            c.viewport = TimelineViewport(px.coerceIn(TimelineViewport.MIN_PX_PER_SECOND, TimelineViewport.MAX_PX_PER_SECOND), 0)
        }, modifier = Modifier.testTag("tb-zoom-fit")) { Text("Fit") }
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
private fun PanelTabs(c: EditorController, modifier: Modifier) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Properties", "Graph", "Path", "Layers")
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("tab-$t")) }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> PropertyPanel(c)
                1 -> GraphEditor(c)
                2 -> PathEditor(c)
                else -> LayersPanel(c)
            }
        }
    }
}
