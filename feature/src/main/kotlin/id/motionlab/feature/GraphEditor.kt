package id.motionlab.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.motionlab.engine.animation.GraphMath
import id.motionlab.engine.animation.GraphPoint
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.PropertyRegistry
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.DeleteKeyframes
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.MoveKeyframes
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetKeyframeInterpolation
import id.motionlab.engine.timeline.SetKeyframeValue
import id.motionlab.engine.timeline.Timecode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Visible window of the graph: time in owner-local microseconds, value in property units. */
private class GraphWindow(val t0: Double, val t1: Double, val v0: Double, val v1: Double) {
    fun x(t: Double, w: Float) = ((t - t0) / (t1 - t0) * w).toFloat()
    fun y(v: Double, h: Float) = (h - (v - v0) / (v1 - v0) * h).toFloat()
    fun t(x: Float, w: Float) = t0 + x / w * (t1 - t0)
    fun v(y: Float, h: Float) = v0 + (h - y) / h * (v1 - v0)
}

private sealed interface GraphHit {
    data class Key(val ref: KeyframeRef) : GraphHit
    data class Handle(val ref: KeyframeRef, val outgoing: Boolean) : GraphHit
    data object None : GraphHit
}

/**
 * Value graph and speed graph of ONE property of the selected clip/layer, drawn from and edited through the same
 * [KeyframeTrack] as the timeline diamonds: dragging a point issues MoveKeyframes / SetKeyframeValue commands, dragging a
 * bezier handle issues SetKeyframeInterpolation. The speed graph is the derivative of the same curve and read-only
 * (move points in time to change it; edit easing in the value graph).
 */
