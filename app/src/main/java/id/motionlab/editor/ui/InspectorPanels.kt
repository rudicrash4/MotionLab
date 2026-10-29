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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import id.motionlab.editor.ui.components.DiamondKeyframeButton
import id.motionlab.editor.ui.components.RulerSlider
import java.util.Locale

enum class EditorTab(val label: String) {
    TRANSFORM("Transform"),
    AUDIO("Audio"),
    SPEED("Kecepatan"),
    COLOR("Warna"),
    FX("Efek"),
    ANIM("Animasi"),
    TRANS("Transisi"),
    CONTENT("Konten"),
    TRACK("Lacak"),
    LAYER("Layer")
}

private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

// =============================================================== komponen pembantu

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) Palette.AccentGreen.copy(alpha = 0.25f) else Palette.Panel2, RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Palette.AccentGreen else Palette.Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Palette.AccentGreen else Palette.TextMain,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Palette.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Menghubungkan PropDef dengan RulerSlider */
@Composable
private fun PropRuler(
    def: PropDef,
    clip: Clip,
    local: Long,
    controller: TimelineController,
    labelOverride: String? = null
) {
    val value = clip.valueAt(def.key, local)
    val kt = clip.keyframes[def.key]
    val atKey = kt != null && kt.indexAt(local, TimelineController.KEYFRAME_TOLERANCE_MS) >= 0

    val (displayMul, unit, decimals, majorInt, minorInt) = when (def.key) {
        Props.X, Props.Y -> Tuple5(1f, "", 2, 0.5f, 0.1f)
        Props.SCALE, Props.STRETCH -> Tuple5(1f, "x", 2, 0.5f, 0.1f)
        Props.ROTATION -> Tuple5(1f, "°", 0, 45f, 15f)
        Props.OPACITY -> Tuple5(100f, "%", 0, 0.2f, 0.05f)
        Props.SPEED -> Tuple5(1f, "x", 2, 1f, 0.2f)
        else -> Tuple5(1f, "", 2, 0.5f, 0.1f)
    }

    val togglable = def.group == Props.GROUP_COLOR || def.group == Props.GROUP_FX
    val enabled = !clip.fxDisabled.contains(def.key)

    val label = (if (togglable) (if (enabled) "● " else "○ ") else "") + (labelOverride ?: def.label)

    RulerSlider(
        label = label,
        value = value,
        onValueChange = { newVal ->
            controller.setProperty(clip.id, def.key, newVal, local)
        },
        min = def.min,
        max = def.max,
        step = if (decimals == 0) 1f else 0.01f,
        displayMultiplier = displayMul,
        unit = unit,
        decimals = decimals,
        majorInterval = majorInt,
        minorInterval = minorInt,
        keyframeActive = atKey,
        hasKeyframes = clip.hasKeyframes(def.key),
        onKeyframeClick = {
            controller.toggleKeyframe(clip.id, def.key, local)
        },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

private data class Tuple5(
    val mul: Float,
    val unit: String,
    val dec: Int,
    val major: Float,
    val minor: Float
)

private val SWATCHES = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD54F.toInt(), 0xFFFF5252.toInt(), 0xFFFF9F1C.toInt(),
    0xFF00DF81.toInt(), 0xFF00E5FF.toInt(), 0xFF00B4D8.toInt(), 0xFF9D4EDD.toInt(), 0xFFFF5C5C.toInt()
)

@Composable
private fun SwatchRow(current: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (c in SWATCHES) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color(c), CircleShape)
                    .border(
                        if (c == current) 3.dp else 1.dp,
                        if (c == current) Palette.AccentGreen else Palette.Border,
                        CircleShape
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

// =============================================================== Panel Utama

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
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        if (clip == null) {
            Hint("Pilih layer di timeline (ketuk klip) untuk melihat dan mengedit properti.")
            return@Column
        }

        val local = (playhead - clip.startMs).coerceIn(0L, clip.durationMs)
        if (playhead < clip.startMs || playhead >= clip.endMs) {
            Text(
                "Playhead berada di luar durasi klip ini.",
                color = Palette.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        when (tab) {
            EditorTab.TRANSFORM -> {
                TransformPanel(clip, local, controller)
            }
            EditorTab.AUDIO -> {
                AudioPanel(clip, local, controller)
            }
            EditorTab.COLOR -> {
                if (clip.type == TrackType.AUDIO) { Hint("Audio tidak memiliki pengaturan warna."); return@Column }
                for (d in Props.group(Props.GROUP_COLOR)) {
                    if (d.key == Props.LUT && clip.lutUri.isEmpty()) continue
                    PropRuler(d, clip, local, controller)
                }
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onPickLut) {
                        Text(if (clip.lutUri.isEmpty()) "Pasang LUT (.PNG 512×512)" else "Ganti LUT", fontSize = 12.sp)
                    }
                    if (clip.lutUri.isNotEmpty()) {
                        TextButton(onClick = { controller.updateClip(clip.id) { it.copy(lutUri = "") } }) {
                            Text("Hapus LUT", fontSize = 12.sp, color = Palette.Danger)
                        }
                    }
                }
            }
            EditorTab.FX -> {
                if (clip.type == TrackType.AUDIO) { Hint("Audio tidak memiliki efek visual shader."); return@Column }
                Hint("Ketuk (● / ○) untuk mengaktifkan atau menonaktifkan efek tanpa menghapus nilainya.")
                for (d in Props.group(Props.GROUP_FX)) PropRuler(d, clip, local, controller)
                FxExtras(vm, clip)
            }
            EditorTab.SPEED -> SpeedPanel(clip, playhead, controller)
            EditorTab.ANIM -> AnimPanel(clip, local, controller, vm)
            EditorTab.TRANS -> TransitionPanel(clip, controller)
            EditorTab.CONTENT -> ContentPanel(clip, controller, onPickFont)
            EditorTab.TRACK -> TrackPanel(vm, clip)
            EditorTab.LAYER -> LayerPanel(vm, project, clip, playhead)
        }
    }
}

