package id.motionlab.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Interp
import id.motionlab.editor.core.Keyframe
import id.motionlab.editor.core.Props
import id.motionlab.editor.core.SpeedEdit
import id.motionlab.editor.core.SpeedLimits
import id.motionlab.editor.core.SpeedMap
import id.motionlab.editor.core.SpeedPreset
import id.motionlab.editor.core.TimelineController
import id.motionlab.editor.core.TrackType
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private val LOG_MIN: Double = ln(SpeedLimits.MIN.toDouble())
private val LOG_RANGE: Double = ln(SpeedLimits.MAX.toDouble() / SpeedLimits.MIN.toDouble())

private fun speedToFrac(v: Float): Float =
    ((ln(v.toDouble()) - LOG_MIN) / LOG_RANGE).toFloat().coerceIn(0f, 1f)

private fun fracToSpeed(f: Float): Float =
    SpeedMap.clampSpeed(exp(LOG_MIN + f.coerceIn(0f, 1f) * LOG_RANGE).toFloat())

private fun fx(v: Float): String = String.format(Locale.US, "%.2f", v)

private class Plot(val w: Float, val h: Float, pad: Float, val dur: Long) {
    val l = pad
    val r = w - pad
    val t = pad
    val b = h - pad
    fun xOf(ms: Long): Float = l + (r - l) * (ms.toFloat() / dur.toFloat())
    fun yOf(v: Float): Float = b - (b - t) * speedToFrac(v)
    fun msOf(x: Float): Long = (((x - l) / (r - l)).coerceIn(0f, 1f) * dur).toLong()
    fun vOf(y: Float): Float = fracToSpeed((b - y) / (b - t))
}

private val INTERPS = listOf(
    Interp.LINEAR to "Linear",
    Interp.EASE_IN to "Ease In",
    Interp.EASE_OUT to "Ease Out",
    Interp.EASE_IN_OUT to "Ease In-Out",
    Interp.CUSTOM to "Custom"
)

