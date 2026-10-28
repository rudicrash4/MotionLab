package id.motionlab.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Easing
import id.motionlab.editor.core.EffectPreset
import id.motionlab.editor.core.Effects
import id.motionlab.editor.core.Interp
import id.motionlab.editor.core.Keyframe
import id.motionlab.editor.core.PropDef
import id.motionlab.editor.core.Props
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.ShapeKind
import id.motionlab.editor.core.TimelineController
import id.motionlab.editor.core.Transition
import id.motionlab.editor.core.TransitionType
import id.motionlab.editor.core.TrackType
import java.util.Locale

enum class EditorTab(val label: String) {
    ADD("Tambah"),
    TRANSFORM("Transform"),
    SPEED("Kecepatan"),
    COLOR("Warna"),
    FX("Efek"),
    ANIM("Animasi"),
    TRANS("Transisi"),
    CONTENT("Teks/Bentuk"),
    TRACK("Lacak"),
    LAYER("Layer")
}

private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

// =============================================================== komponen kecil

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) Palette.Accent else Palette.Panel2, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (selected) Color.White else Palette.TextMain, fontSize = 12.sp)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Palette.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

/** Tombol keyframe (belah ketupat): terisi = ada keyframe tepat di playhead, garis = properti beranimasi. */
@Composable
private fun KeyframeButton(active: Boolean, animated: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(34.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.width / 2f - 1f
            val p = Path()
            p.moveTo(c.x, c.y - r); p.lineTo(c.x + r, c.y); p.lineTo(c.x, c.y + r); p.lineTo(c.x - r, c.y); p.close()
            val col = if (animated) Color(0xFFFFD54F) else Palette.TextMuted
            if (active) drawPath(p, col) else drawPath(p, col, style = Stroke(2f))
        }
    }
}

@Composable
private fun PropRow(def: PropDef, clip: Clip, local: Long, controller: TimelineController, labelOverride: String? = null) {
    val value = clip.valueAt(def.key, local)
    val kt = clip.keyframes[def.key]
    val atKey = kt != null && kt.indexAt(local, TimelineController.KEYFRAME_TOLERANCE_MS) >= 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val togglable = def.group == Props.GROUP_COLOR || def.group == Props.GROUP_FX
        val enabled = !clip.fxDisabled.contains(def.key)
        Text(
            (if (togglable) (if (enabled) "● " else "○ ") else "") + (labelOverride ?: def.label),
            color = if (enabled) Palette.TextMain else Palette.TextMuted, fontSize = 12.sp,
            modifier = Modifier.width(96.dp).then(
                if (togglable) Modifier.clickable { controller.toggleFx(clip.id, def.key) } else Modifier
            )
        )
        Slider(
            value = value.coerceIn(def.min, def.max),
            onValueChange = { controller.setProperty(clip.id, def.key, it, local) },
            valueRange = def.min..def.max,
            modifier = Modifier.weight(1f).height(34.dp)
        )
        Text(fmt(value), color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
        KeyframeButton(atKey, clip.hasKeyframes(def.key)) { controller.toggleKeyframe(clip.id, def.key, local) }
    }
}

@Composable
private fun PlainSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Slider(
            value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max,
            modifier = Modifier.weight(1f).height(34.dp)
        )
        Text(fmt(value), color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
        Spacer(Modifier.width(34.dp))
    }
}

private val SWATCHES = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD54F.toInt(), 0xFFFF6B6B.toInt(), 0xFFFF9F43.toInt(),
    0xFF4CD964.toInt(), 0xFF40C4FF.toInt(), 0xFF3C78D8.toInt(), 0xFF8B7CFF.toInt(), 0xFFFF6EC7.toInt()
)

