package id.motionlab.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.ClipZone
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.LinkTrackToLayer
import id.motionlab.engine.timeline.MIN_CLIP_DURATION_US
import id.motionlab.engine.timeline.MediaClip
import id.motionlab.engine.timeline.MoveClipToTrack
import id.motionlab.engine.timeline.MoveClips
import id.motionlab.engine.timeline.MoveKeyframes
import id.motionlab.engine.timeline.MoveSolver
import id.motionlab.engine.timeline.MoveTrack
import id.motionlab.engine.timeline.RemoveTrack
import id.motionlab.engine.timeline.RowGeometry
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetTrackFlags
import id.motionlab.engine.timeline.SnapEngine
import id.motionlab.engine.timeline.Timecode
import id.motionlab.engine.timeline.Timeline
import id.motionlab.engine.timeline.TimelineHit
import id.motionlab.engine.timeline.TimelineScroll
import id.motionlab.engine.timeline.TimelineLayout
import id.motionlab.engine.timeline.TimelineViewport
import id.motionlab.engine.timeline.TrackKind
import id.motionlab.engine.timeline.TrimClipEnd
import id.motionlab.engine.timeline.TrimClipStart
import kotlin.math.abs

// Compact mobile layout (Phase 3.1): was 80 / 56 / 32 / 120 dp.
private val ROW_EXPANDED = 60.dp
private val ROW_COLLAPSED = 48.dp
private val RULER_HEIGHT = 26.dp
private val HANDLE_WIDTH = 18.dp
private val KEYFRAME_TOUCH_RADIUS = 16.dp
private val SNAP_THRESHOLD = 10.dp
private val HEADER_WIDTH = 108.dp
private val HEADER_BUTTON = 34.dp

private fun kindColor(kind: TrackKind): Color = when (kind) {
    TrackKind.VIDEO -> Color(0xFF2E6F9E)
    TrackKind.AUDIO -> Color(0xFF2E8B63)
    TrackKind.IMAGE -> Color(0xFF7A4FA0)
    TrackKind.TEXT -> Color(0xFFB5762A)
    TrackKind.OBJECT -> Color(0xFF9E4A57)
    TrackKind.SHAPE -> Color(0xFF3F6FD0)
    TrackKind.OVERLAY -> Color(0xFF666E7A)
}

/**
 * Keyframe diamonds per clip in LOCAL time, cached by the identity of the clip's animation data. Moving or trimming a
 * clip keeps the same properties object, so dragging does not rebuild any marks; only a keyframe edit does.
 */
private class MarkCache {
    private class Entry(val source: Any, val marks: List<TimelineLayout.LocalMark>)

    private val byOwner = HashMap<String, Entry>()

    fun clip(clip: MediaClip): List<TimelineLayout.LocalMark> {
        val e = byOwner[clip.id]
        if (e != null && e.source === clip.properties) return e.marks
        val marks = TimelineLayout.localMarks(AnimationOwner.ClipOwner(clip.id), clip.anim)
        if (byOwner.size > 4096) byOwner.clear()
        byOwner[clip.id] = Entry(clip.properties, marks)
        return marks
    }

    fun layer(tl: Timeline, layerId: String): List<TimelineLayout.LocalMark> {
        val layer = tl.findLayer(layerId) ?: return emptyList()
        val key = "layer:$layerId"
        val e = byOwner[key]
        if (e != null && e.source === layer.properties) return e.marks
        val marks = TimelineLayout.localMarks(AnimationOwner.LayerOwner(layerId), layer.anim)
        byOwner[key] = Entry(layer.properties, marks)
        return marks
    }
}

/**
 * Track headers + clip canvas + ruler.
 *
 * Performance design: this composable reads only [EditorController.headers] (changes on structural edits), so dragging a
 * clip does not recompose it. The canvas reads timeline / selection / viewport / playhead inside its DRAW lambda, so those
 * changes only redraw. Only clips that intersect the viewport are drawn. Gestures never write history or the database:
 * they preview through [EditorController.beginGesture] and commit once.
 *
 * Gesture policy (one pointer handler, so gestures cannot fight): two fingers = pinch zoom + pan (always wins);
 * one finger on a clip / trim handle / diamond = edit; on empty space horizontal = scroll the time axis and vertical is NOT
 * consumed, so the surrounding vertical scroll moves the track list.
 */