@Composable
fun GraphEditor(c: EditorController, modifier: Modifier = Modifier) {
    val s = c.session
    @Suppress("UNUSED_VARIABLE") val version = c.timeline
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    if (owner == null) {
        Text("Select a clip or layer with keyframes.", modifier.padding(8.dp))
        return
    }
    val anim = s.anim(owner)
    val animated = anim.properties.tracks.filter { !it.value.isEmpty }.keys.toList()
    if (animated.isEmpty()) {
        Text("No keyframes yet. Use the ◇ stopwatch in Properties or apply a preset.", modifier.padding(8.dp))
        return
    }
    var chosen by remember(owner) { mutableStateOf<String?>(null) }
    val selectedProp = c.selection.keyframes.firstOrNull { it.owner == owner }?.property
    val prop = chosen?.takeIf { it in animated } ?: selectedProp?.takeIf { it in animated } ?: animated.first()
    var speedMode by remember { mutableStateOf(false) }
    var window by remember(owner, prop) {
        val tr = anim.properties.track(prop)
        val (ta, tb) = GraphMath.fitTimeRange(tr)
        val (va, vb) = GraphMath.fitValueRange(tr)
        mutableStateOf(GraphWindow(ta.toDouble(), tb.toDouble(), va, vb))
    }

    Column(modifier.fillMaxSize().padding(4.dp).testTag("graph-editor")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            animated.forEach { p ->
                FilterChip(selected = p == prop, onClick = { chosen = p }, label = { Text(PropertyRegistry.get(p)?.displayName ?: p) }, modifier = Modifier.testTag("graph-prop-$p"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !speedMode, onClick = { speedMode = false }, label = { Text("Value") }, modifier = Modifier.testTag("graph-mode-value"))
            FilterChip(selected = speedMode, onClick = { speedMode = true }, label = { Text("Speed") }, modifier = Modifier.testTag("graph-mode-speed"))
            OutlinedButton(onClick = {
                val tr = s.anim(owner).properties.track(prop)
                val (ta, tb) = GraphMath.fitTimeRange(tr)
                val (va, vb) = GraphMath.fitValueRange(tr)
                window = GraphWindow(ta.toDouble(), tb.toDouble(), va, vb)
            }, modifier = Modifier.testTag("graph-fit")) { Text("Fit") }
        }
        GraphCanvas(c, owner, prop, speedMode, window, { window = it }, Modifier.fillMaxWidth().weight(1f))
    }
}

private fun speedRange(track: KeyframeTrack, win: GraphWindow, w: Float): Pair<Double, Double> {
    var m = 1e-6
    val n = max(2, (w / 3).toInt())
    for (i in 0..n) m = max(m, abs(track.speedPerSecond((win.t0 + (win.t1 - win.t0) * i / n).toLong())))
    return -m * 1.15 to m * 1.15
}

@Composable
private fun GraphCanvas(c: EditorController, owner: AnimationOwner, prop: String, speedMode: Boolean, window: GraphWindow, setWindow: (GraphWindow) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val paint = remember(density) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 10.dp.toPx() } } }
    val s = c.session
    // Latest values for the gesture handler (it is keyed on stable objects and must not capture stale ones).
    val latestWindow by rememberUpdatedState(window)
    val latestSpeed by rememberUpdatedState(speedMode)

    Canvas(
        modifier.testTag("graph-canvas").pointerInput(owner, prop) {
            val slop = viewConfiguration.touchSlop
            val hitR = 22.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val speed = latestSpeed
                var win = latestWindow
                if (speed) { val (a, b) = speedRange(s.anim(owner).properties.track(prop), win, w); win = GraphWindow(win.t0, win.t1, a, b) }
                val track0 = s.anim(owner).properties.track(prop)
                val sel0 = c.selection
                fun yOf(k: Keyframe, w2: GraphWindow): Float = if (speed) w2.y(track0.speedPerSecond(k.timeUs), h) else w2.y(k.value, h)

                // --- hit test: handles of the single selected keyframe first, then keyframe points
                var hit: GraphHit = GraphHit.None
                val selectedHere = sel0.keyframes.filter { it.owner == owner && it.property == prop }
                if (!speed && selectedHere.size == 1) {
                    val ref = selectedHere.first()
                    val ks = track0.keyframes
                    val i = ks.indexOfFirst { it.id == ref.keyframeId }
                    if (i >= 0) {
                        val a = ks[i]
                        val ai = a.interpolation
                        val nxt = ks.getOrNull(i + 1)
                        val outH = if (nxt != null && ai is Interpolation.Bezier) GraphMath.handles(a.timeUs, a.value, nxt.timeUs, nxt.value, ai) else null
                        if (outH != null && hypot(win.x(outH.first.timeUs, w) - down.position.x, win.y(outH.first.value, h) - down.position.y) <= hitR) {
                            hit = GraphHit.Handle(ref, true)
                        }
                        val prv = ks.getOrNull(i - 1)
                        val pi = prv?.interpolation
                        val inH = if (prv != null && pi is Interpolation.Bezier) GraphMath.handles(prv.timeUs, prv.value, a.timeUs, a.value, pi) else null
                        if (hit == GraphHit.None && prv != null && inH != null &&
                            hypot(win.x(inH.second.timeUs, w) - down.position.x, win.y(inH.second.value, h) - down.position.y) <= hitR
                        ) {
                            hit = GraphHit.Handle(KeyframeRef(owner, prop, prv.id), false)
                        }
                    }
                }
                if (hit == GraphHit.None) {
                    var best = Float.MAX_VALUE
                    for (k in track0.keyframes) {
                        val d = hypot(win.x(k.timeUs.toDouble(), w) - down.position.x, yOf(k, win) - down.position.y)
                        if (d <= hitR && d < best) { best = d; hit = GraphHit.Key(KeyframeRef(owner, prop, k.id)) }
                    }
                }

                val hitF: GraphHit = hit
                var pinching = false
                var started = false
                var total = Offset.Zero
                var gesture: EditorController.EditGesture? = null
                val moveRefs = if (hitF is GraphHit.Key) (if (hitF.ref in sel0.keyframes) sel0.keyframes.toList() else listOf(hitF.ref)) else emptyList()
                val startKf = (hitF as? GraphHit.Key)?.let { track0.byId(it.ref.keyframeId) }
                val def = PropertyRegistry.get(prop)

                try {
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2) {
                        gesture?.cancel(); gesture = null
                        pinching = true; started = false
                        val z = ev.calculateZoom().toDouble()
                        val pan = ev.calculatePan()
                        val f = ev.calculateCentroid()
                        if (z != 1.0 || pan != Offset.Zero) {
                            val cur = latestWindow
                            val ft = cur.t(f.x, w); val fv = cur.v(f.y, h)
                            val nt0 = ft - (ft - cur.t0) / z - pan.x / w * (cur.t1 - cur.t0) / z
                            val nt1 = nt0 + (cur.t1 - cur.t0) / z
                            val nv0 = fv - (fv - cur.v0) / z + pan.y / h * (cur.v1 - cur.v0) / z
                            val nv1 = nv0 + (cur.v1 - cur.v0) / z
                            if (nt1 - nt0 > 20_000 && nt1 - nt0 < 3.6e9 && nv1 - nv0 > 1e-6) setWindow(GraphWindow(nt0, nt1, nv0, nv1))
                        }
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    if (pinching) { if (pressed == 0) break else continue }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        if (!started) {
                            when (val hh = hitF) {
                                is GraphHit.Key -> {
                                    val sel = c.selection
                                    s.select(if (c.multiSelect) sel.copy(keyframes = if (hh.ref in sel.keyframes) sel.keyframes - hh.ref else sel.keyframes + hh.ref) else Selection(keyframes = setOf(hh.ref)))
                                }
                                is GraphHit.Handle -> Unit
                                GraphHit.None -> c.seek(max(0L, s.baseUs(owner) + win.t(ch.position.x, w).toLong()))
                            }
                        }
                        break
                    }
                    val d = ch.position - ch.previousPosition
                    total += d
                    if (!started && total.getDistance() > slop) {
                        started = true
                        if (hitF !is GraphHit.None) gesture = c.beginGesture()
                        if (hitF is GraphHit.Key && hitF.ref !in c.selection.keyframes) {
                            val sel = c.selection
                            s.select(if (c.multiSelect) sel.copy(keyframes = sel.keyframes + hitF.ref) else Selection(keyframes = setOf(hitF.ref)))
                        }
                    }
                    if (!started) continue
                    when (val hh = hitF) {
                        is GraphHit.None -> {
                            val cur = latestWindow
                            val dt = -d.x / w * (cur.t1 - cur.t0)
                            val dv = d.y / h * (cur.v1 - cur.v0)
                            setWindow(GraphWindow(cur.t0 + dt, cur.t1 + dt, cur.v0 + dv, cur.v1 + dv))
                            ch.consume()
                        }
                        is GraphHit.Key -> {
                            val fps = s.fps
                            var wantedT = (startKf?.timeUs ?: 0L) + (total.x / w * (win.t1 - win.t0)).toLong()
                            wantedT = Timecode.snapToFrame(wantedT, fps)
                            val delta = wantedT - (startKf?.timeUs ?: 0L)
                            val cmds = ArrayList<id.motionlab.engine.timeline.Command>(2)
                            if (delta != 0L) cmds.add(MoveKeyframes(moveRefs, delta))
                            if (!speed && moveRefs.size == 1 && startKf != null) {
                                val v = win.v(ch.position.y, h)
                                cmds.add(SetKeyframeValue(moveRefs.first(), def?.clamp(v) ?: v))
                            }
                            // Absolute edit from the committed keyframes; previewed, recorded once on release.
                            if (cmds.isNotEmpty()) gesture?.update(id.motionlab.engine.timeline.CompositeCommand("Edit keyframe", cmds))
                            ch.consume()
                        }
                        is GraphHit.Handle -> {
                            val tr = track0
                            val ks = tr.keyframes
                            val a = tr.byId(hh.ref.keyframeId)
                            val ai = a?.interpolation
                            // `ref` is always the keyframe that OWNS the segment; `outgoing` says which handle is dragged.
                            val segOwner = a
                            val idx = ks.indexOfFirst { it.id == hh.ref.keyframeId }
                            val b = ks.getOrNull(idx + 1)
                            if (segOwner != null && b != null && ai is Interpolation.Bezier) {
                                val cur = GraphMath.handles(segOwner.timeUs, segOwner.value, b.timeUs, b.value, ai)
                                if (cur != null) {
                                    val p = GraphPoint(win.t(ch.position.x, w), win.v(ch.position.y, h))
                                    val nb = if (hh.outgoing) GraphMath.bezierFromHandles(segOwner.timeUs, segOwner.value, b.timeUs, b.value, p, cur.second)
                                    else GraphMath.bezierFromHandles(segOwner.timeUs, segOwner.value, b.timeUs, b.value, cur.first, p)
                                    if (nb != null) gesture?.update(SetKeyframeInterpolation(listOf(hh.ref), nb))
                                }
                            }
                            ch.consume()
                        }
                    }
                }
                gesture?.commit()
                } finally {
                    gesture?.cancel()
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val track = s.anim(owner).properties.track(prop)
        var win = window
        if (speedMode) { val (a, b) = speedRange(track, win, w); win = GraphWindow(win.t0, win.t1, a, b) }
        drawRect(scheme.surface)
        drawGrid(win, w, h, scheme.outlineVariant, scheme.onSurfaceVariant, paint, speedMode)
        // curve
        val path = Path()
        var first = true
        var px = 0f
        while (px <= w) {
            val t = win.t(px, w).toLong()
            val v = if (speedMode) track.speedPerSecond(t) else track.evaluate(t)
            val y = win.y(v, h).coerceIn(-10000f, 10000f)
            if (first) { path.moveTo(px, y); first = false } else path.lineTo(px, y)
            px += 2f
        }
        drawPath(path, scheme.primary, style = Stroke(3f))
        val sel = c.selection.keyframes
        val ks = track.keyframes
        // bezier handles of the single selected keyframe (value graph only)
        val selHere = sel.filter { it.owner == owner && it.property == prop }
        if (!speedMode && selHere.size == 1) {
            val i = ks.indexOfFirst { it.id == selHere.first().keyframeId }
            fun drawSeg(a: Keyframe?, b: Keyframe?) {
                val ai = a?.interpolation
                if (a == null || b == null || ai !is Interpolation.Bezier) return
                val (h1, h2) = GraphMath.handles(a.timeUs, a.value, b.timeUs, b.value, ai) ?: return
                val col = Color(0xFFFFB74D)
                drawLine(col, Offset(win.x(a.timeUs.toDouble(), w), win.y(a.value, h)), Offset(win.x(h1.timeUs, w), win.y(h1.value, h)), 2f)
                drawLine(col, Offset(win.x(b.timeUs.toDouble(), w), win.y(b.value, h)), Offset(win.x(h2.timeUs, w), win.y(h2.value, h)), 2f)
                drawCircle(col, 7.dp.toPx(), Offset(win.x(h1.timeUs, w), win.y(h1.value, h)))
                drawCircle(col, 7.dp.toPx(), Offset(win.x(h2.timeUs, w), win.y(h2.value, h)))
            }
            if (i >= 0) { drawSeg(ks[i], ks.getOrNull(i + 1)); drawSeg(ks.getOrNull(i - 1), ks[i]) }
        }
        for (k in ks) {
            val x = win.x(k.timeUs.toDouble(), w)
            val y = if (speedMode) win.y(track.speedPerSecond(k.timeUs), h) else win.y(k.value, h)
            val selected = KeyframeRef(owner, prop, k.id) in sel
            drawCircle(if (selected) Color(0xFFFFD54F) else Color.White, 8.dp.toPx(), Offset(x, y))
            drawCircle(Color.Black, 8.dp.toPx(), Offset(x, y), style = Stroke(2f))
        }
        val px0 = win.x(s.localTime(owner).toDouble(), w)
        drawLine(Color(0xFFFF4D4D), Offset(px0, 0f), Offset(px0, h), 2f)
    }
}

