package id.motionlab.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.motionlab.engine.animation.Easing
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.MotionPath
import id.motionlab.engine.animation.PathPoint
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.CompositeCommand
import id.motionlab.engine.timeline.SetKeyframe
import id.motionlab.engine.timeline.SetMotionPath
import kotlin.math.hypot
import kotlin.math.min

private sealed interface PathHit {
    data class Point(val id: String) : PathHit
    data class Out(val id: String) : PathHit
    data class In(val id: String) : PathHit
    data object None : PathHit
}

/**
 * Bezier motion-path editor for the selected clip/layer. The path lives in canvas coordinates (origin = canvas centre,
 * y down); the canvas rectangle is drawn to scale. Tap empty space to append a point, drag points and handles
 * (handles are mirrored unless "Corner" is on), the orange dot is the position at the playhead, so scrubbing previews
 * the motion. Speed along the path is the keyframed "Path progress" property, edited like any other property.
 */
@Composable
fun PathEditor(c: EditorController, modifier: Modifier = Modifier) {
    val s = c.session
    @Suppress("UNUSED_VARIABLE") val version = c.timeline
    @Suppress("UNUSED_VARIABLE") val playhead = c.playheadUs
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    if (owner == null) {
        Text("Select a clip or layer to edit its motion path.", modifier.padding(8.dp))
        return
    }
    val path = s.anim(owner).motionPath
    var selectedPoint by remember(owner) { mutableStateOf<String?>(null) }
    var corner by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(4.dp).testTag("path-editor"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path == null) {
                OutlinedButton(onClick = {
                    c.run { s.execute(SetMotionPath(owner, MotionPath.smoothThrough(listOf(-s.canvasWidth * 0.25 to 0.0, s.canvasWidth * 0.25 to 0.0)))) }
                }, modifier = Modifier.testTag("path-create")) { Text("Create path") }
            } else {
                OutlinedButton(onClick = {
                    selectedPoint?.let { id -> c.run { s.execute(SetMotionPath(owner, path.removePoint(id))) }; selectedPoint = null }
                }, enabled = selectedPoint != null && path.points.size > 2, modifier = Modifier.testTag("path-delete-point")) { Text("Delete point") }
                OutlinedButton(onClick = {
                    c.run { s.execute(SetMotionPath(owner, MotionPath.smoothThrough(path.points.map { it.x to it.y }).withRotateAlongPath(path.rotateAlongPath))) }
                    selectedPoint = null
                }, modifier = Modifier.testTag("path-smooth")) { Text("Auto-smooth") }
                OutlinedButton(onClick = { c.run { s.execute(SetMotionPath(owner, null)) }; selectedPoint = null }, modifier = Modifier.testTag("path-remove")) { Text("Remove") }
            }
        }
        if (path != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Rotate along path")
                Switch(checked = path.rotateAlongPath, onCheckedChange = { c.run { s.execute(SetMotionPath(owner, path.withRotateAlongPath(it))) } }, modifier = Modifier.testTag("path-rotate"))
                Text("Corner")
                Switch(checked = corner, onCheckedChange = { corner = it }, modifier = Modifier.testTag("path-corner"))
            }
            OutlinedButton(onClick = { c.run { animateAlongPath(c, owner) } }, modifier = Modifier.testTag("path-animate")) {
                Text("Animate along path (0 → 1 over the whole clip)")
            }
        }
        PathCanvas(c, owner, path, selectedPoint, { selectedPoint = it }, corner, Modifier.fillMaxWidth().weight(1f))
    }
}

private fun animateAlongPath(c: EditorController, owner: AnimationOwner) {
    val s = c.session
    val duration = when (owner) {
        is AnimationOwner.ClipOwner -> s.timeline.requireClip(owner.clipId).second.durationUs
        is AnimationOwner.LayerOwner -> s.timeline.durationUs
    }
    if (duration <= 0) return
    s.execute(
        CompositeCommand(
            "Animate along path",
            listOf(
                SetKeyframe(owner, PropertyNames.MOTION_PROGRESS, Keyframe(0, 0.0, Easing.EASE_IN_OUT)),
                SetKeyframe(owner, PropertyNames.MOTION_PROGRESS, Keyframe(duration, 1.0)),
            ),
        ),
    )
}

