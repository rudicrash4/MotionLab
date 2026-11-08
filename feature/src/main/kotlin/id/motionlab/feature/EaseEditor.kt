package id.motionlab.feature

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.motionlab.engine.animation.EaseCurves
import id.motionlab.engine.animation.EasePreset
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.animation.PropertyRegistry
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.SetKeyframeInterpolation
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The user's own easing presets. Stored app-wide in SharedPreferences (they are a personal library, not project data) as
 * plain text via [EaseCurves.encodeCustom]; the curves THEMSELVES, once applied, live in the keyframes and so in the project.
 */
class EasePresetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("motionlab.ease", Context.MODE_PRIVATE)
    val custom = mutableStateListOf<EasePreset>().also { it.addAll(EaseCurves.decodeCustom(prefs.getString(KEY, null))) }

    fun add(i: Interpolation.Bezier): EasePreset {
        val p = EasePreset("custom-${System.nanoTime()}", EaseCurves.nextCustomName(EaseCurves.BUILT_IN + custom), i, builtIn = false)
        custom += p
        save()
        return p
    }

    fun remove(id: String) { if (custom.removeAll { it.id == id }) save() }
    private fun save() { prefs.edit().putString(KEY, EaseCurves.encodeCustom(custom)).apply() }

    companion object { private const val KEY = "custom_presets_v1" }
}

private val HandleA = Color(0xFFFFB74D)
private val HandleB = Color(0xFF4FC3F7)

/**
 * ONE easing editor for the selected keyframe interval. Everything it shows and edits is the real [Interpolation] stored on
 * the keyframe: dragging a handle issues [SetKeyframeInterpolation] (previewed live, one undo step on release), the text
 * "cubic-bezier(...)" is formatted from that same value, and the renderer evaluates the very same data.
 */
@Composable
fun EaseTab(c: EditorController, owner: AnimationOwner, prop: String, modifier: Modifier = Modifier) {
    val s = c.session
    @Suppress("UNUSED_VARIABLE") val version = c.timeline
    val track = s.anim(owner).properties.track(prop)
    val selected = c.selection.keyframes.firstOrNull { it.owner == owner && it.property == prop }?.keyframeId
    val seg = EaseCurves.segmentFor(track, selected)
    if (seg == null) {
        Text("Easing needs two keyframes on this property. Add another keyframe to shape the motion between them.", modifier.padding(8.dp).testTag("ease-needs-two"), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val (a, b) = seg
    val interp = a.interpolation
    val store = EasePresetStoreHolder.get(LocalContext.current)
    val presets = EaseCurves.BUILT_IN + store.custom
    val matched = EaseCurves.match(interp, presets)
    val ref = KeyframeRef(owner, prop, a.id)
    val apply: (Interpolation) -> Unit = { i -> c.run { s.execute(SetKeyframeInterpolation(listOf(ref), i)) } }
    val name = PropertyRegistry.get(prop)?.displayName ?: prop

    BoxWithConstraints(modifier.fillMaxWidth().testTag("ease-tab")) {
        val wide = maxWidth >= 380.dp // a side list needs room; on narrow phones the presets become a strip under the graph
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(EaseCurves.css(interp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("ease-css"))
                    Text(
                        "$name  ${fmtS(a.timeUs)} to ${fmtS(b.timeUs)}" + (matched?.let { " · ${it.name}" } ?: " · Custom"),
                        style = MaterialTheme.typography.labelSmall, color = MlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("ease-segment"),
                    )
                }
                val ctl = EaseCurves.controls(interp)
                OutlinedButton(
                    onClick = { ctl?.let { store.add(EaseCurves.clamped(it.x1, it.y1, it.x2, it.y2)) } },
                    enabled = ctl != null && matched?.builtIn != true && (matched == null),
                    modifier = Modifier.testTag("ease-add-preset"),
                ) {
                    MlIcon(MlIcons.Add, null, size = 18.dp)
                    Text(" Save preset", maxLines = 1)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EaseCanvas(c, owner, ref, a.timeUs, b.timeUs, interp, Modifier.weight(1f).height(GRAPH_HEIGHT))
                    PresetList(presets, matched?.id, store, apply, Modifier.width(136.dp).height(GRAPH_HEIGHT))
                }
            } else {
                EaseCanvas(c, owner, ref, a.timeUs, b.timeUs, interp, Modifier.fillMaxWidth().height(GRAPH_HEIGHT))
                PresetStrip(presets, matched?.id, store, apply)
            }
            Text(
                if (interp == Interpolation.Hold) "This segment is a step (Hold). Pick a curve to ease it." else "Drag the two handles to shape the curve. The curve applies to the interval between the selected keyframe and the next one.",
                style = MaterialTheme.typography.bodySmall, color = MlColors.Muted,
            )
        }
    }
}