@Composable
private fun SwatchRow(current: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (c in SWATCHES) {
            Box(
                modifier = Modifier.size(30.dp)
                    .background(Color(c), CircleShape)
                    .border(if (c == current) 3.dp else 1.dp, if (c == current) Palette.Accent else Palette.Panel3, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

// =============================================================== panel utama

@Composable
fun InspectorPanel(
    tab: EditorTab,
    vm: EditorViewModel,
    project: Project,
    clip: Clip?,
    playhead: Long,
    onPickMedia: (TrackType) -> Unit,
    onPickLut: () -> Unit,
    onAddText: () -> Unit,
    onPickFont: () -> Unit
) {
    val controller = vm.controller
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)
    ) {
        if (tab == EditorTab.ADD) {
            AddPanel(vm, onPickMedia, onAddText)
            return@Column
        }
        if (clip == null) {
            Hint("Pilih klip di timeline (ketuk) untuk mengedit.")
            return@Column
        }
        val local = (playhead - clip.startMs).coerceIn(0L, clip.durationMs)
        if (playhead < clip.startMs || playhead >= clip.endMs) {
            Text(
                "Playhead di luar klip. Nilai yang ditampilkan = posisi terdekat di dalam klip.",
                color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        when (tab) {
            EditorTab.TRANSFORM -> {
                for (d in Props.group(Props.GROUP_TRANSFORM)) PropRow(d, clip, local, controller)
                if (clip.type == TrackType.VIDEO || clip.type == TrackType.AUDIO) {
                    SectionLabel("MEDIA")
                    PlainSlider("Volume", clip.volume, 0f, 1f) { v ->
                        controller.updateClip(clip.id, "vol:${clip.id}") { it.copy(volume = v) }
                    }
                    val maxFade = (clip.durationMs / 2000f).coerceIn(0.1f, 5f)
                    PlainSlider("Fade in (dtk)", (clip.fadeInMs / 1000f).coerceAtMost(maxFade), 0f, maxFade) { v ->
                        controller.updateClip(clip.id, "fin:${clip.id}") { it.copy(fadeInMs = (v * 1000f).toLong()) }
                    }
                    PlainSlider("Fade out (dtk)", (clip.fadeOutMs / 1000f).coerceAtMost(maxFade), 0f, maxFade) { v ->
                        controller.updateClip(clip.id, "fout:${clip.id}") { it.copy(fadeOutMs = (v * 1000f).toLong()) }
                    }
                }
            }
            EditorTab.COLOR -> {
                if (clip.type == TrackType.AUDIO) { Hint("Audio tidak punya pengaturan warna."); return@Column }
                for (d in Props.group(Props.GROUP_COLOR)) {
                    if (d.key == Props.LUT && clip.lutUri.isEmpty()) continue
                    PropRow(d, clip, local, controller)
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickLut) { Text(if (clip.lutUri.isEmpty()) "Pasang LUT (PNG 512×512)" else "Ganti LUT", fontSize = 12.sp) }
                    if (clip.lutUri.isNotEmpty()) {
                        TextButton(onClick = { controller.updateClip(clip.id) { it.copy(lutUri = "") } }) { Text("Hapus LUT", fontSize = 12.sp) }
                    }
                }
            }
            EditorTab.FX -> {
                if (clip.type == TrackType.AUDIO) { Hint("Audio tidak punya efek visual."); return@Column }
                Hint("Ketuk nama efek (● / ○) untuk menyalakan atau mematikan tanpa kehilangan nilainya.")
                for (d in Props.group(Props.GROUP_FX)) PropRow(d, clip, local, controller)
                FxExtras(vm, clip)
            }
            EditorTab.SPEED -> SpeedPanel(clip, playhead, controller)
            EditorTab.ANIM -> AnimPanel(clip, local, controller, vm)
            EditorTab.TRANS -> TransitionPanel(clip, controller)
            EditorTab.CONTENT -> ContentPanel(clip, controller, onPickFont)
            EditorTab.TRACK -> TrackPanel(vm, clip)
            EditorTab.LAYER -> LayerPanel(vm, project, clip, playhead)
            EditorTab.ADD -> Unit
        }
    }
}

// =============================================================== Tambah

@Composable
private fun AddPanel(vm: EditorViewModel, onPickMedia: (TrackType) -> Unit, onAddText: () -> Unit) {
    SectionLabel("MEDIA (ditambahkan di posisi playhead)")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = { onPickMedia(TrackType.VIDEO) }) { Text("Video") }
        Button(onClick = { onPickMedia(TrackType.IMAGE) }) { Text("Gambar / B-roll") }
        Button(onClick = { onPickMedia(TrackType.AUDIO) }) { Text("Audio") }
        Button(onClick = onAddText) { Text("Teks") }
    }
    SectionLabel("BENTUK / VEKTOR")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (k in ShapeKind.values()) {
            OutlinedButton(onClick = { vm.addShape(k) }) {
                Text(
                    when (k) {
                        ShapeKind.RECT -> "Persegi"
                        ShapeKind.ROUNDED -> "Rounded"
                        ShapeKind.ELLIPSE -> "Elips"
                        ShapeKind.TRIANGLE -> "Segitiga"
                        ShapeKind.STAR -> "Bintang"
                        ShapeKind.LINE -> "Garis"
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

// =============================================================== Animasi / keyframe

@Composable
private fun AnimPanel(clip: Clip, local: Long, controller: TimelineController, vm: EditorViewModel) {
    var propKey by remember { mutableStateOf(Props.X) }
    val def = Props.get(propKey) ?: return
    SectionLabel("PILIH PROPERTI (◆ = sudah beranimasi)")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (d in Props.all) {
            if (d.key == Props.SPEED) continue
            Chip((if (clip.hasKeyframes(d.key)) "◆ " else "") + d.label, d.key == propKey) { propKey = d.key }
        }
    }
    Spacer(Modifier.height(4.dp))
    PropRow(def, clip, local, controller)

    val kt = clip.keyframes[propKey]
    val idx = kt?.indexAt(local, TimelineController.KEYFRAME_TOLERANCE_MS) ?: -1
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = {
            val t = controller.neighborKeyframe(clip.id, propKey, local, -1)
            if (t != null) vm.seekTo(clip.startMs + t)
        }, enabled = kt != null && kt.frames.any { it.timeMs < local - TimelineController.KEYFRAME_TOLERANCE_MS }) {
            Text("◀ Keyframe sebelumnya", fontSize = 12.sp)
        }
        OutlinedButton(onClick = {
            val t = controller.neighborKeyframe(clip.id, propKey, local, 1)
            if (t != null) vm.seekTo(clip.startMs + t)
        }, enabled = kt != null && kt.frames.any { it.timeMs > local + TimelineController.KEYFRAME_TOLERANCE_MS }) {
            Text("Keyframe berikutnya ▶", fontSize = 12.sp)
        }
    }
    Text(
        if (kt == null || kt.frames.isEmpty()) "Belum ada keyframe. Ketuk ◆ untuk menambah di posisi playhead, lalu geser playhead dan ubah nilainya."
        else "${kt.frames.size} keyframe. Mengubah slider saat playhead di antara keyframe akan menambah keyframe baru.",
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    if (kt != null && kt.frames.isNotEmpty()) {
        TextButton(onClick = { controller.removeAllKeyframes(clip.id, propKey, local) }) { Text("Hapus semua keyframe properti ini", fontSize = 12.sp) }
    }

    if (idx >= 0 && kt != null) {
        val k: Keyframe = kt.frames[idx]
        SectionLabel("KURVA ANIMASI (dari keyframe ini ke berikutnya)")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val items = listOf(
                Interp.LINEAR to "Linear", Interp.EASE_IN to "Ease In", Interp.EASE_OUT to "Ease Out",
                Interp.EASE_IN_OUT to "Ease In-Out", Interp.HOLD to "Hold", Interp.CUSTOM to "Custom"
            )
            for ((mode, label) in items) {
                Chip(label, k.interp == mode) { controller.setKeyframeCurve(clip.id, propKey, local, mode, k.bx1, k.by1, k.bx2, k.by2) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CurvePreview(k)
            if (k.interp == Interp.CUSTOM) {
                Column(Modifier.weight(1f)) {
                    PlainSlider("x1", k.bx1, 0f, 1f) { controller.setKeyframeCurve(clip.id, propKey, local, Interp.CUSTOM, it, k.by1, k.bx2, k.by2) }
                    PlainSlider("y1", k.by1, -1f, 2f) { controller.setKeyframeCurve(clip.id, propKey, local, Interp.CUSTOM, k.bx1, it, k.bx2, k.by2) }
                    PlainSlider("x2", k.bx2, 0f, 1f) { controller.setKeyframeCurve(clip.id, propKey, local, Interp.CUSTOM, k.bx1, k.by1, it, k.by2) }
                    PlainSlider("y2", k.by2, -1f, 2f) { controller.setKeyframeCurve(clip.id, propKey, local, Interp.CUSTOM, k.bx1, k.by1, k.bx2, it) }
                }
            }
        }
    } else if (kt != null && kt.frames.isNotEmpty()) {
        Text(
            "Letakkan playhead tepat pada sebuah keyframe (◆ di timeline) untuk mengatur kurvanya.",
            color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun CurvePreview(k: Keyframe) {
    Canvas(modifier = Modifier.size(84.dp).background(Palette.Panel2, RoundedCornerShape(8.dp)).padding(8.dp)) {
        val w = size.width
        val h = size.height
        drawLine(Palette.Panel3, Offset(0f, h), Offset(w, 0f), 1f)
        val p = Path()
        val n = 40
        for (i in 0..n) {
            val x = i / n.toFloat()
            val y = Easing.apply(k, x).coerceIn(-0.5f, 1.5f)
            val px = x * w
            val py = h - y * h
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, Palette.Accent, style = Stroke(3f))
    }
}

// =============================================================== Transisi

private fun transitionLabel(t: TransitionType): String = when (t) {
    TransitionType.NONE -> "Tanpa"
    TransitionType.DISSOLVE -> "Dissolve"
    TransitionType.SLIDE_LEFT -> "Slide ←"
    TransitionType.SLIDE_RIGHT -> "Slide →"
    TransitionType.ZOOM -> "Zoom"
    TransitionType.GLITCH -> "Glitch"
    TransitionType.WIPE -> "Wipe"
}

@Composable
private fun TransitionPanel(clip: Clip, controller: TimelineController) {
    for (isIn in listOf(true, false)) {
        val cur = if (isIn) clip.transitionIn else clip.transitionOut
        SectionLabel(if (isIn) "TRANSISI MASUK (awal klip)" else "TRANSISI KELUAR (akhir klip)")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (t in TransitionType.values()) {
                Chip(transitionLabel(t), cur.type == t) { controller.setTransition(clip.id, isIn, cur.copy(type = t)) }
            }
        }
        if (cur.type != TransitionType.NONE) {
            val maxDur = (clip.durationMs / 2).coerceAtLeast(200L).toFloat()
            PlainSlider("Durasi (dtk)", cur.durationMs / 1000f, 0.1f, maxDur / 1000f) {
                controller.setTransition(clip.id, isIn, Transition(cur.type, (it * 1000f).toLong()))
            }
        }
    }
    Text(
        "Transisi berjalan sebagai animasi masuk/keluar di atas layer di bawahnya. Untuk crossfade antar klip, " +
            "letakkan klip kedua di track lain yang tumpang-tindih lalu beri Dissolve masuk.",
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(12.dp)
    )
}

// =============================================================== Teks / Bentuk

@Composable
private fun ContentPanel(clip: Clip, controller: TimelineController, onPickFont: () -> Unit) {
    when (clip.type) {
        TrackType.TEXT -> {
            var text by remember(clip.id) { mutableStateOf(clip.text) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    controller.updateClip(clip.id, "text:${clip.id}") { it.copy(text = v, name = v.take(18)) }
                },
                label = { Text("Isi teks") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            SectionLabel("WARNA TEKS")
            SwatchRow(clip.textColor) { c -> controller.updateClip(clip.id) { it.copy(textColor = c) } }
            PlainSlider("Ukuran", clip.textSize, 0.02f, 0.25f) { v ->
                controller.updateClip(clip.id, "tsize:${clip.id}") { it.copy(textSize = v) }
            }
            SectionLabel("FONT: " + (if (clip.fontName.isEmpty()) "Bawaan sistem" else clip.fontName))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFont) { Text("Impor font TTF/OTF", fontSize = 12.sp) }
                if (clip.fontUri.isNotEmpty()) {
                    TextButton(onClick = { controller.updateClip(clip.id) { it.copy(fontUri = "", fontName = "") } }) { Text("Font bawaan", fontSize = 12.sp) }
                }
            }
            PlainSlider("Weight", clip.fontWeight.toFloat(), 100f, 900f) { v ->
                controller.updateClip(clip.id, "fw:${clip.id}") { it.copy(fontWeight = (Math.round(v / 100f) * 100).coerceIn(100, 900)) }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Miring", clip.italic) { controller.updateClip(clip.id) { it.copy(italic = !it.italic) } }
                Chip("Kiri", clip.textAlign == 0) { controller.updateClip(clip.id) { it.copy(textAlign = 0) } }
                Chip("Tengah", clip.textAlign == 1) { controller.updateClip(clip.id) { it.copy(textAlign = 1) } }
                Chip("Kanan", clip.textAlign == 2) { controller.updateClip(clip.id) { it.copy(textAlign = 2) } }
            }
            PlainSlider("Jarak huruf", clip.letterSpacing, -0.1f, 0.5f) { v ->
                controller.updateClip(clip.id, "ls:${clip.id}") { it.copy(letterSpacing = v) }
            }
            PlainSlider("Jarak baris", clip.lineSpacing, 0.6f, 2.5f) { v ->
                controller.updateClip(clip.id, "lh:${clip.id}") { it.copy(lineSpacing = v) }
            }
            SectionLabel("OUTLINE (stroke)")
            PlainSlider("Tebal outline", clip.strokeWidth, 0f, 0.2f) { v ->
                controller.updateClip(clip.id, "sw:${clip.id}") { it.copy(strokeWidth = v) }
            }
            if (clip.strokeWidth > 0.001f) SwatchRow(clip.strokeColor) { c -> controller.updateClip(clip.id) { it.copy(strokeColor = c) } }
            SectionLabel("BAYANGAN")
            PlainSlider("Ukuran", clip.shadowSize, 0f, 0.3f) { v ->
                controller.updateClip(clip.id, "sh:${clip.id}") { it.copy(shadowSize = v) }
            }
            if (clip.shadowSize > 0.001f) SwatchRow(clip.shadowColor or 0xFF000000.toInt()) { c ->
                controller.updateClip(clip.id) { it.copy(shadowColor = (c and 0x00FFFFFF) or 0xAA000000.toInt()) }
            }
            SectionLabel("LATAR BELAKANG TEKS")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Tanpa latar", clip.bgColor == 0) { controller.updateClip(clip.id) { it.copy(bgColor = 0) } }
                Chip("Dengan latar", clip.bgColor != 0) {
                    controller.updateClip(clip.id) { if (it.bgColor == 0) it.copy(bgColor = 0xCC000000.toInt()) else it }
                }
            }
            if (clip.bgColor != 0) SwatchRow(clip.bgColor or 0xFF000000.toInt()) { c ->
                controller.updateClip(clip.id) { it.copy(bgColor = (c and 0x00FFFFFF) or 0xCC000000.toInt()) }
            }
        }
        TrackType.SHAPE -> {
            SectionLabel("BENTUK")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (k in ShapeKind.values()) {
                    Chip(k.name.lowercase().replaceFirstChar { it.uppercase() }, clip.shape == k) {
                        controller.updateClip(clip.id) { it.copy(shape = k, name = k.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                    }
                }
            }
            SectionLabel("WARNA ISI")
            SwatchRow(clip.fillColor) { c -> controller.updateClip(clip.id) { it.copy(fillColor = c) } }
        }
        else -> Hint("Panel ini untuk klip Teks dan Bentuk. Gunakan tab Transform / Warna / Efek untuk klip ini.")
    }
}

// =============================================================== Lacak (motion tracking)

@Composable
private fun TrackPanel(vm: EditorViewModel, clip: Clip) {
    val tracking by vm.tracking.collectAsState()
    val overlay by vm.trackOverlay.collectAsState()
    val region by vm.trackRegion.collectAsState()
    if (clip.type == TrackType.VIDEO || clip.type == TrackType.AUDIO) {
        Hint("Pilih klip Teks / Bentuk / Gambar yang ingin mengikuti objek. Video yang dilacak = video yang ada di bawah playhead.")
        return
    }
    Text(
        "1) Geser playhead ke frame awal di atas video.  2) Aktifkan \"Atur area\" lalu seret kotak di preview ke objek.  " +
            "3) Tekan Mulai lacak. Hasilnya berupa keyframe X/Y pada klip terpilih (maks. 15 detik per proses).",
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (overlay) "Area: tampil" else "Atur area di preview", overlay) { vm.setTrackOverlay(!overlay) }
    }
    PlainSlider("Ukuran area", region.size, 0.05f, 0.35f) { vm.setTrackRegion(region.copy(size = it)) }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.startTracking(clip.id) }, enabled = !tracking.running) { Text("Mulai lacak") }
        if (tracking.running) OutlinedButton(onClick = { vm.cancelTracking() }) { Text("Batal") }
    }
    if (tracking.running) {
        LinearProgressIndicator(progress = { tracking.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
    }
    if (tracking.message.isNotEmpty()) {
        Text(tracking.message, color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

// =============================================================== Layer

@Composable
private fun LayerPanel(vm: EditorViewModel, project: Project, clip: Clip, playhead: Long) {
    val controller = vm.controller
    val ti = project.trackIndexOfClip(clip.id)
    val track = project.tracks.getOrNull(ti) ?: return
    SectionLabel("URUTAN LAYER (L${project.tracks.size - ti} dari ${project.tracks.size}, atas = depan)")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { controller.moveTrackOfClip(clip.id, -1) }, enabled = ti > 0) { Text("Naikkan ▲", fontSize = 12.sp) }
        OutlinedButton(onClick = { controller.moveTrackOfClip(clip.id, 1) }, enabled = ti < project.tracks.size - 1) { Text("Turunkan ▼", fontSize = 12.sp) }
    }
    SectionLabel("TRACK: ${track.name}")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Chip("Sembunyikan", track.hidden) { controller.toggleTrackHidden(track.id) }
        if (track.type == TrackType.AUDIO || track.type == TrackType.VIDEO) Chip("Bisukan", track.muted) { controller.toggleTrackMuted(track.id) }
        if (track.type == TrackType.AUDIO || track.type == TrackType.VIDEO) Chip("Solo", track.solo) { controller.toggleTrackSolo(track.id) }
        Chip("Kunci", track.locked) { controller.toggleTrackLocked(track.id) }
    }
    SectionLabel("KLIP")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { vm.splitSelected() }) { Text("Potong di playhead", fontSize = 12.sp) }
        OutlinedButton(onClick = { controller.duplicateClip(clip.id) }) { Text("Duplikat", fontSize = 12.sp) }
        OutlinedButton(onClick = { controller.deleteClip(clip.id) }) { Text("Hapus", fontSize = 12.sp, color = Palette.Danger) }
    }
    Text(
        "Mulai ${fmt(clip.startMs / 1000f)} dtk · durasi ${fmt(clip.durationMs / 1000f)} dtk" +
            (if (clip.sourceDurationMs > 0L) " · sumber ${fmt(clip.sourceDurationMs / 1000f)} dtk" else ""),
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(12.dp)
    )
}


// =============================================================== efek: urutan, preset, salin/tempel

@Composable
private fun FxExtras(vm: EditorViewModel, clip: Clip) {
    val controller = vm.controller
    val clipboard by vm.fxClipboard.collectAsState()
    val names = mapOf(Props.WAVE to "Wave", Props.SWIRL to "Swirl", Props.DISPLACE to "Displacement")
    SectionLabel("URUTAN WARP (diterapkan dari atas ke bawah)")
    val order = clip.warpOrder().map { listOf(Props.WAVE, Props.SWIRL, Props.DISPLACE)[it.toInt()] }
    for ((i, key) in order.withIndex()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. ${names[key]}", color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.width(140.dp))
            TextButton(onClick = { controller.moveWarp(clip.id, key, -1) }, enabled = i > 0) { Text("▲") }
            TextButton(onClick = { controller.moveWarp(clip.id, key, 1) }, enabled = i < order.size - 1) { Text("▼") }
        }
    }
    SectionLabel("PRESET EFEK")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (p in EffectPreset.values()) Chip(p.label, false) { controller.applyEffectPreset(clip.id, p) }
        Chip("Hapus semua", false) { controller.clearEffects(clip.id) }
    }
    SectionLabel("SALIN EFEK ANTAR KLIP")
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.copyEffects(clip.id) }) { Text("Salin efek klip ini", fontSize = 12.sp) }
        OutlinedButton(onClick = { clipboard?.let { controller.applyEffects(clip.id, it) } }, enabled = clipboard != null) {
            Text("Tempel", fontSize = 12.sp)
        }
    }
}
