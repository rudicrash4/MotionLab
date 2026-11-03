package id.motionlab.feature

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.motionlab.engine.animation.InterpolationKind
import id.motionlab.engine.animation.PresetCategory
import id.motionlab.engine.animation.PresetLibrary
import id.motionlab.engine.animation.PropertyDef
import id.motionlab.engine.animation.PropertyRegistry
import id.motionlab.engine.animation.kind
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.ClearPropertyAnimation
import id.motionlab.engine.timeline.DeleteKeyframes
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.SetClipPayload
import id.motionlab.engine.timeline.SetKeyframeInterpolation
import id.motionlab.engine.timeline.SetKeyframeValue
import id.motionlab.engine.timeline.SetPropertyValue
import id.motionlab.engine.timeline.TrackKind

internal fun formatValue(v: Double, step: Double): String {
    val decimals = when {
        step >= 1.0 -> 1
        step >= 0.1 -> 2
        else -> 3
    }
    return "%.${decimals}f".format(java.util.Locale.US, v)
}

/** Edits the selected clip/layer: content, every registered property with its stopwatch, keyframes and presets. */
@Composable
fun PropertyPanel(c: EditorController, modifier: Modifier = Modifier) {
    val s = c.session
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    @Suppress("UNUSED_VARIABLE") val editVersion = c.timeline // observable read: recompose on committed/previewed edits
    @Suppress("UNUSED_VARIABLE") val playhead = c.playheadUs // ... and when the playhead moves
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp).testTag("property-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (owner == null) {
            Text("Select a clip or layer to edit its properties.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(s.displayName(owner), style = MaterialTheme.typography.titleSmall)
        if (owner is AnimationOwner.ClipOwner) ContentSection(c, owner.clipId)
        PresetsMenu(c, owner)
        val localT = s.localTime(owner)
        val anim = s.anim(owner)
        val groups = PropertyRegistry.all().groupBy { it.group }
        for ((group, defs) in groups) {
            Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            for (def in defs) PropertyRow(c, owner, def, localT, anim.properties.isAnimated(def.name), anim.properties.track(def.name).at(localT)?.id)
        }
        KeyframeSection(c, owner)
    }
}

