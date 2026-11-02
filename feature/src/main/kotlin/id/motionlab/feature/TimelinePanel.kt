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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.ClipZone
import id.motionlab.engine.timeline.KeyframeMark
import id.motionlab.engine.timeline.MIN_CLIP_DURATION_US
import id.motionlab.engine.timeline.MoveClipToTrack
import id.motionlab.engine.timeline.MoveClips
import id.motionlab.engine.timeline.MoveKeyframes
import id.motionlab.engine.timeline.MoveSolver
import id.motionlab.engine.timeline.MoveTrack
import id.motionlab.engine.timeline.LinkTrackToLayer
import id.motionlab.engine.timeline.RemoveTrack
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetTrackFlags
import id.motionlab.engine.timeline.SnapEngine
import id.motionlab.engine.timeline.Timecode
import id.motionlab.engine.timeline.TimelineHit
import id.motionlab.engine.timeline.TimelineLayout
import id.motionlab.engine.timeline.TimelineViewport
import id.motionlab.engine.timeline.Track
import id.motionlab.engine.timeline.TrackKind
import id.motionlab.engine.timeline.TrimClipEnd
import id.motionlab.engine.timeline.TrimClipStart
import kotlin.math.abs

private val ROW_EXPANDED = 80.dp
private val ROW_COLLAPSED = 56.dp
private val RULER_HEIGHT = 32.dp
private val HANDLE_WIDTH = 18.dp
private val KEYFRAME_TOUCH_RADIUS = 16.dp
private val SNAP_THRESHOLD = 10.dp

private fun kindColor(kind: TrackKind): Color = when (kind) {
    TrackKind.VIDEO -> Color(0xFF2E6F9E)
    TrackKind.AUDIO -> Color(0xFF3F8F6B)
    TrackKind.IMAGE -> Color(0xFF7A4FA0)
    TrackKind.TEXT -> Color(0xFFB5762A)
    TrackKind.OBJECT -> Color(0xFF9E4A57)
    TrackKind.SHAPE -> Color(0xFF3F6FD0)
    TrackKind.OVERLAY -> Color(0xFF666E7A)
}

/**
 * Track headers + clip canvas + ruler. The clip canvas draws only what is visible (virtualisation), reads all state
 * inside the draw scope (a playhead move redraws, it does not recompose) and handles every gesture in ONE pointer
 * handler so gestures cannot fight each other:
 *  - two fingers: pinch zoom + pan (always wins),
 *  - one finger on a clip / trim handle / keyframe: edit (move, trim, move keyframes),
 *  - one finger on empty space: horizontal drag scrolls the time axis; vertical drag is NOT consumed, so the
 *    surrounding vertical scroll moves the track list.
 */