@Composable
fun SpeedPanel(clip: Clip, playhead: Long, controller: TimelineController) {
    if (clip.type != TrackType.VIDEO && clip.type != TrackType.AUDIO) {
        Text("Kecepatan hanya untuk klip video & audio.", color = Palette.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        return
    }
    val frames: List<Keyframe> = clip.keyframes[Props.SPEED]?.frames ?: emptyList()
    var sel by remember(clip.id) { mutableIntStateOf(-1) }
    var notice by remember(clip.id) { mutableStateOf<String?>(null) }
    val selIdx = if (sel in frames.indices) sel else -1
    val base = clip.baseValue(Props.SPEED)

    fun apply(list: List<Keyframe>) {
        val shortened = controller.setSpeedFrames(clip.id, list)
        notice = if (shortened) "Sumber video habis: durasi klip dipendekkan agar kurva muat." else null
    }

    // --- Kecepatan konstan ---
    Text("KECEPATAN KONSTAN (0.1x – 8x)", color = Palette.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (clip.hasSpeedRamp) "Ganti kurva" else "Kecepatan", color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Slider(
            value = speedToFrac(if (clip.hasSpeedRamp) 1f else base),
            onValueChange = { notice = null; controller.setSpeed(clip.id, fracToSpeed(it)) },
            modifier = Modifier.weight(1f).height(34.dp)
        )
        Text(if (clip.hasSpeedRamp) "ramp" else fx(base) + "x", color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.width(48.dp))
    }

    // --- Preset ---
    Text("PRESET (menghasilkan kurva nyata yang bisa diedit)", color = Palette.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (p in SpeedPreset.values()) {
            Chip(p.label, false) {
                val short = controller.applySpeedPreset(clip.id, p)
                notice = if (short) "Sumber video habis: durasi klip dipendekkan agar kurva muat." else null
                sel = -1
            }
        }
    }

    // --- Editor kurva ---
    Text("KURVA (sumbu Y logaritmik; ketuk area kosong = tambah titik, seret titik = ubah waktu & kecepatan)",
        color = Palette.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    CurveEditor(
        clip = clip, frames = frames, base = base, selected = selIdx, playhead = playhead,
        onSelect = { sel = it }, onChange = { apply(it) }
    )

    // --- Info ---
    val dur = max(1L, clip.durationMs)
    val map = clip.speedMap
    val used = map.offset(dur.toDouble())
    Text(
        "Durasi timeline ${fx(dur / 1000f)} dtk · sumber terpakai ${fx((used / 1000.0).toFloat())} dtk · rata-rata ${fx((used / dur).toFloat())}x" +
            (if (clip.sourceDurationMs > 0L) " · sisa sumber ${fx(((clip.sourceDurationMs - clip.sourceEndMs) / 1000f).coerceAtLeast(0f))} dtk" else ""),
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    if (notice != null) {
        Text(notice ?: "", color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }

    // --- Titik terpilih ---
    if (selIdx >= 0) {
        val k = frames[selIdx]
        Text("TITIK ${selIdx + 1} / ${frames.size}", color = Palette.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Waktu", color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(96.dp))
            Slider(
                value = k.timeMs.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = { apply(SpeedEdit.updatePoint(frames, selIdx, timeMs = it.toLong(), maxTimeMs = dur)) },
                valueRange = 0f..dur.toFloat(),
                modifier = Modifier.weight(1f).height(34.dp)
            )
            Text(fx(k.timeMs / 1000f) + "s", color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.width(48.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kecepatan", color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(96.dp))
            Slider(
                value = speedToFrac(k.value),
                onValueChange = { apply(SpeedEdit.updatePoint(frames, selIdx, value = fracToSpeed(it))) },
                modifier = Modifier.weight(1f).height(34.dp)
            )
            Text(fx(k.value) + "x", color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.width(48.dp))
        }
        Text("Interpolasi menuju titik berikutnya", color = Palette.TextMuted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((mode, label) in INTERPS) {
                Chip(label, k.interp == mode) { apply(SpeedEdit.updatePoint(frames, selIdx, interp = mode)) }
            }
        }
        if (k.interp == Interp.CUSTOM) {
            val bz = floatArrayOf(k.bx1, k.by1, k.bx2, k.by2)
            val names = listOf("x1", "y1", "x2", "y2")
            for (i in 0 until 4) {
                val isX = i % 2 == 0
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bezier " + names[i], color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                    Slider(
                        value = bz[i].coerceIn(if (isX) 0f else -0.5f, if (isX) 1f else 1.5f),
                        onValueChange = {
                            val nb = bz.copyOf()
                            nb[i] = it
                            apply(SpeedEdit.updatePoint(frames, selIdx, bezier = nb))
                        },
                        valueRange = (if (isX) 0f else -0.5f)..(if (isX) 1f else 1.5f),
                        modifier = Modifier.weight(1f).height(34.dp)
                    )
                    Text(fx(bz[i]), color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                }
            }
        }
    }

    // --- Aksi ---
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = {
            val local = (playhead - clip.startMs).coerceIn(0L, clip.durationMs)
            val start = if (frames.isEmpty()) listOf(Keyframe(0L, base), Keyframe(clip.durationMs, base)) else frames
            val list = SpeedEdit.addPoint(start, local, base)
            apply(list)
            sel = list.indexOfFirst { Math.abs(it.timeMs - local) < 2L }
        }) { Text("Titik di playhead", fontSize = 12.sp) }
        OutlinedButton(
            enabled = selIdx >= 0,
            onClick = { apply(SpeedEdit.removePoint(frames, selIdx)); sel = -1 }
        ) { Text("Hapus titik", fontSize = 12.sp) }
        OutlinedButton(onClick = {
            val short = controller.resetSpeed(clip.id)
            notice = if (short) "Sumber video habis: durasi klip dipendekkan." else null
            sel = -1
        }) { Text("Reset 1x", fontSize = 12.sp) }
    }
    Text(
        "Audio di preview mengikuti kurva dengan pitch dipertahankan (time-stretch bawaan pemutar).",
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun CurveEditor(
    clip: Clip, frames: List<Keyframe>, base: Float, selected: Int, playhead: Long,
    onSelect: (Int) -> Unit, onChange: (List<Keyframe>) -> Unit
) {
    val clipState by rememberUpdatedState(clip)
    val onSelectState by rememberUpdatedState(onSelect)
    val onChangeState by rememberUpdatedState(onChange)
    val dens = LocalDensity.current
    val padPx = with(dens) { 16.dp.toPx() }
    val hitPx = with(dens) { 28.dp.toPx() }
    val dotPx = with(dens) { 6.dp.toPx() }
    val dur = max(1L, clip.durationMs)
    val map = clip.speedMap
    val phLocal = playhead - clip.startMs

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(210.dp)
            .background(Palette.Panel2, RoundedCornerShape(8.dp))
            .pointerInput(clip.id) {
                detectTapGestures { pos ->
                    val c = clipState
                    val fr = c.keyframes[Props.SPEED]?.frames ?: emptyList()
                    val plot = Plot(size.width.toFloat(), size.height.toFloat(), padPx, max(1L, c.durationMs))
                    var best = -1
                    var bestD = hitPx
                    for ((i, k) in fr.withIndex()) {
                        val dx = plot.xOf(k.timeMs) - pos.x
                        val dy = plot.yOf(k.value) - pos.y
                        val d = sqrt(dx * dx + dy * dy)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    if (best >= 0) {
                        onSelectState(best)
                    } else {
                        val b0 = c.baseValue(Props.SPEED)
                        val start = if (fr.isEmpty()) listOf(Keyframe(0L, b0), Keyframe(c.durationMs, b0)) else fr
                        val ms = plot.msOf(pos.x)
                        val list = SpeedEdit.addPoint(start, ms, b0, plot.vOf(pos.y))
                        onChangeState(list)
                        onSelectState(list.indexOfFirst { Math.abs(it.timeMs - ms) < 2L })
                    }
                }
            }
            .pointerInput(clip.id) {
                var dragIdx = -1
                detectDragGestures(
                    onDragStart = { pos ->
                        val c = clipState
                        val fr = c.keyframes[Props.SPEED]?.frames ?: emptyList()
                        val plot = Plot(size.width.toFloat(), size.height.toFloat(), padPx, max(1L, c.durationMs))
                        var best = -1
                        var bestD = hitPx
                        for ((i, k) in fr.withIndex()) {
                            val dx = plot.xOf(k.timeMs) - pos.x
                            val dy = plot.yOf(k.value) - pos.y
                            val d = sqrt(dx * dx + dy * dy)
                            if (d < bestD) { bestD = d; best = i }
                        }
                        dragIdx = best
                        if (best >= 0) onSelectState(best)
                    },
                    onDragEnd = { dragIdx = -1 },
                    onDragCancel = { dragIdx = -1 }
                ) { change, _ ->
                    if (dragIdx >= 0) {
                        change.consume()
                        val c = clipState
                        val fr = c.keyframes[Props.SPEED]?.frames ?: emptyList()
                        val plot = Plot(size.width.toFloat(), size.height.toFloat(), padPx, max(1L, c.durationMs))
                        val p = change.position
                        onChangeState(
                            SpeedEdit.updatePoint(fr, dragIdx, timeMs = plot.msOf(p.x), value = plot.vOf(p.y), maxTimeMs = c.durationMs)
                        )
                    }
                }
            }
    ) {
        val plot = Plot(size.width, size.height, padPx, dur)
        val paint = android.graphics.Paint().apply {
            color = 0xFF9BA3B7.toInt()
            textSize = 10f * dens.density
            isAntiAlias = true
        }
        // Garis bantu kecepatan.
        for (g in floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 8f)) {
            val y = plot.yOf(g)
            val major = g == 1f
            drawLine(
                if (major) Color(0x66FFFFFF) else Color(0x22FFFFFF),
                Offset(plot.l, y), Offset(plot.r, y), strokeWidth = if (major) 2f else 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                (if (g < 1f) fx(g) else g.toInt().toString()) + "x", plot.l + 4f, y - 3f, paint
            )
        }
        // Kurva.
        val path = Path()
        val n = 160
        for (i in 0..n) {
            val ms = dur.toDouble() * i / n
            val x = plot.xOf(ms.toLong())
            val y = plot.yOf(map.speedAt(ms))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Palette.Accent, style = Stroke(width = 4f))
        // Playhead.
        if (phLocal in 0..dur) {
            val x = plot.xOf(phLocal)
            drawLine(Color(0xFFFFD54F), Offset(x, plot.t), Offset(x, plot.b), strokeWidth = 2f)
        }
        // Titik.
        for ((i, k) in frames.withIndex()) {
            val c = Offset(plot.xOf(k.timeMs), plot.yOf(k.value))
            if (i == selected) drawCircle(Color.White, dotPx * 1.8f, c)
            drawCircle(if (i == selected) Palette.Accent else Color(0xFFFFD54F), dotPx * 1.3f, c)
        }
    }
}