@Composable
fun TimelinePanel(c: EditorController, modifier: Modifier = Modifier, media: MediaLibraryController? = null) {
    val density = LocalDensity.current
    val expandedPx = with(density) { ROW_EXPANDED.toPx() }
    val collapsedPx = with(density) { ROW_COLLAPSED.toPx() }
    val headers = c.headers
    val rows = remember(headers, expandedPx, collapsedPx) {
        TimelineLayout.rowsFromFlags(headers.map { it.id to it.collapsed }, expandedPx, collapsedPx)
    }
    val scroll = rememberScrollState()
    val contentHeight = with(density) { TimelineLayout.totalHeight(rows).toDp() }.coerceAtLeast(ROW_COLLAPSED)
    val byId = remember(headers) { headers.associateBy { it.id } }
    var viewportHeightPx by remember { mutableStateOf(0) }
    // After an insertion, scroll vertically (least amount) so the track of the new clip is fully visible.
    LaunchedEffect(c.revealCounter) {
        val id = c.revealTrackId ?: return@LaunchedEffect
        val row = rows.firstOrNull { it.trackId == id } ?: return@LaunchedEffect
        val target = TimelineScroll.revealRow(row.top, row.bottom, scroll.value.toFloat(), viewportHeightPx.toFloat())
        if (target != scroll.value.toFloat()) scroll.animateScrollTo(target.toInt())
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
            Box(Modifier.width(HEADER_WIDTH).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                TimecodeLabel(c)
            }
            Ruler(c, Modifier.weight(1f).fillMaxSize())
        }
        Row(Modifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height }) {
            Column(Modifier.width(HEADER_WIDTH).fillMaxSize().verticalScroll(scroll)) {
                if (rows.isEmpty()) {
                    Text("No tracks yet. Tap Add media, or use the + menu.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                rows.forEach { row ->
                    val model = byId[row.trackId] ?: return@forEach
                    TrackHeader(c, model, with(density) { row.height.toDp() })
                }
            }
            Box(Modifier.weight(1f).fillMaxSize().verticalScroll(scroll)) {
                ClipCanvas(c, rows, contentHeight, Modifier.fillMaxWidth(), media)
            }
        }
    }
}

@Composable
private fun TimecodeLabel(c: EditorController) {
    Text(Timecode.format(c.playheadUs, c.session.fps), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("timecode"))
}