@Composable
fun TimelinePanel(c: EditorController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val headerWidth = 120.dp
    val expandedPx = with(density) { ROW_EXPANDED.toPx() }
    val collapsedPx = with(density) { ROW_COLLAPSED.toPx() }
    val rows = TimelineLayout.rows(c.state.timeline, expandedPx, collapsedPx)
    val scroll = rememberScrollState()
    val contentHeight = with(density) { TimelineLayout.totalHeight(rows).toDp() }.coerceAtLeast(ROW_COLLAPSED)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Fixed top line: spacer over the header column + ruler over the canvas.
        Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
            Box(Modifier.width(headerWidth).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(Timecode.format(c.playheadUs, c.session.fps), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("timecode"))
            }
            Ruler(c, Modifier.weight(1f).fillMaxSize())
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(headerWidth).fillMaxSize().verticalScroll(scroll)) {
                if (rows.isEmpty()) {
                    Text("No tracks. Use + Track.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                }
                rows.forEach { row ->
                    val track = c.state.timeline.track(row.trackId)
                    TrackHeader(c, track, with(density) { row.height.toDp() })
                }
            }
            Box(Modifier.weight(1f).fillMaxSize().verticalScroll(scroll)) {
                ClipCanvas(c, contentHeight, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TrackHeader(c: EditorController, track: Track, height: androidx.compose.ui.unit.Dp) {
    var menu by remember { mutableStateOf(false) }
    var layerMenu by remember { mutableStateOf(false) }
    val tl = c.state.timeline
    Column(
        Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 6.dp)
            .testTag("track-header-${track.id}"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            track.name + (track.layerId?.let { " ↳" + (tl.findLayer(it)?.name ?: "") } ?: ""),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(16.dp),
        )
        Row {
            HeaderButton(if (track.locked) "🔒" else "🔓", if (track.locked) "Unlock ${track.name}" else "Lock ${track.name}", "lock-${track.id}") {
                c.run { c.session.execute(SetTrackFlags(track.id, locked = !track.locked)) }
            }
            HeaderButton(if (track.visible) "👁" else "∅", if (track.visible) "Hide ${track.name}" else "Show ${track.name}", "visible-${track.id}") {
                c.run { c.session.execute(SetTrackFlags(track.id, visible = !track.visible)) }
            }
            Box {
                HeaderButton("⋮", "Track menu ${track.name}", "menu-${track.id}") { menu = true }
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
                        c.run { c.session.execute(MoveTrack(track.id, (tl.trackIndex(track.id) + 1).coerceAtMost(tl.tracks.size - 1))) }
                    })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = {
                        menu = false
                        c.run { c.session.execute(MoveTrack(track.id, (tl.trackIndex(track.id) - 1).coerceAtLeast(0))) }
                    })
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
                    tl.layers.forEach { l ->
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
private fun HeaderButton(label: String, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick).testTag(tag).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label) }
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
    Canvas(
        modifier
            .testTag("ruler")
            .onSizeChanged { c.timelineWidthPx = it.width.toFloat() }
            .pointerInput(c) {
                // Tap or drag on the ruler scrubs the playhead (frame-snapped).
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
        val step = vp.rulerStepUs(with(density) { 64.dp.toPx() })
        val from = (vp.scrollUs / step) * step
        val to = vp.pxToTime(size.width)
        var t = from
        while (t <= to) {
            val x = vp.timeToPx(t)
            drawLine(fg, Offset(x, size.height * 0.45f), Offset(x, size.height), 1.5f)
            drawIntoCanvas { cv ->
                paint.color = fg.toArgb()
                cv.nativeCanvas.drawText(Timecode.format(t, c.session.fps), x + 3f, size.height * 0.4f, paint)
            }
            for (i in 1..3) {
                val mx = vp.timeToPx(t + step * i / 4)
                drawLine(fg.copy(alpha = 0.5f), Offset(mx, size.height * 0.75f), Offset(mx, size.height), 1f)
            }
            t += step
        }
        drawPlayhead(c, size.height, Color(0xFFFF4D4D))
    }
}

private fun DrawScope.drawPlayhead(c: EditorController, height: Float, color: Color) {
    val x = c.viewport.timeToPx(c.playheadUs)
    if (x < -2f || x > size.width + 2f) return
    drawLine(color, Offset(x, 0f), Offset(x, height), 2f)
    val p = Path().apply {
        moveTo(x - 7f, 0f); lineTo(x + 7f, 0f); lineTo(x, 12f); close()
    }
    drawPath(p, color)
}

// ------------------------------------------------------------------------------------------------ clip canvas

private enum class Mode { PENDING, MOVE_CLIPS, TRIM_START, TRIM_END, MOVE_KEYFRAMES, SCROLL_X, PASS }

@Composable
private fun ClipCanvas(c: EditorController, contentHeight: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val labelPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 11.dp.toPx() }
            color = android.graphics.Color.WHITE
        }
    }
    var snapGuideUs by remember { mutableStateOf<Long?>(null) }
    val expandedPx = with(density) { ROW_EXPANDED.toPx() }
    val collapsedPx = with(density) { ROW_COLLAPSED.toPx() }

    Canvas(
        modifier
            .height(contentHeight)
            .testTag("clip-canvas")
            .pointerInput(c) {
                val slop = viewConfiguration.touchSlop
                val handlePx = HANDLE_WIDTH.toPx()
                val kfPx = KEYFRAME_TOUCH_RADIUS.toPx()
                val snapUs = { c.viewport.pxToDuration(SNAP_THRESHOLD.toPx()) }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tl0 = c.state.timeline
                    val rows = TimelineLayout.rows(tl0, expandedPx, collapsedPx)
                    val hit = TimelineLayout.hit(tl0, rows, c.viewport, down.position.x, down.position.y, handlePx, kfPx)
                    var mode = Mode.PENDING
                    var pinching = false
                    var total = Offset.Zero
                    var applied = 0L
                    var movingIds: Set<String> = emptySet()
                    var moveRefs = emptyList<id.motionlab.engine.timeline.KeyframeRef>()
                    var lastAbsKfTime = 0L
                    var trimEdge0 = 0L

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (!pinching) {
                                pinching = true
                                if (mode != Mode.PENDING) c.session.endGesture()
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
                            continue // ignore the remaining finger until everything is lifted
                        }
                        val ch: PointerInputChange = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (mode == Mode.PENDING) onTap(c, hit)
                            else if (mode != Mode.SCROLL_X && mode != Mode.PASS) finishDrag(c, mode, hit, rows, ch.position.y)
                            snapGuideUs = null
                            break
                        }
                        val d = ch.positionChange()
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
                                    val sel = c.state.selection
                                    if (hit.clipId !in sel.clipIds) c.session.select(sel.selectClip(hit.clipId, c.multiSelect))
                                    movingIds = c.state.selection.clipIds
                                }
                                Mode.MOVE_KEYFRAMES -> {
                                    hit as TimelineHit.OnKeyframes
                                    val sel = c.state.selection
                                    if (!sel.keyframes.containsAll(hit.mark.refs)) {
                                        c.session.select(
                                            if (c.multiSelect) sel.copy(keyframes = sel.keyframes + hit.mark.refs)
                                            else Selection(keyframes = hit.mark.refs.toSet()),
                                        )
                                    }
                                    moveRefs = c.state.selection.keyframes.toList()
                                    lastAbsKfTime = hit.mark.absoluteTimeUs
                                }
                                Mode.TRIM_START, Mode.TRIM_END -> {
                                    hit as TimelineHit.OnClip
                                    c.session.select(Selection(clipIds = setOf(hit.clipId)))
                                    trimEdge0 = c.state.timeline.findClip(hit.clipId)?.second?.let { if (mode == Mode.TRIM_START) it.startUs else it.endUs } ?: 0L
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
                                val tl = c.state.timeline
                                val desired = c.viewport.pxToDuration(total.x)
                                var target = desired
                                if (c.snapEnabled && movingIds.isNotEmpty()) {
                                    val cands = movingIds.mapNotNull { tl.findClip(it)?.second }
                                        .flatMap { listOf(it.startUs, it.endUs) }
                                        .map { it - applied + desired } // where they would be at the desired delta
                                    val s = SnapEngine.snap(cands, SnapEngine.targets(tl, movingIds, c.playheadUs), snapUs())
                                    target = desired + s.deltaUs
                                    snapGuideUs = s.snappedTo
                                }
                                val inc = MoveSolver.clampDelta(tl, movingIds, target - applied)
                                if (inc != 0L && c.run { c.session.execute(MoveClips(movingIds.toList(), inc)) }) applied += inc
                                ch.consume()
                            }
                            Mode.TRIM_START, Mode.TRIM_END -> {
                                hit as TimelineHit.OnClip
                                trim(c, hit, mode == Mode.TRIM_START, trimEdge0 + c.viewport.pxToDuration(total.x), snapUs()) { snapGuideUs = it }
                                ch.consume()
                            }
                            Mode.MOVE_KEYFRAMES -> {
                                hit as TimelineHit.OnKeyframes
                                val fps = c.session.fps
                                var wanted = hit.mark.absoluteTimeUs + c.viewport.pxToDuration(total.x)
                                wanted = if (c.snapEnabled && abs(wanted - c.playheadUs) <= snapUs()) c.playheadUs else Timecode.snapToFrame(wanted, fps)
                                snapGuideUs = if (wanted == c.playheadUs) wanted else null
                                val inc = wanted - lastAbsKfTime
                                if (inc != 0L && c.run { c.session.execute(MoveKeyframes(moveRefs, inc)) }) lastAbsKfTime += inc
                                ch.consume()
                            }
                            else -> Unit
                        }
                    }
                    if (mode != Mode.PENDING && mode != Mode.PASS && mode != Mode.SCROLL_X) c.session.endGesture()
                    snapGuideUs = null
                }
            },
    ) {
        val vp = c.viewport
        val tl = c.state.timeline
        val sel = c.state.selection
        val rows = TimelineLayout.rows(tl, expandedPx, collapsedPx)
        val fromUs = vp.scrollUs
        val toUs = vp.pxToTime(size.width)
        drawRect(scheme.surface)
        for (row in rows) {
            val track = tl.track(row.trackId)
            drawLine(scheme.outlineVariant, Offset(0f, row.bottom), Offset(size.width, row.bottom), 1f)
            val dim = !track.visible
            for (clip in TimelineLayout.visibleClips(track, fromUs, toUs)) {
                val x0 = vp.timeToPx(clip.startUs)
                val x1 = vp.timeToPx(clip.endUs)
                val selected = clip.id in sel.clipIds
                val base = kindColor(track.kind).copy(alpha = if (dim) 0.35f else 1f)
                val top = row.top + 3f
                val bottom = row.top + row.barHeight - 3f
                drawRect(base, Offset(x0, top), Size((x1 - x0).coerceAtLeast(1f), bottom - top))
                if (selected) {
                    drawRect(Color.White, Offset(x0, top), Size((x1 - x0).coerceAtLeast(1f), bottom - top), style = Stroke(3f))
                    val hw = minOf(HANDLE_WIDTH.toPx(), (x1 - x0) / 3f)
                    drawRect(Color.White, Offset(x0, top), Size(hw * 0.35f, bottom - top))
                    drawRect(Color.White, Offset(x1 - hw * 0.35f, top), Size(hw * 0.35f, bottom - top))
                }
                if (x1 - x0 > 40f) {
                    drawIntoCanvas { cv ->
                        cv.nativeCanvas.save()
                        cv.nativeCanvas.clipRect(x0, top, x1, bottom)
                        val label = clip.payload["text"] ?: clip.assetId ?: track.kind.label
                        cv.nativeCanvas.drawText(label, x0 + 10f, (top + bottom) / 2f + 4f, labelPaint)
                        cv.nativeCanvas.restore()
                    }
                }
            }
            if (row.expanded) {
                drawRect(scheme.surfaceVariant.copy(alpha = 0.4f), Offset(0f, row.laneTop), Size(size.width, row.height - row.barHeight))
                for (m in TimelineLayout.marks(tl, row.trackId)) {
                    if (m.absoluteTimeUs < fromUs - 1_000_000 || m.absoluteTimeUs > toUs + 1_000_000) continue
                    drawDiamond(vp.timeToPx(m.absoluteTimeUs), row.laneCenterY, m, sel)
                }
            }
            if (track.locked) drawRect(Color.Black.copy(alpha = 0.25f), Offset(0f, row.top), Size(size.width, row.height))
        }
        snapGuideUs?.let {
            val x = vp.timeToPx(it)
            drawLine(Color(0xFFFFD54F), Offset(x, 0f), Offset(x, size.height), 2f)
        }
        drawPlayhead(c, size.height, Color(0xFFFF4D4D))
    }
}