private fun DrawScope.drawGrid(win: GraphWindow, w: Float, h: Float, line: Color, text: Color, paint: android.graphics.Paint, speed: Boolean) {
    paint.color = text.toArgb()
    val vStep = niceStep((win.v1 - win.v0) / 5)
    var v = kotlin.math.ceil(win.v0 / vStep) * vStep
    while (v <= win.v1) {
        val y = win.y(v, h)
        drawLine(line, Offset(0f, y), Offset(w, y), 1f)
        drawIntoCanvas { it.nativeCanvas.drawText(formatValue(v, vStep) + if (speed) "/s" else "", 4f, y - 3f, paint) }
        v += vStep
    }
    val tStepUs = niceStep((win.t1 - win.t0) / 6 / 1_000_000.0) * 1_000_000.0
    var t = kotlin.math.ceil(win.t0 / tStepUs) * tStepUs
    while (t <= win.t1) {
        val x = win.x(t, w)
        drawLine(line, Offset(x, 0f), Offset(x, h), 1f)
        drawIntoCanvas { it.nativeCanvas.drawText("%.2fs".format(java.util.Locale.US, t / 1_000_000.0), x + 3f, h - 4f, paint) }
        t += tStepUs
    }
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0.0 || raw.isNaN()) return 1.0
    val pow = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val f = raw / pow
    val n = if (f < 1.5) 1.0 else if (f < 3.5) 2.0 else if (f < 7.5) 5.0 else 10.0
    return n * pow
}
