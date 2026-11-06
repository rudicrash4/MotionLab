package id.motionlab.feature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.vector.ImageVector
import id.motionlab.engine.timeline.SetTrackFlags
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

/** The separate editing sections of the property editor; only the selected one is shown. */
enum class PropertyCategory(val label: String, val icon: ImageVector) {
    TRANSFORM("Transform", MlIcons.Transform),
    CONTENT("Content", MlIcons.Font),
    MASK("Mask", MlIcons.Mask),
    BLEND("Blend", MlIcons.Blend),
    AUDIO("Audio", MlIcons.Volume),
    ANIMATION("Animation", MlIcons.Animation),
}

/**
 * Which categories apply to the selection. Only categories with REAL controls are offered: Color (brightness / contrast /
 * saturation) and audio volume / fades / balance are not implemented in the editor yet, so they are not listed.
 */
internal fun categoriesFor(owner: AnimationOwner, kind: TrackKind?): List<PropertyCategory> = when {
    owner !is AnimationOwner.ClipOwner -> listOf(PropertyCategory.TRANSFORM, PropertyCategory.ANIMATION)
    kind == TrackKind.AUDIO -> listOf(PropertyCategory.AUDIO)
    kind == TrackKind.TEXT || kind == TrackKind.SHAPE -> listOf(PropertyCategory.TRANSFORM, PropertyCategory.CONTENT, PropertyCategory.MASK, PropertyCategory.BLEND, PropertyCategory.ANIMATION)
    kind != null && kind.hasVisuals -> listOf(PropertyCategory.TRANSFORM, PropertyCategory.MASK, PropertyCategory.BLEND, PropertyCategory.ANIMATION)
    else -> listOf(PropertyCategory.TRANSFORM, PropertyCategory.ANIMATION)
}

/** Edits the selected clip/layer, one category at a time (Transform, Content, Mask, Blend, Audio, Animation). */
@Composable
fun PropertyPanel(c: EditorController, modifier: Modifier = Modifier, fontFamilies: List<String> = emptyList()) {
    val s = c.session
    val owner = c.ownerOfSelection()?.takeIf { s.exists(it) }
    @Suppress("UNUSED_VARIABLE") val editVersion = c.timeline // observable read: recompose on committed/previewed edits
    @Suppress("UNUSED_VARIABLE") val playhead = c.playheadUs // ... and when the playhead moves
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("property-panel"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (owner == null) {
            Text("Select a clip or layer to edit its properties.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val clipKind = (owner as? AnimationOwner.ClipOwner)?.let { c.timeline.findClip(it.clipId)?.first?.kind }
        val cats = categoriesFor(owner, clipKind)
        var chosen by remember(owner) { mutableStateOf(cats.first()) }
        // Selecting a keyframe in the timeline jumps to the Animation section, where keyframes are edited.
        val hasKeyframeSelection = c.selection.keyframes.isNotEmpty()
        androidx.compose.runtime.LaunchedEffect(owner, hasKeyframeSelection) {
            if (hasKeyframeSelection && PropertyCategory.ANIMATION in cats) chosen = PropertyCategory.ANIMATION
        }
        val cat = if (chosen in cats) chosen else cats.first()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.displayName(owner), style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(end = 4.dp).widthIn(max = 110.dp))
            cats.forEach { k ->
                FilterChip(
                    selected = cat == k, onClick = { chosen = k },
                    leadingIcon = { MlIcon(k.icon, null, size = 16.dp) },
                    label = { Text(k.label, maxLines = 1) }, modifier = Modifier.testTag("prop-cat-${k.name}"),
                )
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("prop-section-${cat.name}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (cat) {
                PropertyCategory.TRANSFORM -> {
                    val localT = s.localTime(owner)
                    val anim = s.anim(owner)
                    for (def in PropertyRegistry.all()) PropertyRow(c, owner, def, localT, anim.properties.isAnimated(def.name), anim.properties.track(def.name).at(localT)?.id)
                }
                PropertyCategory.CONTENT -> if (owner is AnimationOwner.ClipOwner) ContentSection(c, owner.clipId, fontFamilies)
                PropertyCategory.MASK -> if (owner is AnimationOwner.ClipOwner) MaskSection(c, owner.clipId)
                PropertyCategory.BLEND -> if (owner is AnimationOwner.ClipOwner) BlendSection(c, owner.clipId)
                PropertyCategory.AUDIO -> if (owner is AnimationOwner.ClipOwner) AudioSection(c, owner.clipId)
                PropertyCategory.ANIMATION -> {
                    PresetsMenu(c, owner)
                    Text("Add keyframes with the diamond next to a value in Transform.", style = MaterialTheme.typography.bodySmall, color = MlColors.Muted)
                    KeyframeSection(c, owner)
                }
            }
        }
    }
}

/** The only audio control that exists today: muting the track the clip is on (honoured by playback). Volume, fades and balance are not implemented. */
@Composable
private fun AudioSection(c: EditorController, clipId: String) {
    val track = c.timeline.findClip(clipId)?.first ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MlIcon(MlIcons.Volume, null)
        Text("Mute this track", Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = track.muted,
            onCheckedChange = { c.run { c.session.execute(SetTrackFlags(track.id, muted = it)) } },
            modifier = Modifier.testTag("audio-mute"),
        )
    }
    Text("Volume, fade in / out and balance are not available yet.", style = MaterialTheme.typography.bodySmall, color = MlColors.Muted, modifier = Modifier.testTag("audio-note"))
}