private fun DrawScope.drawDiamond(x: Float, y: Float, m: KeyframeMark, sel: Selection) {
    val r = 7.dp.toPx()
    val selected = m.refs.any { it in sel.keyframes }
    val p = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
    val layer = m.owner is AnimationOwner.LayerOwner
    val color = if (selected) Color(0xFFFFD54F) else Color(0xFFE0E0E0)
    if (layer && !selected) drawPath(p, color, style = Stroke(2.5f)) else drawPath(p, color)
}

// ------------------------------------------------------------------------------------------------ gesture helpers

private fun onTap(c: EditorController, hit: TimelineHit) {
    when (hit) {
        is TimelineHit.OnClip -> c.session.select(c.state.selection.selectClip(hit.clipId, c.multiSelect))
        is TimelineHit.OnKeyframes -> {
            val sel = c.state.selection
            val all = sel.keyframes.containsAll(hit.mark.refs)
            c.session.select(
                if (c.multiSelect) sel.copy(keyframes = if (all) sel.keyframes - hit.mark.refs.toSet() else sel.keyframes + hit.mark.refs)
                else Selection(keyframes = hit.mark.refs.toSet()),
            )
        }
        is TimelineHit.OnEmpty -> if (!c.multiSelect) c.session.clearSelection()
    }
}

