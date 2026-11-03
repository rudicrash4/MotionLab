package id.motionlab.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.CompositeCommand
import id.motionlab.engine.timeline.PreviewMath
import id.motionlab.engine.timeline.RenderItem
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetPropertyValue
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Selection outline, corner handles, rotate handle, anchor point and optional grid, drawn over the GL preview.
 * It uses the SAME scene frame as the renderer (session.frame()), and edits the SAME animation data through
 * SetPropertyValue (auto-key when animated). Drags are transactions: preview per move, one undo step on release.
 * Layer-parent transforms are not compensated when dragging (positions are the clip's own local values).
 */
@Composable
fun PreviewOverlay(c: EditorController, modifier: Modifier = Modifier) {
    val s = c.session
    Canvas(
        modifier.testTag("preview-overlay").pointerInput(c) {
            val hitR = 26.dp.toPx()
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val cw = s.canvasWidth.toDouble(); val chh = s.canvasHeight.toDouble()
                val k = size.width / cw // screen px per canvas px
                fun toCanvas(o: Offset) = (o.x / k - cw / 2) to (o.y / k - chh / 2)
                val frame = s.committedState.let { s.frame() }
                val sel = c.selection
                val selItem = frame.items.firstOrNull { it.clip.id in sel.clipIds }
                val (dx0, dy0) = toCanvas(down.position)
                val geo = selItem?.let { geometry(c, it) }
                var mode = 0 // 0 none, 1 move, 2 scale, 3 rotate
                if (geo != null && c.tool == EditorTool.TRANSFORM) {
                    val rot = geo.rotateHandle
                    if (hypot((rot.first - dx0) * k, (rot.second - dy0) * k) <= hitR) mode = 3
                    else if (geo.corners.any { hypot((it.first - dx0) * k, (it.second - dy0) * k) <= hitR }) mode = 2
                }
                if (mode == 0 && geo != null && c.tool != EditorTool.EDIT && geo.contains(dx0, dy0)) mode = 1
                val owner = selItem?.let { AnimationOwner.ClipOwner(it.clip.id) }
                val localT = owner?.let { s.localTime(it) } ?: 0L
                val px0 = owner?.let { s.valueAtPlayhead(it, PropertyNames.POSITION_X) } ?: 0.0
                val py0 = owner?.let { s.valueAtPlayhead(it, PropertyNames.POSITION_Y) } ?: 0.0
                val sx0 = owner?.let { s.valueAtPlayhead(it, PropertyNames.SCALE_X) } ?: 1.0
                val sy0 = owner?.let { s.valueAtPlayhead(it, PropertyNames.SCALE_Y) } ?: 1.0
                val r0 = owner?.let { s.valueAtPlayhead(it, PropertyNames.ROTATION) } ?: 0.0
                var total = Offset.Zero
                var started = false
                var gesture: EditorController.EditGesture? = null
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) { gesture?.cancel(); gesture = null; break }
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.isConsumed) break
                        if (!ch.pressed) {
                            if (!started) { // tap: select the top-most clip under the finger
                                val hitItem = frame.items.lastOrNull { geometry(c, it)?.contains(dx0, dy0) == true }
                                if (hitItem != null) s.select(Selection(clipIds = setOf(hitItem.clip.id))) else if (!c.multiSelect) s.clearSelection()
                            }
                            gesture?.commit()
                            break
                        }
                        total += ch.position - ch.previousPosition
                        if (!started && total.getDistance() > slop && mode != 0 && owner != null) {
                            started = true
                            gesture = c.beginGesture()
                        }
                        val g = gesture ?: continue
                        val (cx, cy) = toCanvas(ch.position)
                        val cmds: List<id.motionlab.engine.timeline.Command> = when (mode) {
                            1 -> listOf(
                                SetPropertyValue(owner!!, PropertyNames.POSITION_X, localT, px0 + (cx - dx0)),
                                SetPropertyValue(owner, PropertyNames.POSITION_Y, localT, py0 + (cy - dy0)),
                            )
                            2 -> {
                                val ctr = geo!!.center
                                val d0 = hypot(dx0 - ctr.first, dy0 - ctr.second).coerceAtLeast(1.0)
                                val f = (hypot(cx - ctr.first, cy - ctr.second) / d0).coerceIn(0.05, 20.0)
                                listOf(
                                    SetPropertyValue(owner!!, PropertyNames.SCALE_X, localT, sx0 * f),
                                    SetPropertyValue(owner, PropertyNames.SCALE_Y, localT, sy0 * f),
                                )
                            }
                            else -> {
                                val ctr = geo!!.center
                                val a0 = atan2(dy0 - ctr.second, dx0 - ctr.first)
                                val a1 = atan2(cy - ctr.second, cx - ctr.first)
                                listOf(SetPropertyValue(owner!!, PropertyNames.ROTATION, localT, r0 + Math.toDegrees(a1 - a0)))
                            }
                        }
                        g.update(CompositeCommand("Transform", cmds))
                        ch.consume()
                    }
                } finally {
                    gesture?.cancel()
                }
            }
        },
    ) {
        @Suppress("UNUSED_VARIABLE") val t = c.timeline // redraw on previewed/committed edits
        @Suppress("UNUSED_VARIABLE") val p = c.playheadUs
        val sel = c.selection
        val cw = s.canvasWidth.toDouble(); val chh = s.canvasHeight.toDouble()
        val k = (size.width / cw).toFloat()
        fun sp(x: Double, y: Double) = Offset(((x + cw / 2) * k).toFloat(), ((y + chh / 2) * k).toFloat())
        if (c.previewSettings.showGrid) {
            val gridColor = Color.White.copy(alpha = 0.25f)
            for (i in 1..2) {
                drawLine(gridColor, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
                drawLine(gridColor, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
            }
            drawLine(gridColor, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
            drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        }
        val item = s.frame().items.firstOrNull { it.clip.id in sel.clipIds } ?: return@Canvas
        val g = geometry(c, item) ?: return@Canvas
        val path = Path().apply {
            val q = g.corners.map { sp(it.first, it.second) }
            moveTo(q[0].x, q[0].y); for (i in 1..3) lineTo(q[i].x, q[i].y); close()
        }
        drawPath(path, MlColors.Accent, style = Stroke(2.dp.toPx()))
        val ctr = sp(g.center.first, g.center.second)
        drawCircle(MlColors.Accent, 4.dp.toPx(), ctr) // anchor point
        drawCircle(Color.Black, 4.dp.toPx(), ctr, style = Stroke(1.dp.toPx()))
        if (c.tool == EditorTool.TRANSFORM) {
            for (cr in g.corners) drawCircle(MlColors.Accent, 7.dp.toPx(), sp(cr.first, cr.second))
            val top = sp((g.corners[0].first + g.corners[1].first) / 2, (g.corners[0].second + g.corners[1].second) / 2)
            val rot = sp(g.rotateHandle.first, g.rotateHandle.second)
            drawLine(MlColors.Accent, top, rot, 1.5.dp.toPx())
            drawCircle(MlColors.Keyframe, 7.dp.toPx(), rot)
        }
    }
}

/** Bounding box of a rendered item in canvas coordinates (origin = canvas centre). */
private class Geo(val corners: List<Pair<Double, Double>>, val center: Pair<Double, Double>, val rotateHandle: Pair<Double, Double>) {
    fun contains(x: Double, y: Double): Boolean {
        // point-in-convex-quad via consistent cross-product sign
        var pos = false; var neg = false
        for (i in 0..3) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            val cr = (b.first - a.first) * (y - a.second) - (b.second - a.second) * (x - a.first)
            if (cr > 0) pos = true else if (cr < 0) neg = true
        }
        return !(pos && neg)
    }
}

private fun geometry(c: EditorController, item: RenderItem): Geo? {
    val s = c.session
    val kind = item.trackKind
    if (!kind.hasVisuals) return null
    val measured = c.contentSizes[item.clip.id]
    val (w, h) = measured?.let { it.first.toDouble() to it.second.toDouble() } ?: PreviewMath.estimateContentSize(item.clip, kind, s.canvasWidth)
    val wd = item.world
    fun m(x: Double, y: Double) = wd.mapX(x, y) to wd.mapY(x, y)
    val corners = listOf(m(-w / 2, -h / 2), m(w / 2, -h / 2), m(w / 2, h / 2), m(-w / 2, h / 2))
    val top = m(0.0, -h / 2)
    val ctr = m(0.0, 0.0)
    val dx = top.first - ctr.first; val dy = top.second - ctr.second
    val len = hypot(dx, dy).coerceAtLeast(1e-6)
    val handle = (top.first + dx / len * 60.0) to (top.second + dy / len * 60.0)
    return Geo(corners, ctr, handle)
}

/** Axis-aligned bounds (minX, minY, maxX, maxY) of an item in canvas coordinates, for aligning. */
internal fun clipBounds(c: EditorController, item: RenderItem): DoubleArray? {
    val g = geometry(c, item) ?: return null
    return doubleArrayOf(g.corners.minOf { it.first }, g.corners.minOf { it.second }, g.corners.maxOf { it.first }, g.corners.maxOf { it.second })
}