// =============================================================== Transform Panel

@Composable
private fun TransformPanel(clip: Clip, local: Long, controller: TimelineController) {
    var lockRatio by remember { mutableStateOf(true) }

    SectionLabel("POSISI")
    Props.get(Props.X)?.let { PropRuler(it, clip, local, controller, "Posisi X") }
    Props.get(Props.Y)?.let { PropRuler(it, clip, local, controller, "Posisi Y") }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionLabel("SKALA")
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .background(Palette.Panel2, RoundedCornerShape(6.dp))
                .border(1.dp, if (lockRatio) Palette.AccentGreen else Palette.Border, RoundedCornerShape(6.dp))
                .clickable { lockRatio = !lockRatio }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (lockRatio) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = "Lock Ratio",
                tint = if (lockRatio) Palette.AccentGreen else Palette.TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (lockRatio) "Rasio Terkunci" else "Rasio Bebas",
                fontSize = 11.sp,
                color = if (lockRatio) Palette.AccentGreen else Palette.TextMuted
            )
        }
    }

    val curScale = clip.valueAt(Props.SCALE, local)
    val curStretch = clip.valueAt(Props.STRETCH, local)
    val atKeyScale = clip.keyframes[Props.SCALE]?.indexAt(local, TimelineController.KEYFRAME_TOLERANCE_MS)?.let { it >= 0 } ?: false

    RulerSlider(
        label = "Skala X",
        value = curScale * curStretch,
        onValueChange = { newScaleX ->
            if (lockRatio) {
                val factor = (newScaleX / (curScale * curStretch).coerceAtLeast(0.01f))
                controller.setProperty(clip.id, Props.SCALE, (curScale * factor).coerceIn(0.05f, 5f), local)
            } else {
                val newStretch = (newScaleX / curScale.coerceAtLeast(0.01f)).coerceIn(0.1f, 4f)
                controller.setProperty(clip.id, Props.STRETCH, newStretch, local)
            }
        },
        min = 0.05f,
        max = 5f,
        step = 0.01f,
        unit = "x",
        decimals = 2,
        majorInterval = 0.5f,
        minorInterval = 0.1f,
        keyframeActive = atKeyScale,
        hasKeyframes = clip.hasKeyframes(Props.SCALE),
        onKeyframeClick = { controller.toggleKeyframe(clip.id, Props.SCALE, local) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )

    RulerSlider(
        label = "Skala Y",
        value = curScale,
        onValueChange = { newScaleY ->
            controller.setProperty(clip.id, Props.SCALE, newScaleY.coerceIn(0.05f, 5f), local)
        },
        min = 0.05f,
        max = 5f,
        step = 0.01f,
        unit = "x",
        decimals = 2,
        majorInterval = 0.5f,
        minorInterval = 0.1f,
        keyframeActive = atKeyScale,
        hasKeyframes = clip.hasKeyframes(Props.SCALE),
        onKeyframeClick = { controller.toggleKeyframe(clip.id, Props.SCALE, local) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )

    Spacer(Modifier.height(4.dp))
    SectionLabel("ROTASI & OPACITY")
    Props.get(Props.ROTATION)?.let { PropRuler(it, clip, local, controller, "Rotasi") }
    Props.get(Props.OPACITY)?.let { PropRuler(it, clip, local, controller, "Opacity") }

    // Video Playback Controls (Reverse)
    if (clip.type == TrackType.VIDEO) {
        Spacer(Modifier.height(6.dp))
        SectionLabel("PEMUTARAN VIDEO")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { controller.toggleClipReversed(clip.id) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (clip.isReversed) Palette.AccentGreen else Palette.Panel2,
                    contentColor = if (clip.isReversed) Color.Black else Palette.TextMain
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, if (clip.isReversed) Palette.AccentGreen else Palette.Border, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    if (clip.isReversed) Icons.Filled.FastRewind else Icons.Filled.PlayArrow,
                    contentDescription = "Reverse Playback",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (clip.isReversed) "Putar Mundur (Aktif)" else "Putar Mundur (Reverse)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Audio controls for video/audio clips
    if (clip.type == TrackType.VIDEO || clip.type == TrackType.AUDIO) {
        Spacer(Modifier.height(6.dp))
        SectionLabel("AUDIO KLIP")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute toggle button
            IconButton(
                onClick = { controller.toggleClipMuted(clip.id) },
                modifier = Modifier
                    .background(if (clip.isMuted) Palette.Danger.copy(alpha = 0.2f) else Palette.Panel2, RoundedCornerShape(8.dp))
                    .border(1.dp, if (clip.isMuted) Palette.Danger else Palette.Border, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    if (clip.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (clip.isMuted) "Bisu" else "Suara Aktif",
                    tint = if (clip.isMuted) Palette.Danger else Palette.AccentGreen
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (clip.isMuted) "Audio Dibisukan (Muted)" else "Audio Aktif",
                fontSize = 12.sp,
                color = if (clip.isMuted) Palette.Danger else Palette.TextMain
            )
        }

        RulerSlider(
            label = "Volume Klip",
            value = clip.volume,
            onValueChange = { v ->
                controller.setClipVolume(clip.id, v)
            },
            min = 0f,
            max = 2f,
            step = 0.01f,
            displayMultiplier = 100f,
            unit = "%",
            decimals = 0,
            majorInterval = 0.25f,
            minorInterval = 0.05f,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// =============================================================== Audio Panel

@Composable
private fun AudioPanel(clip: Clip, local: Long, controller: TimelineController) {
    if (clip.type != TrackType.AUDIO && clip.type != TrackType.VIDEO) {
        Hint("Objek ini tidak memiliki saluran audio.")
        return
    }

    SectionLabel("KONTROL SUARA")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { controller.toggleClipMuted(clip.id) },
            modifier = Modifier
                .background(if (clip.isMuted) Palette.Danger.copy(alpha = 0.2f) else Palette.Panel2, RoundedCornerShape(8.dp))
                .border(1.dp, if (clip.isMuted) Palette.Danger else Palette.Border, RoundedCornerShape(8.dp))
        ) {
            Icon(
                if (clip.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (clip.isMuted) "Bisu" else "Suara Aktif",
                tint = if (clip.isMuted) Palette.Danger else Palette.AccentGreen
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                if (clip.isMuted) "Audio Klip Dibisukan" else "Audio Klip Bersuara",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (clip.isMuted) Palette.Danger else Palette.TextMain
            )
            Text(
                "Nilai volume independen untuk klip ini",
                fontSize = 11.sp,
                color = Palette.TextMuted
            )
        }
    }

    RulerSlider(
        label = "Volume Klip",
        value = clip.volume,
        onValueChange = { v -> controller.setClipVolume(clip.id, v) },
        min = 0f,
        max = 2f,
        step = 0.01f,
        displayMultiplier = 100f,
        unit = "%",
        decimals = 0,
        majorInterval = 0.25f,
        minorInterval = 0.05f,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )

    Spacer(Modifier.height(6.dp))
    SectionLabel("FADE TRANSISI AUDIO")
    val maxFade = (clip.durationMs / 2000f).coerceIn(0.1f, 5f)

    RulerSlider(
        label = "Fade In (Masuk)",
        value = clip.fadeInMs / 1000f,
        onValueChange = { v ->
            controller.updateClip(clip.id, "fin:${clip.id}") { it.copy(fadeInMs = (v * 1000f).toLong()) }
        },
        min = 0f,
        max = maxFade,
        step = 0.05f,
        unit = " dtk",
        decimals = 2,
        majorInterval = 0.5f,
        minorInterval = 0.1f,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )

    RulerSlider(
        label = "Fade Out (Keluar)",
        value = clip.fadeOutMs / 1000f,
        onValueChange = { v ->
            controller.updateClip(clip.id, "fout:${clip.id}") { it.copy(fadeOutMs = (v * 1000f).toLong()) }
        },
        min = 0f,
        max = maxFade,
        step = 0.05f,
        unit = " dtk",
        decimals = 2,
        majorInterval = 0.5f,
        minorInterval = 0.1f,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

// =============================================================== Animasi / Keyframe

@Composable
private fun AnimPanel(clip: Clip, local: Long, controller: TimelineController, vm: EditorViewModel) {
    var propKey by remember { mutableStateOf(Props.X) }
    val def = Props.get(propKey) ?: return
    SectionLabel("PILIH PROPERTI UNTUK ANIMASI")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (d in Props.all) {
            if (d.key == Props.SPEED) continue
            Chip((if (clip.hasKeyframes(d.key)) "◆ " else "") + d.label, d.key == propKey) { propKey = d.key }
        }
    }
    Spacer(Modifier.height(6.dp))
    PropRuler(def, clip, local, controller)

    val kt = clip.keyframes[propKey]
    val idx = kt?.indexAt(local, TimelineController.KEYFRAME_TOLERANCE_MS) ?: -1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = {
            val t = controller.neighborKeyframe(clip.id, propKey, local, -1)
            if (t != null) vm.seekTo(clip.startMs + t)
        }, enabled = kt != null && kt.frames.any { it.timeMs < local - TimelineController.KEYFRAME_TOLERANCE_MS }) {
            Text("◀ Sebelumnya", fontSize = 12.sp)
        }
        OutlinedButton(onClick = {
            val t = controller.neighborKeyframe(clip.id, propKey, local, 1)
            if (t != null) vm.seekTo(clip.startMs + t)
        }, enabled = kt != null && kt.frames.any { it.timeMs > local + TimelineController.KEYFRAME_TOLERANCE_MS }) {
            Text("Berikutnya ▶", fontSize = 12.sp)
        }
    }
    Text(
        if (kt == null || kt.frames.isEmpty()) "Ketuk diamond (◆) di kanan penggaris untuk menambah keyframe."
        else "${kt.frames.size} keyframe tersimpan. Menggeser penggaris saat playhead di antara keyframe akan menambah keyframe.",
        color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    if (kt != null && kt.frames.isNotEmpty()) {
        TextButton(onClick = { controller.removeAllKeyframes(clip.id, propKey, local) }) {
            Text("Hapus semua keyframe properti ini", fontSize = 12.sp, color = Palette.Danger)
        }
    }

    if (idx >= 0 && kt != null) {
        val k: Keyframe = kt.frames[idx]
        SectionLabel("KURVA INTERPOLASI KEYFRAME")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val items = listOf(
                Interp.LINEAR to "Linear", Interp.EASE_IN to "Ease In", Interp.EASE_OUT to "Ease Out",
                Interp.EASE_IN_OUT to "Ease In-Out", Interp.HOLD to "Hold", Interp.CUSTOM to "Custom"
            )
            for ((mode, label) in items) {
                Chip(label, k.interp == mode) {
                    controller.setKeyframeCurve(clip.id, propKey, local, mode, k.bx1, k.by1, k.bx2, k.by2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CurvePreview(k)
        }
    }
}

@Composable
private fun CurvePreview(k: Keyframe) {
    Canvas(
        modifier = Modifier
            .size(84.dp)
            .background(Palette.Panel2, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
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
        drawPath(p, Palette.AccentGreen, style = Stroke(3f))
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
            RulerSlider(
                label = "Durasi Transisi",
                value = cur.durationMs / 1000f,
                onValueChange = { controller.setTransition(clip.id, isIn, Transition(cur.type, (it * 1000f).toLong())) },
                min = 0.1f,
                max = maxDur / 1000f,
                step = 0.05f,
                unit = " dtk",
                decimals = 2,
                majorInterval = 0.5f,
                minorInterval = 0.1f,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

// =============================================================== Teks & Bentuk

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
                label = { Text("Isi Teks") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            SectionLabel("WARNA TEKS")
            SwatchRow(clip.textColor) { c -> controller.updateClip(clip.id) { it.copy(textColor = c) } }

            RulerSlider(
                label = "Ukuran Huruf",
                value = clip.textSize,
                onValueChange = { v -> controller.updateClip(clip.id, "tsize:${clip.id}") { it.copy(textSize = v) } },
                min = 0.02f,
                max = 0.35f,
                step = 0.01f,
                displayMultiplier = 100f,
                unit = " pt",
                decimals = 1,
                majorInterval = 0.05f,
                minorInterval = 0.01f,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )

            SectionLabel("FONT: " + (if (clip.fontName.isEmpty()) "Bawaan sistem" else clip.fontName))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFont) { Text("Impor Font TTF/OTF", fontSize = 12.sp) }
                if (clip.fontUri.isNotEmpty()) {
                    TextButton(onClick = { controller.updateClip(clip.id) { it.copy(fontUri = "", fontName = "") } }) {
                        Text("Reset Bawaan", fontSize = 12.sp)
                    }
                }
            }

            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Miring", clip.italic) { controller.updateClip(clip.id) { it.copy(italic = !it.italic) } }
                Chip("Rata Kiri", clip.textAlign == 0) { controller.updateClip(clip.id) { it.copy(textAlign = 0) } }
                Chip("Rata Tengah", clip.textAlign == 1) { controller.updateClip(clip.id) { it.copy(textAlign = 1) } }
                Chip("Rata Kanan", clip.textAlign == 2) { controller.updateClip(clip.id) { it.copy(textAlign = 2) } }
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
        else -> {
            Hint("Objek ini tidak memiliki pengaturan konten khusus.")
        }
    }
}

// =============================================================== Track & Layer

@Composable
private fun TrackPanel(vm: EditorViewModel, clip: Clip) {
    val tracking by vm.tracking.collectAsState()
    val region by vm.trackRegion.collectAsState()
    val overlayOn by vm.trackOverlay.collectAsState()

    SectionLabel("PELACAKAN GERAKAN (Motion Tracking)")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Chip("Kotak Target", overlayOn) { vm.setTrackOverlay(!overlayOn) }
        if (!tracking.running) {
            Button(
                onClick = { vm.startTracking(clip.id) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.AccentGreen)
            ) {
                Text("Lacak Maju ▶", color = Color.Black, fontSize = 12.sp)
            }
        } else {
            Button(
                onClick = { vm.cancelTracking() },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger)
            ) {
                Text("Batal", fontSize = 12.sp)
            }
        }
    }
    if (tracking.running) {
        LinearProgressIndicator(progress = { tracking.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
    }
    if (tracking.message.isNotEmpty()) {
        Text(tracking.message, color = Palette.TextMain, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun LayerPanel(vm: EditorViewModel, project: Project, clip: Clip, playhead: Long) {
    val controller = vm.controller
    val ti = project.trackIndexOfClip(clip.id)
    val track = project.tracks.getOrNull(ti) ?: return

    SectionLabel("SUSUNAN LAYER (Layer ${project.tracks.size - ti} dari ${project.tracks.size})")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { controller.reorderTrack(ti, (ti - 1).coerceAtLeast(0)) }, enabled = ti > 0) {
            Text("Naikkan ▲", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { controller.reorderTrack(ti, (ti + 1).coerceAtMost(project.tracks.size - 1)) }, enabled = ti < project.tracks.size - 1) {
            Text("Turunkan ▼", fontSize = 12.sp)
        }
    }

    SectionLabel("KONTROL STATUS LAYER")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Chip(if (track.hidden) "Tersembunyi" else "Terlihat", track.hidden) { controller.toggleTrackHidden(track.id) }
        Chip(if (track.locked) "Terkunci" else "Terbuka", track.locked) { controller.toggleTrackLocked(track.id) }
        if (track.type == TrackType.AUDIO || track.type == TrackType.VIDEO) {
            Chip(if (track.muted) "Muted" else "Bersuara", track.muted) { controller.toggleTrackMuted(track.id) }
        }
    }
}

@Composable
private fun FxExtras(vm: EditorViewModel, clip: Clip) {
    val controller = vm.controller
    val clipboard by vm.fxClipboard.collectAsState()
    SectionLabel("PRESET EFEK CEPAT")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (p in EffectPreset.values()) Chip(p.label, false) { controller.applyEffectPreset(clip.id, p) }
        Chip("Reset Semua", false) { controller.clearEffects(clip.id) }
    }
    SectionLabel("SALIN EFEK")
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.copyEffects(clip.id) }) { Text("Salin Efek", fontSize = 12.sp) }
        OutlinedButton(onClick = { clipboard?.let { controller.applyEffects(clip.id, it) } }, enabled = clipboard != null) {
            Text("Tempel Efek", fontSize = 12.sp)
        }
    }
}