@Composable
private fun PathCanvas(c: EditorController, owner: AnimationOwner, path: MotionPath?, selected: String?, select: (String?) -> Unit, corner: Boolean, modifier: Modifier) {
    val s = c.session
    val density = LocalDensity.current
    val latestPath by rememberUpdatedState(path)
    val latestCorner by rememberUpdatedState(corner)
    val scheme = MaterialTheme.colorScheme

    // canvas px -> screen px: uniform scale, canvas centred in the view
    fun scaleFor(w: Float, h: Float) = min(w / s.canvasWidth, h / s.canvasHeight) * 0.9f

    Canvas(
        modifier.testTag("path-canvas").pointerInput(owner) {
            val hitR = 22.dp.toPx()
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val sc = scaleFor(w, h)
                val cx = w / 2f
                val cy = h / 2f
                val p0 = latestPath
                fun sx(x: Double) = cx + x.toFloat() * sc
                fun sy(y: Double) = cy + y.toFloat() * sc
                var hit: PathHit = PathHit.None
                if (p0 != null) {
                    val sel = p0.points.firstOrNull { it.id == selected }
                    if (sel != null) {
                        if (hypot(sx(sel.x + sel.outDx) - down.position.x, sy(sel.y + sel.outDy) - down.position.y) <= hitR) hit = PathHit.Out(sel.id)
                        else if (hypot(sx(sel.x + sel.inDx) - down.position.x, sy(sel.y + sel.inDy) - down.position.y) <= hitR) hit = PathHit.In(sel.id)
                    }
                    if (hit == PathHit.None) {
                        var best = Float.MAX_VALUE
                        for (p in p0.points) {
                            val d = hypot(sx(p.x) - down.position.x, sy(p.y) - down.position.y)
                            if (d <= hitR && d < best) { best = d; hit = PathHit.Point(p.id) }
                        }
                    }
                }
                val hitF: PathHit = hit
                var started = false
                var gesture: EditorController.EditGesture? = null
                try {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.isConsumed) break
                    val cur = p0 // always edit from the COMMITTED path; the preview is absolute, never accumulated
                    if (!ch.pressed) {
                        if (!started) {
                            when (hitF) {
                                is PathHit.Point -> select(hitF.id)
                                is PathHit.Out, is PathHit.In -> Unit
                                PathHit.None -> if (cur != null) {
                                    val x = ((down.position.x - cx) / sc).toDouble()
                                    val y = ((down.position.y - cy) / sc).toDouble()
                                    val id = "pp-" + java.util.UUID.randomUUID().toString().take(8)
                                    c.run { s.execute(SetMotionPath(owner, cur.addPoint(PathPoint(id, x, y)))) }
                                    select(id)
                                }
                            }
                        }
                        break
                    }
                    if (cur == null) continue
                    if (!started && (ch.position - down.position).getDistance() > slop) {
                        started = true
                        if (hitF != PathHit.None) gesture = c.beginGesture()
                    }
                    if (!started) continue
                    val x = ((ch.position.x - cx) / sc).toDouble()
                    val y = ((ch.position.y - cy) / sc).toDouble()
                    when (hitF) {
                        is PathHit.Point -> cur.point(hitF.id)?.let { p -> gesture?.update(SetMotionPath(owner, cur.updatePoint(p.copy(x = x, y = y)))) }
                        is PathHit.Out -> cur.point(hitF.id)?.let { p ->
                            val dx = x - p.x; val dy = y - p.y
                            val np = if (latestCorner) p.copy(outDx = dx, outDy = dy) else p.copy(outDx = dx, outDy = dy, inDx = -dx, inDy = -dy)
                            gesture?.update(SetMotionPath(owner, cur.updatePoint(np)))
                        }
                        is PathHit.In -> cur.point(hitF.id)?.let { p ->
                            val dx = x - p.x; val dy = y - p.y
                            val np = if (latestCorner) p.copy(inDx = dx, inDy = dy) else p.copy(inDx = dx, inDy = dy, outDx = -dx, outDy = -dy)
                            gesture?.update(SetMotionPath(owner, cur.updatePoint(np)))
                        }
                        PathHit.None -> Unit
                    }
                    ch.consume()
                }
                gesture?.commit()
                } finally {
                    gesture?.cancel()
                }
            }
        },
    ) {
        val sc = scaleFor(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawRect(scheme.surfaceVariant)
        val cw = s.canvasWidth * sc
        val chh = s.canvasHeight * sc
        drawRect(Color.Black, Offset(cx - cw / 2f, cy - chh / 2f), Size(cw, chh))
        drawRect(scheme.outline, Offset(cx - cw / 2f, cy - chh / 2f), Size(cw, chh), style = Stroke(1.5f))
        drawLine(scheme.outline.copy(alpha = 0.5f), Offset(cx, cy - chh / 2f), Offset(cx, cy + chh / 2f), 1f)
        drawLine(scheme.outline.copy(alpha = 0.5f), Offset(cx - cw / 2f, cy), Offset(cx + cw / 2f, cy), 1f)
        val p = path ?: return@Canvas
        val flat = p.flatten()
        val line = Path()
        flat.forEachIndexed { i, (x, y) -> if (i == 0) line.moveTo(cx + x.toFloat() * sc, cy + y.toFloat() * sc) else line.lineTo(cx + x.toFloat() * sc, cy + y.toFloat() * sc) }
        drawPath(line, scheme.primary, style = Stroke(3f))
        for (pt in p.points) {
            val isSel = pt.id == selected
            val px = cx + pt.x.toFloat() * sc
            val py = cy + pt.y.toFloat() * sc
            if (isSel) {
                val o = Offset(cx + (pt.x + pt.outDx).toFloat() * sc, cy + (pt.y + pt.outDy).toFloat() * sc)
                val i = Offset(cx + (pt.x + pt.inDx).toFloat() * sc, cy + (pt.y + pt.inDy).toFloat() * sc)
                drawLine(Color(0xFFFFB74D), Offset(px, py), o, 2f); drawLine(Color(0xFFFFB74D), Offset(px, py), i, 2f)
                drawCircle(Color(0xFFFFB74D), 7.dp.toPx(), o); drawCircle(Color(0xFFFFB74D), 7.dp.toPx(), i)
            }
            drawCircle(if (isSel) Color(0xFFFFD54F) else Color.White, 9.dp.toPx(), Offset(px, py))
            drawCircle(Color.Black, 9.dp.toPx(), Offset(px, py), style = Stroke(2f))
        }
        // Position on the path at the playhead (needs the progress property to be animated or constant).
        val progress = s.valueAtPlayhead(owner, PropertyNames.MOTION_PROGRESS).coerceIn(0.0, 1.0)
        val m = p.pointAt(progress)
        drawCircle(Color(0xFFFF7043), 8.dp.toPx(), Offset(cx + m.x.toFloat() * sc, cy + m.y.toFloat() * sc))
    }
}