/** One store per process: tabs are recreated often and the SharedPreferences read should not repeat. */
private object EasePresetStoreHolder {
    private var store: EasePresetStore? = null
    fun get(context: Context): EasePresetStore = store ?: EasePresetStore(context).also { store = it }
}

private val GRAPH_HEIGHT: Dp = 280.dp

private fun fmtS(us: Long) = "%.2fs".format(java.util.Locale.US, us / 1_000_000.0)

@Composable
private fun PresetList(presets: List<EasePreset>, selectedId: String?, store: EasePresetStore, apply: (Interpolation) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).testTag("ease-preset-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.forEach { p -> PresetItem(p, p.id == selectedId, store, apply, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun PresetStrip(presets: List<EasePreset>, selectedId: String?, store: EasePresetStore, apply: (Interpolation) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("ease-preset-list"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { p -> PresetItem(p, p.id == selectedId, store, apply, Modifier.width(112.dp)) }
    }
}

@Composable
private fun PresetItem(p: EasePreset, selected: Boolean, store: EasePresetStore, apply: (Interpolation) -> Unit, modifier: Modifier) {
    val accent = MlColors.Accent
    Row(
        modifier
            .background(if (selected) MlColors.AccentDim.copy(alpha = 0.35f) else MlColors.PanelHigh, RoundedCornerShape(6.dp))
            .border(if (selected) 1.5.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { apply(p.interpolation) }
            .padding(6.dp)
            .semantics { contentDescription = "Easing ${p.name}" + if (selected) ", selected" else "" }
            .testTag("ease-preset-${p.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CurveThumb(p.interpolation, if (selected) accent else MlColors.OnSurface)
        Text(p.name, Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        if (!p.builtIn) MlIconButton(MlIcons.Delete, "Delete preset ${p.name}", "ease-preset-delete-${p.id}", touch = 32.dp) { store.remove(p.id) }
    }
}

@Composable
private fun CurveThumb(i: Interpolation, color: Color) {
    Canvas(Modifier.size(36.dp)) {
        val pad = 4.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val s = EaseCurves.sample(i, 24)
        val lo = min(0.0, s.min()).toFloat()
        val hi = max(1.0, s.max()).toFloat()
        val path = Path()
        s.forEachIndexed { k, v ->
            val x = pad + w * k / (s.size - 1)
            val y = pad + h * (1f - (v.toFloat() - lo) / (hi - lo))
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun EaseCanvas(c: EditorController, owner: AnimationOwner, ref: KeyframeRef, t0: Long, t1: Long, interp: Interpolation, modifier: Modifier) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val paint = remember(density) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 11.dp.toPx() } } }
    val latest by rememberUpdatedState(interp)
    var frozenRange by remember { mutableStateOf<Pair<Float, Float>?>(null) } // y range is fixed while a handle is dragged so the plot does not jump under the finger
    val s = c.session
    val desc = "Easing curve ${EaseCurves.css(interp)}. Drag the two handles to change it."

    fun yRange(i: Interpolation): Pair<Float, Float> {
        val ctl = EaseCurves.controls(i)
        val lo = min(0.0, min(ctl?.y1 ?: 0.0, ctl?.y2 ?: 0.0)) - 0.12
        val hi = max(1.0, max(ctl?.y1 ?: 1.0, ctl?.y2 ?: 1.0)) + 0.12
        return lo.toFloat() to hi.toFloat()
    }

    Canvas(
        modifier.testTag("ease-canvas").semantics { contentDescription = desc }.pointerInput(ref) {
            val hitR = 30.dp.toPx() // generous: a fingertip, not a mouse
            val pad = 22.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val ctl0 = EaseCurves.controls(latest) ?: return@awaitEachGesture
                val (lo, hi) = yRange(latest)
                val left = pad; val top = pad
                val pw = size.width - 2 * pad; val ph = size.height - 2 * pad
                fun px(x: Double) = left + x.toFloat() * pw
                fun py(y: Double) = top + (hi - y.toFloat()) / (hi - lo) * ph
                val d1 = hypot(px(ctl0.x1) - down.position.x, py(ctl0.y1) - down.position.y)
                val d2 = hypot(px(ctl0.x2) - down.position.x, py(ctl0.y2) - down.position.y)
                val which = if (min(d1, d2) > hitR) 0 else if (d1 <= d2) 1 else 2
                if (which == 0) return@awaitEachGesture // not on a handle: leave the touch to the scrolling parent
                down.consume()
                frozenRange = lo to hi
                val gesture = c.beginGesture()
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val x = ((ch.position.x - left) / pw).toDouble().coerceIn(0.0, 1.0)
                        val y = (hi - (ch.position.y - top) / ph * (hi - lo)).toDouble()
                        val next = if (which == 1) EaseCurves.clamped(x, y, ctl0.x2, ctl0.y2) else EaseCurves.clamped(ctl0.x1, ctl0.y1, x, y)
                        gesture.update(SetKeyframeInterpolation(listOf(ref), next))
                        ch.consume()
                    }
                    gesture.commit()
                } finally {
                    gesture.cancel() // no-op after commit; discards the preview if the gesture was interrupted
                    frozenRange = null
                }
            }
        },
    ) {
        val pad = 22.dp.toPx()
        val (lo, hi) = frozenRange ?: yRange(interp)
        val left = pad; val top = pad
        val pw = size.width - 2 * pad; val ph = size.height - 2 * pad
        fun px(x: Double) = left + x.toFloat() * pw
        fun py(y: Double) = top + (hi - y.toFloat()) / (hi - lo) * ph
        drawRect(scheme.surface)
        // grid: quarter lines in x, whole steps in y
        val grid = scheme.outlineVariant
        for (k in 0..4) drawLine(grid, Offset(px(k / 4.0), top), Offset(px(k / 4.0), top + ph), 1f)
        var gy = kotlin.math.ceil(lo.toDouble() * 4) / 4
        while (gy <= hi) { drawLine(grid, Offset(left, py(gy)), Offset(left + pw, py(gy)), 1f); gy += 0.25 }
        // reference guides: the linear diagonal and the centre cross (dashed)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        drawLine(scheme.onSurfaceVariant.copy(alpha = 0.55f), Offset(px(0.0), py(0.0)), Offset(px(1.0), py(1.0)), 1.5f, pathEffect = dash)
        drawLine(scheme.onSurfaceVariant.copy(alpha = 0.35f), Offset(px(0.5), top), Offset(px(0.5), top + ph), 1f, pathEffect = dash)
        drawLine(scheme.onSurfaceVariant.copy(alpha = 0.35f), Offset(left, py(0.5)), Offset(left + pw, py(0.5)), 1f, pathEffect = dash)
        // the 0 and 1 value lines
        drawLine(scheme.onSurfaceVariant, Offset(left, py(0.0)), Offset(left + pw, py(0.0)), 1.5f)
        drawLine(scheme.onSurfaceVariant, Offset(left, py(1.0)), Offset(left + pw, py(1.0)), 1.5f)
        paint.color = scheme.onSurfaceVariant.toArgb()
        drawIntoCanvas { cv ->
            cv.nativeCanvas.drawText("0", left - 14.dp.toPx(), py(0.0) + 4.dp.toPx(), paint)
            cv.nativeCanvas.drawText("1", left - 14.dp.toPx(), py(1.0) + 4.dp.toPx(), paint)
            cv.nativeCanvas.drawText("time", left + pw - 28.dp.toPx(), top + ph + 16.dp.toPx(), paint)
        }
        // the curve: same BezierEasing the renderer uses
        val path = Path()
        val n = 96
        for (k in 0..n) {
            val x = k.toDouble() / n
            val y = py(EaseCurves.valueAt(interp, x)).coerceIn(-5000f, 5000f)
            if (k == 0) path.moveTo(px(x), y) else path.lineTo(px(x), y)
        }
        drawPath(path, scheme.primary, style = Stroke(3.5.dp.toPx()))
        val ctl = EaseCurves.controls(interp)
        if (ctl != null) {
            drawLine(HandleA, Offset(px(0.0), py(0.0)), Offset(px(ctl.x1), py(ctl.y1)), 2.dp.toPx())
            drawLine(HandleB, Offset(px(1.0), py(1.0)), Offset(px(ctl.x2), py(ctl.y2)), 2.dp.toPx())
        }
        // end points
        for ((x, y) in listOf(0.0 to 0.0, 1.0 to 1.0)) {
            drawCircle(Color.White, 6.dp.toPx(), Offset(px(x), py(y)))
            drawCircle(Color.Black, 6.dp.toPx(), Offset(px(x), py(y)), style = Stroke(2f))
        }
        if (ctl != null) {
            drawCircle(HandleA, 11.dp.toPx(), Offset(px(ctl.x1), py(ctl.y1)))
            drawCircle(HandleB, 11.dp.toPx(), Offset(px(ctl.x2), py(ctl.y2)))
            drawCircle(Color.Black, 11.dp.toPx(), Offset(px(ctl.x1), py(ctl.y1)), style = Stroke(2f))
            drawCircle(Color.Black, 11.dp.toPx(), Offset(px(ctl.x2), py(ctl.y2)), style = Stroke(2f))
        }
        // where the playhead is inside this interval
        val local = s.localTime(owner)
        if (local in t0..t1 && t1 > t0) {
            val x = (local - t0).toDouble() / (t1 - t0)
            drawCircle(Color(0xFFFF4D4D), 5.dp.toPx(), Offset(px(x), py(EaseCurves.valueAt(interp, x))))
        }
    }
}