private fun finishDrag(c: EditorController, mode: Mode, hit: TimelineHit, rows: List<id.motionlab.engine.timeline.RowGeometry>, endY: Float) {
    if (mode != Mode.MOVE_CLIPS || hit !is TimelineHit.OnClip) return
    val ids = c.state.selection.clipIds
    if (ids.size != 1) return
    val target = TimelineLayout.rowAt(rows, endY)?.trackId ?: return
    val (track, clip) = c.state.timeline.findClip(hit.clipId) ?: return
    if (target != track.id) c.run { c.session.execute(MoveClipToTrack(clip.id, target, clip.startUs)) }
}

/** Trims one edge so the dragged edge follows the finger's movement, snapped, never overlapping a neighbour or going below the minimum. */
private fun trim(
    c: EditorController,
    hit: TimelineHit.OnClip,
    start: Boolean,
    desiredEdgeUs: Long,
    snapUs: Long,
    guide: (Long?) -> Unit,
) {
    val tl = c.state.timeline
    val (track, clip) = tl.findClip(hit.clipId) ?: return
    val limited = track.kind.isSourceLimited
    var wanted = desiredEdgeUs
    if (c.snapEnabled) {
        val s = SnapEngine.snap(listOf(wanted), SnapEngine.targets(tl, setOf(clip.id), c.playheadUs), snapUs)
        wanted += s.deltaUs
        guide(s.snappedTo)
    } else {
        wanted = Timecode.snapToFrame(wanted, c.session.fps)
        guide(null)
    }
    val others = track.clips.filter { it.id != clip.id }
    if (start) {
        val prevEnd = others.lastOrNull { it.endUs <= clip.startUs }?.endUs ?: 0L
        val lo = maxOf(prevEnd, clip.minStartUs(limited))
        val hi = clip.endUs - MIN_CLIP_DURATION_US
        val v = wanted.coerceIn(lo, maxOf(lo, hi))
        if (v != clip.startUs) c.run { c.session.execute(TrimClipStart(clip.id, v)) }
    } else {
        val nextStart = others.firstOrNull { it.startUs >= clip.endUs }?.startUs ?: Long.MAX_VALUE
        val lo = clip.startUs + MIN_CLIP_DURATION_US
        val hi = minOf(nextStart, clip.maxEndUs(limited))
        val v = wanted.coerceIn(lo, maxOf(lo, hi))
        if (v != clip.endUs) c.run { c.session.execute(TrimClipEnd(clip.id, v)) }
    }
}