@Composable
private fun TrackHeader(c: EditorController, track: TrackHeaderModel, height: Dp) {
    var menu by remember { mutableStateOf(false) }
    var layerMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 6.dp)
            .testTag("track-header-${track.id}"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            track.name + (track.layerName?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(14.dp),
        )
        Row {
            HeaderButton(if (track.locked) MlIcons.Lock else MlIcons.LockOpen, if (track.locked) "Unlock ${track.name}" else "Lock ${track.name}", "lock-${track.id}") {
                c.run { c.session.execute(SetTrackFlags(track.id, locked = !track.locked)) }
            }
            HeaderButton(if (track.visible) MlIcons.Visible else MlIcons.Hidden, if (track.visible) "Hide ${track.name}" else "Show ${track.name}", "visible-${track.id}") {
                c.run { c.session.execute(SetTrackFlags(track.id, visible = !track.visible)) }
            }
            Box {
                HeaderButton(MlIcons.More, "Track menu ${track.name}", "menu-${track.id}") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (track.muted) "Unmute" else "Mute") },
                        modifier = Modifier.testTag("mute-${track.id}"),
                        onClick = { menu = false; c.run { c.session.execute(SetTrackFlags(track.id, muted = !track.muted)) } },
                    )
                    DropdownMenuItem(
                        text = { Text(if (track.collapsed) "Expand" else "Collapse") },
                        modifier = Modifier.testTag("collapse-${track.id}"),
                        onClick = { menu = false; c.run { c.session.execute(SetTrackFlags(track.id, collapsed = !track.collapsed)) } },
                    )
                    DropdownMenuItem(text = { Text("Move up") }, onClick = {
                        menu = false
                        val tl = c.session.timeline
                        c.run { c.session.execute(MoveTrack(track.id, (tl.trackIndex(track.id) + 1).coerceAtMost(tl.tracks.size - 1))) }
                    })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = {
                        menu = false
                        val tl = c.session.timeline
                        c.run { c.session.execute(MoveTrack(track.id, (tl.trackIndex(track.id) - 1).coerceAtLeast(0))) }
                    })
                    DropdownMenuItem(
                        text = { Text("Duplicate layer") },
                        modifier = Modifier.testTag("duplicate-${track.id}"),
                        onClick = { menu = false; c.run { c.session.duplicateTrack(track.id) } },
                    )
                    DropdownMenuItem(text = { Text("Parent layer…") }, onClick = { menu = false; layerMenu = true })
                    DropdownMenuItem(
                        text = { Text("Remove track") },
                        modifier = Modifier.testTag("remove-${track.id}"),
                        onClick = { menu = false; c.run { c.session.execute(RemoveTrack(track.id)) } },
                    )
                }
                DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                    DropdownMenuItem(text = { Text("(none)") }, onClick = {
                        layerMenu = false
                        c.run { c.session.execute(LinkTrackToLayer(track.id, null)) }
                    })
                    c.layers.forEach { l ->
                        DropdownMenuItem(text = { Text(l.name) }, onClick = {
                            layerMenu = false
                            c.run { c.session.execute(LinkTrackToLayer(track.id, l.id)) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tag: String, onClick: () -> Unit) {
    MlIconButton(icon, description, tag, touch = HEADER_BUTTON, iconSize = 18.dp, onClick = onClick)
}

// ------------------------------------------------------------------------------------------------ ruler

@Composable
private fun Ruler(c: EditorController, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val paint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 10.dp.toPx() } }
    }
    val minLabelPx = with(density) { 64.dp.toPx() }
    Canvas(
        modifier
            .testTag("ruler")
            .onSizeChanged { c.timelineWidthPx = it.width.toFloat() }
            .pointerInput(c) {
                // Tap or drag on the ruler scrubs the playhead (frame-snapped). Seeking only moves the clock: no edit, no I/O.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    c.seek(Timecode.snapToFrame(c.viewport.pxToTime(down.position.x), c.session.fps))
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        c.seek(Timecode.snapToFrame(c.viewport.pxToTime(ch.position.x), c.session.fps))
                        ch.consume()
                    }
                }
            },
    ) {
        val vp = c.viewport
        drawRect(bg)
        val step = vp.rulerStepUs(minLabelPx)
        val from = (vp.scrollUs / step) * step
        val to = vp.pxToTime(size.width)
        paint.color = fg.toArgb()
        var t = from
        while (t <= to) {
            val x = vp.timeToPx(t)
            drawLine(fg, Offset(x, size.height * 0.45f), Offset(x, size.height), 1.5f)
            drawIntoCanvas { cv -> cv.nativeCanvas.drawText(Timecode.format(t, c.session.fps), x + 3f, size.height * 0.4f, paint) }
            for (i in 1..3) {
                val mx = vp.timeToPx(t + step * i / 4)
                drawLine(fg.copy(alpha = 0.5f), Offset(mx, size.height * 0.75f), Offset(mx, size.height), 1f)
            }
            t += step
        }
        drawPlayhead(c, size.height)
    }
}

private fun DrawScope.drawPlayhead(c: EditorController, height: Float) {
    val x = c.viewport.timeToPx(c.playheadUs)
    if (x < -2f || x > size.width + 2f) return
    drawLine(MlColors.Playhead, Offset(x, 0f), Offset(x, height), 2f)
    val p = Path().apply { moveTo(x - 7f, 0f); lineTo(x + 7f, 0f); lineTo(x, 12f); close() }
    drawPath(p, MlColors.Playhead)
}

// ------------------------------------------------------------------------------------------------ clip canvas

private enum class Mode { PENDING, MOVE_CLIPS, TRIM_START, TRIM_END, MOVE_KEYFRAMES, SCROLL_X, PASS }

