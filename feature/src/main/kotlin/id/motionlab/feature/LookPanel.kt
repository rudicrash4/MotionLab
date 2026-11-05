package id.motionlab.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.motionlab.engine.timeline.BlendMode
import id.motionlab.engine.timeline.MaskShape
import id.motionlab.engine.timeline.MaskSpec
import id.motionlab.engine.timeline.SetBlendMode
import id.motionlab.engine.timeline.SetMask
import id.motionlab.engine.timeline.blendMode
import id.motionlab.engine.timeline.mask

/**
 * Blend mode and mask of one clip. Every control writes real data through commands (undoable, saved with the project) and
 * is drawn by the preview renderer: blend modes the renderer cannot draw exactly are shown disabled, not as working.
 */
@Composable
internal fun LookSection(c: EditorController, clipId: String) {
    val found = c.timeline.findClip(clipId) ?: return
    val (track, clip) = found
    if (!track.kind.hasVisuals) return
    val s = c.session
    Text("Blend & mask", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))

    var blendMenu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { blendMenu = true }, modifier = Modifier.testTag("blend-button")) { Text("Blend: ${clip.blendMode.label} ▾") }
        DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
            BlendMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(if (m.exactOnGpu) m.label else "${m.label} (not supported yet)") },
                    enabled = m.exactOnGpu,
                    modifier = Modifier.testTag("blend-${m.name}"),
                    onClick = { blendMenu = false; c.run { s.execute(SetBlendMode(listOf(clipId), m)) } },
                )
            }
        }
    }

    val mask = clip.mask
    if (mask == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.run { s.execute(SetMask(clipId, MaskSpec(MaskShape.RECTANGLE))) } }, modifier = Modifier.testTag("mask-add-rect")) { Text("+ Rectangle mask") }
            OutlinedButton(onClick = { c.run { s.execute(SetMask(clipId, MaskSpec(MaskShape.ELLIPSE))) } }, modifier = Modifier.testTag("mask-add-ellipse")) { Text("+ Ellipse mask") }
        }
        return
    }
    fun commit(m: MaskSpec) { c.run { s.execute(SetMask(clipId, m)) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mask on")
        Switch(checked = mask.enabled, onCheckedChange = { commit(mask.copy(enabled = it)) }, modifier = Modifier.testTag("mask-enabled"))
        Text("Invert")
        Switch(checked = mask.inverted, onCheckedChange = { commit(mask.copy(inverted = it)) }, modifier = Modifier.testTag("mask-invert"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { commit(mask.copy(shape = MaskShape.RECTANGLE)) }, modifier = Modifier.testTag("mask-shape-rect")) { Text(if (mask.shape == MaskShape.RECTANGLE) "● Rectangle" else "○ Rectangle") }
        OutlinedButton(onClick = { commit(mask.copy(shape = MaskShape.ELLIPSE)) }, modifier = Modifier.testTag("mask-shape-ellipse")) { Text(if (mask.shape == MaskShape.ELLIPSE) "● Ellipse" else "○ Ellipse") }
        OutlinedButton(onClick = { c.run { s.execute(SetMask(clipId, null)) } }, modifier = Modifier.testTag("mask-remove")) { Text("Remove") }
    }
    MaskSlider(c, clipId, "x", "Position X", mask.cx, -0.5f..0.5f) { mask.copy(cx = it) }
    MaskSlider(c, clipId, "y", "Position Y", mask.cy, -0.5f..0.5f) { mask.copy(cy = it) }
    MaskSlider(c, clipId, "w", "Width", mask.w, 0.05f..2f) { mask.copy(w = it) }
    MaskSlider(c, clipId, "h", "Height", mask.h, 0.05f..2f) { mask.copy(h = it) }
    MaskSlider(c, clipId, "rot", "Rotation °", mask.rotationDeg, -180f..180f) { mask.copy(rotationDeg = it) }
    MaskSlider(c, clipId, "opacity", "Opacity", mask.opacity, 0f..1f) { mask.copy(opacity = it) }
    MaskSlider(c, clipId, "feather", "Feather", mask.feather, 0f..1f) { mask.copy(feather = it) }
}

/** One mask value: the drag is previewed (no history, no autosave) and recorded as ONE undo step on release. */
@Composable
private fun MaskSlider(c: EditorController, clipId: String, tag: String, label: String, value: Double, range: ClosedFloatingPointRange<Float>, build: (Double) -> MaskSpec) {
    var gesture by remember(clipId, tag) { mutableStateOf<EditorController.EditGesture?>(null) }
    DisposableEffect(clipId, tag) { onDispose { gesture?.cancel() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ${"%.2f".format(java.util.Locale.US, value)}", Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = {
                val g = gesture ?: c.beginGesture().also { gesture = it }
                g.update(SetMask(clipId, build(it.toDouble())))
            },
            onValueChangeFinished = { gesture?.commit(); gesture = null },
            valueRange = range,
            modifier = Modifier.weight(1f).testTag("mask-slider-$tag"),
        )
    }
}