@Composable
private fun PresetsMenu(c: EditorController, owner: AnimationOwner) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("presets-button")) { Text("Animation presets ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (cat in PresetCategory.values()) {
                PresetLibrary.byCategory(cat).forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${cat.label}: ${p.name}") },
                        modifier = Modifier.testTag("preset-${p.id}"),
                        onClick = { open = false; c.run { c.session.applyPreset(owner, p.id) } },
                    )
                }
            }
        }
    }
    val applied = c.session.presetsOf(owner)
    if (applied.isNotEmpty()) {
        Text("Applied: " + applied.joinToString { PresetLibrary.get(it.presetId)?.name ?: it.presetId }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ContentSection(c: EditorController, clipId: String) {
    val found = c.timeline.findClip(clipId) ?: return
    val (track, clip) = found
    if (track.kind != TrackKind.TEXT && track.kind != TrackKind.SHAPE) return
    fun set(key: String, value: String) = c.run { c.session.execute(SetClipPayload(clipId, clip.payload + (key to value))) }
    if (track.kind == TrackKind.TEXT) {
        var text by remember(clipId, clip.payload["text"]) { mutableStateOf(clip.payload["text"] ?: "") }
        OutlinedTextField(
            value = text, onValueChange = { text = it; set("text", it) }, label = { Text("Text") },
            modifier = Modifier.fillMaxWidth().testTag("content-text"),
        )
    }
    var color by remember(clipId, clip.payload["color"]) { mutableStateOf(clip.payload["color"] ?: "") }
    OutlinedTextField(
        value = color, onValueChange = { color = it; if (Regex("#[0-9a-fA-F]{6}").matches(it)) set("color", it) },
        label = { Text("Colour #RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("content-color"),
    )
    if (track.kind == TrackKind.SHAPE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { set("shape", "rect") }, modifier = Modifier.testTag("shape-rect")) { Text("Rectangle") }
            OutlinedButton(onClick = { set("shape", "ellipse") }, modifier = Modifier.testTag("shape-ellipse")) { Text("Ellipse") }
        }
    }
}

@Composable
private fun PropertyRow(c: EditorController, owner: AnimationOwner, def: PropertyDef, localT: Long, animated: Boolean, kfHereId: String?) {
    val s = c.session
    val value = s.valueAtPlayhead(owner, def.name)
    val tag = def.name
    Column(Modifier.fillMaxWidth().testTag("prop-$tag")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.displayName + if (def.unit.isNotEmpty()) " (${def.unit})" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (animated) {
                IconCell("◀", "Previous keyframe of ${def.displayName}", "kf-prev-$tag") {
                    s.anim(owner).properties.track(def.name).previousBefore(localT)?.let { c.seek(s.baseUs(owner) + it.timeUs) }
                }
                IconCell("▶", "Next keyframe of ${def.displayName}", "kf-next-$tag") {
                    s.anim(owner).properties.track(def.name).nextAfter(localT)?.let { c.seek(s.baseUs(owner) + it.timeUs) }
                }
            }
            IconCell(
                if (kfHereId != null) "◆" else "◇",
                if (kfHereId != null) "Remove keyframe of ${def.displayName}" else "Add keyframe of ${def.displayName}",
                "kf-toggle-$tag",
            ) {
                c.run {
                    if (kfHereId != null) s.execute(DeleteKeyframes(listOf(KeyframeRef(owner, def.name, kfHereId))))
                    else s.addKeyframeAtPlayhead(owner, def.name)
                }
            }
            if (animated) IconCell("✕", "Remove animation of ${def.displayName}", "kf-clear-$tag") {
                c.run { s.execute(ClearPropertyAnimation(owner, def.name, localT)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconCell("−", "Decrease ${def.displayName}", "dec-$tag") { c.run { s.setValueAtPlayhead(owner, def.name, def.clamp(value - def.step)); s.endGesture() } }
            var text by remember(owner, def.name, formatValue(value, def.step)) { mutableStateOf(formatValue(value, def.step)) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.width(96.dp).testTag("value-$tag"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    text.toDoubleOrNull()?.let { v -> c.run { s.setValueAtPlayhead(owner, def.name, def.clamp(v)); s.endGesture() } }
                }),
            )
            IconCell("+", "Increase ${def.displayName}", "inc-$tag") { c.run { s.setValueAtPlayhead(owner, def.name, def.clamp(value + def.step)); s.endGesture() } }
            val lo = def.min
            val hi = def.max
            if (lo != null && hi != null && hi > lo) {
                // Slider drag = one transaction: previews are absolute from the committed state, one undo step on release.
                var gesture by remember(owner, def.name) { mutableStateOf<EditorController.EditGesture?>(null) }
                androidx.compose.runtime.DisposableEffect(owner, def.name) { onDispose { gesture?.cancel() } }
                Slider(
                    value = value.coerceIn(lo, hi).toFloat(),
                    onValueChange = {
                        val g = gesture ?: c.beginGesture().also { gesture = it }
                        g.update(SetPropertyValue(owner, def.name, localT, def.clamp(it.toDouble())))
                    },
                    onValueChangeFinished = { gesture?.commit(); gesture = null },
                    valueRange = lo.toFloat()..hi.toFloat(),
                    modifier = Modifier.weight(1f).testTag("slider-$tag"),
                )
            }
        }
    }
}

@Composable
internal fun IconCell(label: String, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick).testTag(tag).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label) }
}

@Composable
private fun KeyframeSection(c: EditorController, owner: AnimationOwner) {
    val refs = c.selection.keyframes.filter { it.owner == owner }
    if (refs.isEmpty()) return
    val s = c.session
    Text("Selected keyframes: ${refs.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    val first = refs.first()
    val kf = s.anim(owner).properties.track(first.property).byId(first.keyframeId)
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("interp-button")) {
                Text((kf?.interpolation?.kind()?.label ?: "-") + " ▾")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                InterpolationKind.values().forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k.label) },
                        modifier = Modifier.testTag("interp-${k.name}"),
                        onClick = { menu = false; c.run { s.execute(SetKeyframeInterpolation(refs, k.create())) } },
                    )
                }
            }
        }
        OutlinedButton(onClick = { c.run { s.execute(DeleteKeyframes(refs)) } }, modifier = Modifier.testTag("kf-delete")) { Text("Delete") }
    }
    if (refs.size == 1 && kf != null) {
        val def = PropertyRegistry.get(first.property)
        var text by remember(first.keyframeId, kf.value) { mutableStateOf(formatValue(kf.value, def?.step ?: 1.0)) }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text("Keyframe value (${def?.displayName ?: first.property})") },
            modifier = Modifier.fillMaxWidth().testTag("kf-value"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                text.toDoubleOrNull()?.let { v -> c.run { s.execute(SetKeyframeValue(first, def?.clamp(v) ?: v)) } }
            }),
        )
    }
}