@Composable
private fun ClipCanvas(c: EditorController, rows: List<RowGeometry>, contentHeight: Dp, modifier: Modifier, media: MediaLibraryController?) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val labelPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 11.dp.toPx() }
            color = android.graphics.Color.WHITE
        }
    }
    val marks = remember { MarkCache() }
    var snapGuideUs by remember { mutableStateOf<Long?>(null) }

    // Video thumbnails and audio waveforms: loaded in the background (cancelled with this composable), cached by the library,
    // and only READ while drawing. The effect re-runs when clips or library entries are added/removed, not while dragging.
    val thumbs = remember { HashMap<String, androidx.compose.ui.graphics.ImageBitmap>() }
    var mediaVersion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(media) {
        val lib = media ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { c.timeline.tracks.sumOf { it.clips.size } to lib.assets }
            .collect {
                val used = c.session.timeline.tracks.flatMap { tr -> tr.clips.mapNotNull { cl -> cl.assetId } }.toSet()
                for (id in used) {
                    val a = lib.session.asset(id) ?: continue
                    if (a.kind == "AUDIO" && lib.waveforms.cached(id) == null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lib.waveforms.load(a) { !isActive } }
                        mediaVersion++
                    } else if (a.kind == "VIDEO" && id !in thumbs) {
                        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lib.bitmaps.load(a, 0, 96) { !isActive } }
                        if (bmp != null) { thumbs[id] = bmp.asImageBitmap(); mediaVersion++ }
                    }
                }
            }
    }

    Canvas(
        modifier
            .height(contentHeight)
            .testTag("clip-canvas")
            .pointerInput(c, rows) {
                val slop = viewConfiguration.touchSlop
                val handlePx = HANDLE_WIDTH.toPx()
                val kfPx = KEYFRAME_TOUCH_RADIUS.toPx()
                val snapPx = SNAP_THRESHOLD.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Everything below is computed from the COMMITTED timeline captured once, never from the live preview.
                    val tl0 = c.session.committedState.timeline
                    val hit = try {
                        TimelineLayout.hit(tl0, rows, c.viewport, down.position.x, down.position.y, handlePx, kfPx)
                    } catch (e: Exception) {
                        TimelineHit.OnEmpty(null) // rows can be one frame stale right after a structural edit
                    }
                    var mode = Mode.PENDING
                    var pinching = false
                    var total = Offset.Zero
                    var gesture: EditorController.EditGesture? = null

                    var movingIds: Set<String> = emptySet()
                    var targets = LongArray(0)
                    var baseEdges = LongArray(0)
                    val candidates = ArrayList<Long>(8)
                    var lastDelta = 0L
                    var trimTrackId = ""
                    var trimClipId = ""
                    var trimEdge0 = 0L
                    var kfRefs = emptyList<KeyframeRef>()
                    var kfBase = 0L

                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (!pinching) {
                                    pinching = true
                                    gesture?.cancel() // a second finger turns the gesture into a pinch: drop the preview
                                    gesture = null
                                    mode = Mode.PASS
                                    snapGuideUs = null
                                }
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val focus = ev.calculateCentroid()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    var v = c.viewport
                                    if (zoom != 1f) v = v.zoomed(zoom.toDouble(), focus.x)
                                    if (pan.x != 0f) v = v.scrolledByPx(-pan.x)
                                    c.viewport = v
                                }
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinching) {
                                if (pressed == 0) break
                                continue
                            }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.isConsumed) { // someone else (a parent scroll) took the gesture
                                gesture?.cancel()
                                break
                            }
                            if (!ch.pressed) {
                                when (mode) {
                                    Mode.PENDING -> onTap(c, hit)
                                    Mode.MOVE_CLIPS -> {
                                        val g = gesture
                                        val target = TimelineLayout.rowAt(rows, ch.position.y)?.trackId
                                        val single = movingIds.singleOrNull()
                                        val origin = single?.let { tl0.findClip(it) }
                                        if (g != null && single != null && origin != null && target != null && target != origin.first.id) {
                                            g.commit(MoveClipToTrack(single, target, origin.second.startUs + lastDelta))
                                        } else {
                                            g?.commit()
                                        }
                                    }
                                    Mode.TRIM_START, Mode.TRIM_END, Mode.MOVE_KEYFRAMES -> gesture?.commit()
                                    else -> Unit
                                }
                                snapGuideUs = null
                                break
                            }
                            val d: Offset = ch.position - ch.previousPosition
                            total += d
                            if (mode == Mode.PENDING && total.getDistance() > slop) {
                                mode = when {
                                    hit is TimelineHit.OnClip && hit.zone == ClipZone.TRIM_START -> Mode.TRIM_START
                                    hit is TimelineHit.OnClip && hit.zone == ClipZone.TRIM_END -> Mode.TRIM_END
                                    hit is TimelineHit.OnClip -> Mode.MOVE_CLIPS
                                    hit is TimelineHit.OnKeyframes -> Mode.MOVE_KEYFRAMES
                                    abs(total.x) >= abs(total.y) -> Mode.SCROLL_X
                                    else -> Mode.PASS // vertical on empty space: let the track list scroll
                                }
                                when (mode) {
                                    Mode.MOVE_CLIPS -> {
                                        hit as TimelineHit.OnClip
                                        val sel = c.selection
                                        if (hit.clipId !in sel.clipIds) c.session.select(sel.selectClip(hit.clipId, c.multiSelect))
                                        movingIds = c.selection.clipIds
                                        targets = SnapEngine.targets(tl0, movingIds, c.playheadUs)
                                        baseEdges = movingIds.mapNotNull { tl0.findClip(it)?.second }.flatMap { listOf(it.startUs, it.endUs) }.toLongArray()
                                        gesture = c.beginGesture()
                                    }
                                    Mode.MOVE_KEYFRAMES -> {
                                        hit as TimelineHit.OnKeyframes
                                        val sel = c.selection
                                        if (!sel.keyframes.containsAll(hit.mark.refs)) {
                                            c.session.select(
                                                if (c.multiSelect) sel.copy(keyframes = sel.keyframes + hit.mark.refs)
                                                else Selection(keyframes = hit.mark.refs.toSet()),
                                            )
                                        }
                                        kfRefs = c.selection.keyframes.toList()
                                        kfBase = hit.mark.absoluteTimeUs
                                        gesture = c.beginGesture()
                                    }
                                    Mode.TRIM_START, Mode.TRIM_END -> {
                                        hit as TimelineHit.OnClip
                                        c.session.select(Selection(clipIds = setOf(hit.clipId)))
                                        val found = tl0.findClip(hit.clipId)
                                        if (found != null) {
                                            trimTrackId = found.first.id
                                            trimClipId = found.second.id
                                            trimEdge0 = if (mode == Mode.TRIM_START) found.second.startUs else found.second.endUs
                                            targets = SnapEngine.targets(tl0, setOf(hit.clipId), c.playheadUs)
                                            gesture = c.beginGesture()
                                        } else {
                                            mode = Mode.PASS
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                            when (mode) {
                                Mode.SCROLL_X -> {
                                    c.viewport = c.viewport.scrolledByPx(-d.x)
                                    ch.consume()
                                }
                                Mode.MOVE_CLIPS -> {
                                    val desired = c.viewport.pxToDuration(total.x)
                                    var target = desired
                                    if (c.snapEnabled && baseEdges.isNotEmpty()) {
                                        candidates.clear()
                                        for (e in baseEdges) candidates.add(e + desired)
                                        val s = SnapEngine.snap(candidates, targets, c.viewport.pxToDuration(snapPx))
                                        target += s.deltaUs
                                        snapGuideUs = s.snappedTo
                                    } else {
                                        snapGuideUs = null
                                    }
                                    val delta = MoveSolver.clampDelta(tl0, movingIds, target)
                                    if (delta != lastDelta) {
                                        if (gesture?.update(MoveClips(movingIds.toList(), delta)) == true) lastDelta = delta
                                    }
                                    ch.consume()
                                }
                                Mode.TRIM_START, Mode.TRIM_END -> {
                                    val found = tl0.findClip(trimClipId)
                                    if (found != null) {
                                        val start = mode == Mode.TRIM_START
                                        val cmd = trimCommand(c, tl0, found.first.id == trimTrackId, found.second, start, trimEdge0 + c.viewport.pxToDuration(total.x), targets, c.viewport.pxToDuration(snapPx)) { snapGuideUs = it }
                                        if (cmd != null) gesture?.update(cmd)
                                    }
                                    ch.consume()
                                }
                                Mode.MOVE_KEYFRAMES -> {
                                    var wanted = kfBase + c.viewport.pxToDuration(total.x)
                                    val near = abs(wanted - c.playheadUs) <= c.viewport.pxToDuration(snapPx)
                                    wanted = if (c.snapEnabled && near) c.playheadUs else Timecode.snapToFrame(wanted, c.session.fps)
                                    snapGuideUs = if (c.snapEnabled && near) wanted else null
                                    gesture?.update(MoveKeyframes(kfRefs, wanted - kfBase))
                                    ch.consume()
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        gesture?.cancel() // no-op once committed; drops the preview if the gesture was cancelled
                        snapGuideUs = null
                    }
                }
            },
    ) {
        @Suppress("UNUSED_VARIABLE") val mv = mediaVersion // redraw when a thumbnail / waveform arrives
        val vp = c.viewport
        val tl = c.timeline
        val sel = c.selection
        val fromUs = vp.scrollUs
        val toUs = vp.pxToTime(size.width)
        val handle = HANDLE_WIDTH.toPx()
        val r = 7.dp.toPx()
        drawRect(scheme.surface)
        for (row in rows) {
            val track = tl.tracks.firstOrNull { it.id == row.trackId } ?: continue
            drawLine(scheme.outlineVariant, Offset(0f, row.bottom), Offset(size.width, row.bottom), 1f)
            val dim = !track.visible
            val color = kindColor(track.kind).copy(alpha = if (dim) 0.35f else 1f)
            val visible = TimelineLayout.visibleClips(track, fromUs, toUs)
            for (clip in visible) {
                val x0 = vp.timeToPx(clip.startUs)
                val x1 = vp.timeToPx(clip.endUs)
                val w = (x1 - x0).coerceAtLeast(1f)
                val top = row.top + 3f
                val bottom = row.top + row.barHeight - 3f
                drawRect(color, Offset(x0, top), Size(w, bottom - top))
                if (clip.id in sel.clipIds) {
                    drawRect(MlColors.Accent, Offset(x0, top), Size(w, bottom - top), style = Stroke(3f))
                    val hw = minOf(handle, w / 3f)
                    drawRect(MlColors.Accent, Offset(x0, top), Size(hw * 0.35f, bottom - top))
                    drawRect(MlColors.Accent, Offset(x1 - hw * 0.35f, top), Size(hw * 0.35f, bottom - top))
                }
                val aid = clip.assetId
                if (media != null && aid != null && w > 8f) {
                    if (track.kind == TrackKind.VIDEO) {
                        thumbs[aid]?.let { img ->
                            val th = (bottom - top - 4f).coerceAtLeast(1f)
                            val tw = (th * img.width / img.height).coerceAtLeast(1f)
                            clipRect(x0, top, x1, bottom) {
                                var tx = x0 + 2f
                                while (tx < x1 && tx < size.width) {
                                    if (tx + tw > 0f) drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height), dstOffset = androidx.compose.ui.unit.IntOffset(tx.toInt(), (top + 2f).toInt()), dstSize = androidx.compose.ui.unit.IntSize(tw.toInt().coerceAtLeast(1), th.toInt().coerceAtLeast(1)), alpha = 0.55f)
                                    tx += tw
                                }
                            }
                        }
                    } else if (track.kind == TrackKind.AUDIO) {
                        media.waveforms.cached(aid)?.let { wf ->
                            val mid = (top + bottom) / 2f
                            val half = (bottom - top) / 2f - 3f
                            val stepPx = 2f
                            val stepUs = vp.pxToDuration(stepPx)
                            var px = maxOf(x0, 0f)
                            val end = minOf(x1, size.width)
                            while (px < end) {
                                val tUs = vp.pxToTime(px) - clip.startUs + clip.sourceStartUs
                                val peak = wf.peakBetween(tUs, tUs + stepUs)
                                drawLine(Color.White.copy(alpha = 0.8f), Offset(px, mid - peak * half), Offset(px, mid + peak * half), 1.5f)
                                px += stepPx
                            }
                        }
                    }
                }
                if (w > 40f) {
                    drawIntoCanvas { cv ->
                        cv.nativeCanvas.save()
                        cv.nativeCanvas.clipRect(x0, top, x1, bottom)
                        cv.nativeCanvas.drawText(clip.payload["text"] ?: clip.assetId?.let { aid -> media?.assets?.firstOrNull { it.id == aid }?.displayName } ?: track.kind.label, x0 + 10f, (top + bottom) / 2f + 4f, labelPaint)
                        cv.nativeCanvas.restore()
                    }
                }
            }
            if (row.expanded) {
                drawRect(scheme.surfaceVariant.copy(alpha = 0.4f), Offset(0f, row.laneTop), Size(size.width, row.height - row.barHeight))
                for (clip in visible) {
                    for (m in marks.clip(clip)) drawDiamond(vp.timeToPx(clip.startUs + m.timeUs), row.laneCenterY, r, m.refs.any { it in sel.keyframes }, false)
                }
                track.layerId?.let { lid ->
                    for (m in marks.layer(tl, lid)) {
                        val x = vp.timeToPx(m.timeUs)
                        if (x >= -r && x <= size.width + r) drawDiamond(x, row.laneCenterY, r, m.refs.any { it in sel.keyframes }, true)
                    }
                }
            }
            if (track.locked) drawRect(Color.Black.copy(alpha = 0.25f), Offset(0f, row.top), Size(size.width, row.height))
        }
        snapGuideUs?.let {
            val x = vp.timeToPx(it)
            drawLine(MlColors.Keyframe, Offset(x, 0f), Offset(x, size.height), 2f)
        }
        drawPlayhead(c, size.height)
    }
}