@Composable
private fun PresetsMenu(c: EditorController, owner: AnimationOwner) {
    var open by remember { mutableStateOf(false) }
    Box {
        MenuButton("Animation presets", "presets-button") { open = true }
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
private fun ContentSection(c: EditorController, clipId: String, fontFamilies: List<String>) {
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
    if (track.kind == TrackKind.TEXT) {
        var fontMenu by remember { mutableStateOf(false) }
        Box {
            MenuButton("Font: ${clip.payload["font"] ?: "System default"}", "font-button") { fontMenu = true }
            DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                DropdownMenuItem(text = { Text("System default") }, modifier = Modifier.testTag("font-default"), onClick = {
                    fontMenu = false
                    c.run { c.session.execute(SetClipPayload(clipId, clip.payload - "font")) }
                })
                fontFamilies.forEach { f ->
                    DropdownMenuItem(text = { Text(f) }, modifier = Modifier.testTag("font-$f"), onClick = { fontMenu = false; set("font", f) })
                }
            }
        }
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
                IconCell(MlIcons.KeyframePrevious, "Previous keyframe of ${def.displayName}", "kf-prev-$tag") {
                    s.anim(owner).properties.track(def.name).previousBefore(localT)?.let { c.seek(s.baseUs(owner) + it.timeUs) }
                }
                IconCell(MlIcons.KeyframeNext, "Next keyframe of ${def.displayName}", "kf-next-$tag") {
                    s.anim(owner).properties.track(def.name).nextAfter(localT)?.let { c.seek(s.baseUs(owner) + it.timeUs) }
                }
            }
            IconCell(
                if (kfHereId != null) MlIcons.Keyframe else MlIcons.KeyframeEmpty,
                if (kfHereId != null) "Remove keyframe of ${def.displayName}" else "Add keyframe of ${def.displayName}",
                "kf-toggle-$tag",
            ) {
                c.run {
                    if (kfHereId != null) s.execute(DeleteKeyframes(listOf(KeyframeRef(owner, def.name, kfHereId))))
                    else s.addKeyframeAtPlayhead(owner, def.name)
                }
            }
            if (animated) IconCell(MlIcons.Close, "Remove animation of ${def.displayName}", "kf-clear-$tag") {
                c.run { s.execute(ClearPropertyAnimation(owner, def.name, localT)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconCell(MlIcons.Remove, "Decrease ${def.displayName}", "dec-$tag") { c.run { s.setValueAtPlayhead(owner, def.name, def.clamp(value - def.step)); s.endGesture() } }
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
            IconCell(MlIcons.Add, "Increase ${def.displayName}", "inc-$tag") { c.run { s.setValueAtPlayhead(owner, def.name, def.clamp(value + def.step)); s.endGesture() } }
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
internal fun IconCell(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    MlIconButton(icon, description, tag, touch = 40.dp, iconSize = 20.dp, onClick = onClick)
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
                Text(kf?.interpolation?.kind()?.label ?: "-")
                MlIcon(MlIcons.ExpandMore, null, size = 18.dp)
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