/**
 * Position graph: the path the layer's position takes (X against Y), sampled from the real animated X / Y tracks, with a
 * dot for each keyframe time and the playhead. Tapping the plot moves the playhead to the nearest sampled time. Shown
 * only when position is animated; otherwise it says so instead of drawing a placeholder.
 */
@Composable
fun PositionTab(c: EditorController, owner: AnimationOwner, modifier: Modifier = Modifier) {
    val s = c.session
    @Suppress("UNUSED_VARIABLE") val version = c.timeline
    val props = s.anim(owner).properties
    val tx = props.track(PropertyNames.POSITION_X)
    val ty = props.track(PropertyNames.POSITION_Y)
    if (tx.isEmpty && ty.isEmpty) {
        Text("Position is not animated. Add keyframes to Position X or Y (Transform) to see the path here.", modifier.padding(8.dp).testTag("position-empty"), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val times = (tx.keyframes + ty.keyframes).map { it.timeUs }.distinct().sorted()
    val t0 = times.first(); val t1 = max(times.last(), t0 + 1)
    val n = 120
    val dx = PropertyRegistry.get(PropertyNames.POSITION_X)?.defaultValue ?: 0.0
    val dy = PropertyRegistry.get(PropertyNames.POSITION_Y)?.defaultValue ?: 0.0
    val xs = DoubleArray(n + 1) { tx.evaluate(t0 + (t1 - t0) * it / n, dx) }
    val ys = DoubleArray(n + 1) { ty.evaluate(t0 + (t1 - t0) * it / n, dy) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(4.dp).testTag("position-tab"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Path of the layer position over ${fmtS(t0)} to ${fmtS(t1)}. Tap to move the playhead.", style = MaterialTheme.typography.bodySmall, color = MlColors.Muted)
        Canvas(
            Modifier.fillMaxWidth().height(GRAPH_HEIGHT).testTag("position-canvas").semantics { contentDescription = "Position path graph" }.pointerInput(owner, t0, t1) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = awaitPointerEventUp(down.id) ?: return@awaitEachGesture
                    val pad = 24.dp.toPx()
                    val minX = xs.min(); val maxX = max(xs.max(), minX + 1e-6); val minY = ys.min(); val maxY = max(ys.max(), minY + 1e-6)
                    val sc = min((size.width - 2 * pad) / (maxX - minX).toFloat(), (size.height - 2 * pad) / (maxY - minY).toFloat())
                    var best = 0; var bd = Float.MAX_VALUE
                    for (k in 0..n) {
                        val d = hypot(pad + ((xs[k] - minX) * sc).toFloat() - up.x, pad + ((ys[k] - minY) * sc).toFloat() - up.y)
                        if (d < bd) { bd = d; best = k }
                    }
                    c.seek(max(0L, s.baseUs(owner) + t0 + (t1 - t0) * best / n))
                }
            },
        ) {
            drawRect(scheme.surface)
            val pad = 24.dp.toPx()
            val minX = xs.min(); val maxX = max(xs.max(), minX + 1e-6); val minY = ys.min(); val maxY = max(ys.max(), minY + 1e-6)
            val sc = min((size.width - 2 * pad) / (maxX - minX).toFloat(), (size.height - 2 * pad) / (maxY - minY).toFloat())
            fun p(x: Double, y: Double) = Offset(pad + ((x - minX) * sc).toFloat(), pad + ((y - minY) * sc).toFloat())
            val path = Path()
            for (k in 0..n) { val o = p(xs[k], ys[k]); if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, scheme.primary, style = Stroke(3.dp.toPx()))
            for (t in times) {
                val o = p(tx.evaluate(t, dx), ty.evaluate(t, dy))
                drawCircle(Color.White, 7.dp.toPx(), o); drawCircle(Color.Black, 7.dp.toPx(), o, style = Stroke(2f))
            }
            val lt = s.localTime(owner).coerceIn(t0, t1)
            drawCircle(Color(0xFFFF4D4D), 6.dp.toPx(), p(tx.evaluate(lt, dx), ty.evaluate(lt, dy)))
        }
    }
}

/** Waits for the finger to lift; returns where. Null when the touch was cancelled. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPointerEventUp(id: androidx.compose.ui.input.pointer.PointerId): Offset? {
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == id } ?: return null
        if (!ch.pressed) return ch.position
    }
}