private fun DrawScope.drawDiamond(x: Float, y: Float, r: Float, selected: Boolean, hollow: Boolean) {
    if (x < -r || x > size.width + r) return
    val p = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
    val color = if (selected) MlColors.Keyframe else Color(0xFFE0E0E0)
    if (hollow && !selected) drawPath(p, color, style = Stroke(2.5f)) else drawPath(p, color)
}

// ------------------------------------------------------------------------------------------------ gesture helpers

private fun onTap(c: EditorController, hit: TimelineHit) {
    when (hit) {
        is TimelineHit.OnClip -> c.session.select(c.selection.selectClip(hit.clipId, c.multiSelect))
        is TimelineHit.OnKeyframes -> {
            val sel = c.selection
            val all = sel.keyframes.containsAll(hit.mark.refs)
            c.session.select(
                if (c.multiSelect) sel.copy(keyframes = if (all) sel.keyframes - hit.mark.refs.toSet() else sel.keyframes + hit.mark.refs)
                else Selection(keyframes = hit.mark.refs.toSet()),
            )
        }
        is TimelineHit.OnEmpty -> if (!c.multiSelect) c.session.clearSelection()
    }
}

/**
 * The absolute trim command for one edge, computed from the committed timeline [tl0]: snapped, never overlapping a
 * neighbour, never below the minimum length, never past the source. Null when the clip is gone.
 */
private fun trimCommand(
    c: EditorController,
    tl0: Timeline,
    @Suppress("UNUSED_PARAMETER") sameTrack: Boolean,
    clip: MediaClip,
    start: Boolean,
    desiredEdgeUs: Long,
    targets: LongArray,
    snapUs: Long,
    guide: (Long?) -> Unit,
): id.motionlab.engine.timeline.Command? {
    val track = tl0.findClip(clip.id)?.first ?: return null
    val limited = track.kind.isSourceLimited
    var wanted = desiredEdgeUs
    if (c.snapEnabled) {
        val s = SnapEngine.snap(listOf(wanted), targets, snapUs)
        wanted += s.deltaUs
        guide(s.snappedTo)
    } else {
        wanted = Timecode.snapToFrame(wanted, c.session.fps)
        guide(null)
    }
    val others = track.clips.filter { it.id != clip.id }
    return if (start) {
        val prevEnd = others.lastOrNull { it.endUs <= clip.startUs }?.endUs ?: 0L
        val lo = maxOf(prevEnd, clip.minStartUs(limited))
        val hi = clip.endUs - MIN_CLIP_DURATION_US
        TrimClipStart(clip.id, wanted.coerceIn(lo, maxOf(lo, hi)))
    } else {
        val nextStart = others.firstOrNull { it.startUs >= clip.endUs }?.startUs ?: Long.MAX_VALUE
        val lo = clip.startUs + MIN_CLIP_DURATION_US
        val hi = minOf(nextStart, clip.maxEndUs(limited))
        TrimClipEnd(clip.id, wanted.coerceIn(lo, maxOf(lo, hi)))
    }
}

@Suppress("unused")
private val keepViewportImport: TimelineViewport? = null
