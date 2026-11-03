package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.animation.AppliedPreset
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.MotionPath
import id.motionlab.engine.animation.PresetLibrary

/** Everything an edit can change: the timeline and what is selected. Immutable. */
data class EditorState(val timeline: Timeline = Timeline(), val selection: Selection = Selection.EMPTY) {
    fun withTimeline(t: Timeline) = EditorState(t, selection.prunedTo(t))
    fun withSelection(s: Selection) = EditorState(timeline, s.prunedTo(timeline))
}

/**
 * A user-level edit. [apply] is pure: it returns a new state or throws [MlException] and leaves the old state
 * untouched, so a failed command can never corrupt the project. Commands that belong to one continuous gesture
 * (dragging) share a [mergeKey]; [History] folds consecutive ones into a single undo step.
 */
interface Command {
    val label: String
    val mergeKey: String? get() = null
    fun apply(state: EditorState): EditorState
}

private fun invalid(msg: String): Nothing = throw MlException(ErrorCode.INVALID_ARGUMENT, msg)

internal fun requireClipEditable(tl: Timeline, clipId: String): Pair<Track, MediaClip> {
    val found = tl.requireClip(clipId)
    if (found.first.locked) invalid("Track '${found.first.name}' is locked")
    return found
}

internal fun requireOwnerEditable(tl: Timeline, owner: AnimationOwner) {
    when (owner) {
        is AnimationOwner.ClipOwner -> requireClipEditable(tl, owner.clipId)
        is AnimationOwner.LayerOwner -> if (tl.layer(owner.layerId).locked) invalid("Layer '${tl.layer(owner.layerId).name}' is locked")
    }
}

internal fun Timeline.updateOwner(owner: AnimationOwner, fn: (AnimData) -> AnimData): Timeline {
    requireOwnerEditable(this, owner)
    return when (owner) {
        is AnimationOwner.ClipOwner -> {
            val (track, clip) = requireClip(owner.clipId)
            updateTrack(track.id) { it.replaceClip(clip.withAnim(fn(clip.anim))) }
        }
        is AnimationOwner.LayerOwner -> updateLayer(owner.layerId) { it.withAnim(fn(it.anim)) }
    }
}

private fun ownerDurationUs(tl: Timeline, owner: AnimationOwner): Long = when (owner) {
    is AnimationOwner.ClipOwner -> tl.requireClip(owner.clipId).second.durationUs
    is AnimationOwner.LayerOwner -> tl.durationUs.coerceAtLeast(1)
}

// ------------------------------------------------------------------------------------------------ clips

class AddClip(val trackId: String, val clip: MediaClip) : Command {
    override val label get() = "Add clip"
    override fun apply(state: EditorState): EditorState {
        if (state.timeline.track(trackId).locked) invalid("Track is locked")
        val tl = state.timeline.addClip(trackId, clip)
        return EditorState(tl, Selection(clipIds = setOf(clip.id)))
    }
}

/** Moves clips by [deltaUs] (all by the same amount, so a multi-selection keeps its spacing). */
class MoveClips(val clipIds: List<String>, val deltaUs: Long) : Command {
    override val label get() = "Move clip"
    override val mergeKey get() = "move:" + clipIds.sorted().joinToString(",")
    override fun apply(state: EditorState): EditorState {
        if (clipIds.isEmpty()) invalid("No clips to move")
        var tl = state.timeline
        val ids = clipIds.toSet()
        val byTrack = ids.map { requireClipEditable(tl, it) }.groupBy({ it.first.id }, { it.second })
        for ((trackId, moving) in byTrack) {
            val moved = moving.map {
                if (it.startUs + deltaUs < 0) invalid("Clip ${it.id} cannot move before time 0")
                it.movedTo(it.startUs + deltaUs)
            }
            tl = tl.updateTrack(trackId) { t -> t.copy(clips = t.clips.filter { it.id !in ids } + moved) }
        }
        return state.withTimeline(tl)
    }
}

/** Moves one clip to another track of a compatible kind (audio stays on audio, visuals on visual tracks). */
class MoveClipToTrack(val clipId: String, val targetTrackId: String, val newStartUs: Long) : Command {
    override val label get() = "Move clip to track"
    override val mergeKey get() = "movetrack:$clipId"
    override fun apply(state: EditorState): EditorState {
        val tl = state.timeline
        val (from, clip) = requireClipEditable(tl, clipId)
        val to = tl.track(targetTrackId)
        if (to.locked) invalid("Target track is locked")
        if (from.kind.hasVisuals != to.kind.hasVisuals) invalid("A ${from.kind.label} clip cannot go on a ${to.kind.label} track")
        if (newStartUs < 0) invalid("Start must be >= 0")
        val moved = clip.movedTo(newStartUs)
        val result = if (from.id == to.id) {
            tl.updateTrack(from.id) { it.replaceClip(moved) }
        } else {
            tl.updateTrack(from.id) { it.removeClip(clipId) }.updateTrack(to.id) { it.addClip(moved) }
        }
        return state.withTimeline(result)
    }
}

class TrimClipStart(val clipId: String, val newStartUs: Long) : Command {
    override val label get() = "Trim start"
    override val mergeKey get() = "trimstart:$clipId"
    override fun apply(state: EditorState): EditorState {
        val (track, clip) = requireClipEditable(state.timeline, clipId)
        val trimmed = clip.trimmedStart(newStartUs, track.kind.isSourceLimited)
        if (!track.isFree(trimmed.startUs, trimmed.durationUs, setOf(clipId))) invalid("Trim would overlap a neighbouring clip")
        return state.withTimeline(state.timeline.updateTrack(track.id) { it.replaceClip(trimmed) })
    }
}

class TrimClipEnd(val clipId: String, val newEndUs: Long) : Command {
    override val label get() = "Trim end"
    override val mergeKey get() = "trimend:$clipId"
    override fun apply(state: EditorState): EditorState {
        val (track, clip) = requireClipEditable(state.timeline, clipId)
        val trimmed = clip.trimmedEnd(newEndUs, track.kind.isSourceLimited)
        if (!track.isFree(trimmed.startUs, trimmed.durationUs, setOf(clipId))) invalid("Trim would overlap a neighbouring clip")
        return state.withTimeline(state.timeline.updateTrack(track.id) { it.replaceClip(trimmed) })
    }
}

class SetClipDuration(val clipId: String, val durationUs: Long) : Command {
    override val label get() = "Change duration"
    override fun apply(state: EditorState): EditorState {
        val clip = state.timeline.requireClip(clipId).second
        if (durationUs <= 0) invalid("Duration must be > 0")
        return TrimClipEnd(clipId, clip.startUs + durationUs).apply(state)
    }
}

class SplitClip(val clipId: String, val atUs: Long, val newClipId: String) : Command {
    override val label get() = "Split clip"
    override fun apply(state: EditorState): EditorState {
        val (track, _) = requireClipEditable(state.timeline, clipId)
        val tl = state.timeline.splitClip(track.id, clipId, atUs, newClipId)
        return EditorState(tl, Selection(clipIds = setOf(clipId, newClipId)))
    }
}

/** Duplicates each clip right after its original (or at the next free spot of the same track). */
class DuplicateClips(val clipIds: List<String>, val newIds: List<String>) : Command {
    override val label get() = "Duplicate"
    override fun apply(state: EditorState): EditorState {
        if (clipIds.isEmpty()) invalid("No clips to duplicate")
        if (newIds.size != clipIds.size) invalid("One new id per clip is required")
        var tl = state.timeline
        for ((i, id) in clipIds.withIndex()) {
            val (track, clip) = requireClipEditable(tl, id)
            val start = tl.track(track.id).firstFreeStart(clip.endUs, clip.durationUs)
            tl = tl.updateTrack(track.id) { it.addClip(clip.duplicatedAs(newIds[i], start)) }
        }
        return EditorState(tl, Selection(clipIds = newIds.toSet()))
    }
}

class DeleteClips(val clipIds: List<String>) : Command {
    override val label get() = "Delete"
    override fun apply(state: EditorState): EditorState {
        if (clipIds.isEmpty()) invalid("No clips to delete")
        var tl = state.timeline
        for (id in clipIds) {
            val (track, _) = requireClipEditable(tl, id)
            tl = tl.updateTrack(track.id) { it.removeClip(id) }
        }
        return state.withTimeline(tl)
    }
}

/**
 * Pastes copied clips with their relative timing at [atUs], starting on [targetTrackId] (clips that came from
 * several tracks keep their track spacing). If the spot is occupied the whole group slides right to the first
 * position where everything fits.
 */
class PasteClips(
    val content: ClipboardClips,
    val atUs: Long,
    val targetTrackId: String,
    val newIds: List<String>,
) : Command {
    override val label get() = "Paste"
    override fun apply(state: EditorState): EditorState {
        if (content.isEmpty) invalid("Clipboard is empty")
        if (newIds.size != content.entries.size) invalid("One new id per pasted clip is required")
        val tl = state.timeline
        val baseIndex = tl.trackIndex(targetTrackId)
        val targets = content.entries.map { e ->
            val idx = baseIndex + e.trackOffset
            val t = tl.tracks.getOrNull(idx) ?: invalid("Not enough tracks below '${tl.track(targetTrackId).name}' to paste")
            if (t.locked) invalid("Track '${t.name}' is locked")
            if (t.kind.hasVisuals != e.kind.hasVisuals) invalid("Cannot paste a ${e.kind.label} clip on a ${t.kind.label} track")
            t
        }
        var shift = 0L
        var guard = 0
        while (true) {
            var conflict: Long? = null
            for ((i, e) in content.entries.withIndex()) {
                val start = atUs + shift + e.offsetUs
                if (!targets[i].isFree(start, e.clip.durationUs)) {
                    conflict = targets[i].firstFreeStart(start, e.clip.durationUs) - start
                    break
                }
            }
            if (conflict == null) break
            shift += conflict.coerceAtLeast(1)
            if (++guard > 10_000) invalid("No room to paste")
        }
        var result = tl
        for ((i, e) in content.entries.withIndex()) {
            result = result.updateTrack(targets[i].id) { it.addClip(e.clip.duplicatedAs(newIds[i], atUs + shift + e.offsetUs)) }
        }
        return EditorState(result, Selection(clipIds = newIds.toSet()))
    }
}

// ------------------------------------------------------------------------------------------------ tracks

class AddTrack(val track: Track, val index: Int? = null) : Command {
    override val label get() = "Add track"
    override fun apply(state: EditorState): EditorState =
        state.withTimeline(if (index == null) state.timeline.addTrack(track) else state.timeline.insertTrack(track, index))
}

class RemoveTrack(val trackId: String) : Command {
    override val label get() = "Remove track"
    override fun apply(state: EditorState): EditorState {
        if (state.timeline.track(trackId).locked) invalid("Track is locked")
        return state.withTimeline(state.timeline.removeTrack(trackId))
    }
}

class MoveTrack(val trackId: String, val newIndex: Int) : Command {
    override val label get() = "Reorder track"
    override fun apply(state: EditorState): EditorState = state.withTimeline(state.timeline.moveTrack(trackId, newIndex))
}

/** Rename, mute, lock, hide or collapse a track. Only the given fields change. */
class SetTrackFlags(
    val trackId: String,
    val name: String? = null,
    val muted: Boolean? = null,
    val locked: Boolean? = null,
    val visible: Boolean? = null,
    val collapsed: Boolean? = null,
) : Command {
    override val label get() = when {
        locked != null -> if (locked) "Lock track" else "Unlock track"
        muted != null -> if (muted) "Mute track" else "Unmute track"
        visible != null -> if (visible) "Show track" else "Hide track"
        collapsed != null -> if (collapsed) "Collapse track" else "Expand track"
        else -> "Rename track"
    }

    override fun apply(state: EditorState): EditorState {
        if (name != null && name.isBlank()) invalid("Track name must not be blank")
        val tl = state.timeline.updateTrack(trackId) {
            it.copy(
                name = name ?: it.name,
                muted = muted ?: it.muted,
                locked = locked ?: it.locked,
                visible = visible ?: it.visible,
                collapsed = collapsed ?: it.collapsed,
            )
        }
        return state.withTimeline(tl)
    }
}

class LinkTrackToLayer(val trackId: String, val layerId: String?) : Command {
    override val label get() = "Attach track to layer"
    override fun apply(state: EditorState): EditorState {
        if (layerId != null) state.timeline.layer(layerId)
        return state.withTimeline(state.timeline.updateTrack(trackId) { it.copy(layerId = layerId) })
    }
}

// ------------------------------------------------------------------------------------------------ layers

class AddLayer(val layer: Layer) : Command {
    override val label get() = "Add layer"
    override fun apply(state: EditorState): EditorState {
        if (state.timeline.findLayer(layer.id) != null) invalid("Duplicate layer ${layer.id}")
        return state.withTimeline(state.timeline.withLayers(state.timeline.layers + layer))
    }
}

/**
 * Removes a layer. Without [cascade] it must be empty (no child layers, no attached tracks) so nothing silently
 * changes its position; with [cascade] descendants are removed too and their tracks are detached (the clips stay).
 */
class RemoveLayer(val layerId: String, val cascade: Boolean = false) : Command {
    override val label get() = "Remove layer"
    override fun apply(state: EditorState): EditorState {
        val tl = state.timeline
        tl.layer(layerId)
        val doomed = Hierarchy.subtree(tl, layerId)
        val attached = tl.tracks.filter { it.layerId in doomed }
        if (!cascade && (doomed.size > 1 || attached.isNotEmpty())) {
            invalid("Layer still has children or attached tracks; detach them first or remove with cascade")
        }
        val tracks = tl.tracks.map { if (it.layerId in doomed) it.copy(layerId = null) else it }
        return state.withTimeline(Timeline(tracks, tl.layers.filter { it.id !in doomed }))
    }
}

class SetLayerParent(
    val layerId: String,
    val newParentId: String?,
    val keepVisual: Boolean = true,
    val atUs: Long = 0,
) : Command {
    override val label get() = "Change parent"
    override fun apply(state: EditorState): EditorState {
        if (state.timeline.layer(layerId).locked) invalid("Layer is locked")
        return state.withTimeline(Hierarchy.reparent(state.timeline, layerId, newParentId, keepVisual, atUs))
    }
}

/** Puts [layerIds] (which must share one parent) under a new group layer. */
class GroupLayers(val layerIds: List<String>, val group: Layer) : Command {
    override val label get() = "Group layers"
    override fun apply(state: EditorState): EditorState {
        val tl = state.timeline
        if (layerIds.isEmpty()) invalid("Select at least one layer to group")
        if (group.type == LayerType.CONTENT) invalid("A group must be of type GROUP or NULL")
        val parents = layerIds.map { tl.layer(it).parentId }.toSet()
        if (parents.size != 1) invalid("Layers to group must share the same parent")
        val withGroup = tl.withLayers(tl.layers + group.copy(parentId = parents.first()))
        // The group starts as an identity transform, so the children do not move visually.
        val grouped = withGroup.withLayers(withGroup.layers.map { if (it.id in layerIds) it.copy(parentId = group.id) else it })
        return EditorState(grouped, Selection(layerIds = setOf(group.id)))
    }
}

/** Dissolves a group: children go to the group's parent keeping their visual position at [atUs]. */
class UngroupLayer(val groupId: String, val atUs: Long = 0) : Command {
    override val label get() = "Ungroup"
    override fun apply(state: EditorState): EditorState {
        var tl = state.timeline
        val group = tl.layer(groupId)
        for (child in tl.layers.filter { it.parentId == groupId }) {
            tl = Hierarchy.reparent(tl, child.id, group.parentId, keepVisual = true, timeUs = atUs)
        }
        val tracks = tl.tracks.map { if (it.layerId == groupId) it.copy(layerId = group.parentId) else it }
        return state.withTimeline(Timeline(tracks, tl.layers.filter { it.id != groupId }))
    }
}

class SetLayerFlags(
    val layerId: String,
    val name: String? = null,
    val visible: Boolean? = null,
    val locked: Boolean? = null,
    val collapsed: Boolean? = null,
) : Command {
    override val label get() = if (visible != null) "Toggle layer visibility" else if (locked != null) "Toggle layer lock" else "Edit layer"
    override fun apply(state: EditorState): EditorState {
        if (name != null && name.isBlank()) invalid("Layer name must not be blank")
        return state.withTimeline(
            state.timeline.updateLayer(layerId) {
                it.copy(
                    name = name ?: it.name,
                    visible = visible ?: it.visible,
                    locked = locked ?: it.locked,
                    collapsed = collapsed ?: it.collapsed,
                )
            },
        )
    }
}

// ------------------------------------------------------------------------------------------------ animation

private fun KeyframeRef.mapTrack(tl: Timeline, fn: (KeyframeTrack) -> KeyframeTrack): Timeline =
    tl.updateOwner(owner) { a -> a.copy(properties = a.properties.with(property, fn(a.properties.track(property)))) }

class SetKeyframe(val owner: AnimationOwner, val property: String, val keyframe: Keyframe) : Command {
    override val label get() = "Add keyframe"
    override fun apply(state: EditorState): EditorState {
        val tl = state.timeline.updateOwner(owner) { a ->
            a.copy(properties = a.properties.with(property, a.properties.track(property).with(keyframe)))
        }
        return EditorState(tl, Selection(keyframes = setOf(KeyframeRef(owner, property, keyframe.id))))
    }
}

class DeleteKeyframes(val refs: List<KeyframeRef>) : Command {
    override val label get() = "Delete keyframe"
    override fun apply(state: EditorState): EditorState {
        if (refs.isEmpty()) invalid("No keyframes selected")
        var tl = state.timeline
        for (ref in refs.groupBy { it.owner to it.property }) {
            val ids = ref.value.map { it.keyframeId }.toSet()
            tl = ref.value.first().mapTrack(tl) { it.withoutIds(ids) }
        }
        return state.withTimeline(tl)
    }
}

/** Shifts keyframes in time by [deltaUs] (the same amount for all, keeping their relative timing). */
class MoveKeyframes(val refs: List<KeyframeRef>, val deltaUs: Long) : Command {
    override val label get() = "Move keyframe"
    override val mergeKey get() = "kfmove:" + refs.map { it.keyframeId }.sorted().joinToString(",")
    override fun apply(state: EditorState): EditorState {
        if (refs.isEmpty()) invalid("No keyframes selected")
        var tl = state.timeline
        for ((_, group) in refs.groupBy { it.owner to it.property }) {
            val ids = group.map { it.keyframeId }.toSet()
            tl = group.first().mapTrack(tl) { track ->
                val moved = ids.map { track.byId(it) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown keyframe $it") }
                    .map { it.copy(timeUs = it.timeUs + deltaUs) }
                moved.fold(track.withoutIds(ids)) { acc, k -> acc.with(k) }
            }
        }
        return state.withTimeline(tl)
    }
}

class SetKeyframeValue(val ref: KeyframeRef, val value: Double) : Command {
    override val label get() = "Edit keyframe value"
    override val mergeKey get() = "kfvalue:${ref.keyframeId}"
    override fun apply(state: EditorState): EditorState {
        val v = id.motionlab.engine.animation.PropertyRegistry.get(ref.property)?.clamp(value) ?: value
        if (!v.isFinite()) invalid("Value must be finite")
        return state.withTimeline(ref.mapTrack(state.timeline) { t ->
            val k = t.byId(ref.keyframeId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown keyframe ${ref.keyframeId}")
            t.replaced(k.copy(value = v))
        })
    }
}

class SetKeyframeInterpolation(val refs: List<KeyframeRef>, val interpolation: Interpolation) : Command {
    override val label get() = "Change interpolation"
    override val mergeKey get() = if (refs.size == 1) "kfinterp:${refs[0].keyframeId}" else null
    override fun apply(state: EditorState): EditorState {
        if (refs.isEmpty()) invalid("No keyframes selected")
        var tl = state.timeline
        for ((_, group) in refs.groupBy { it.owner to it.property }) {
            tl = group.first().mapTrack(tl) { track ->
                group.fold(track) { acc, r ->
                    val k = acc.byId(r.keyframeId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown keyframe ${r.keyframeId}")
                    acc.replaced(k.copy(interpolation = interpolation))
                }
            }
        }
        return state.withTimeline(tl)
    }
}

/** Copies keyframes [deltaUs] later (new ids, same property and value). */
class DuplicateKeyframes(val refs: List<KeyframeRef>, val deltaUs: Long, val newIds: List<String>) : Command {
    override val label get() = "Duplicate keyframe"
    override fun apply(state: EditorState): EditorState {
        if (refs.isEmpty()) invalid("No keyframes selected")
        if (newIds.size != refs.size) invalid("One new id per keyframe is required")
        var tl = state.timeline
        val created = ArrayList<KeyframeRef>()
        for ((i, ref) in refs.withIndex()) {
            tl = ref.mapTrack(tl) { track ->
                val k = track.byId(ref.keyframeId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown keyframe ${ref.keyframeId}")
                track.with(k.copy(timeUs = k.timeUs + deltaUs, id = newIds[i]))
            }
            created += KeyframeRef(ref.owner, ref.property, newIds[i])
        }
        return EditorState(tl, Selection(keyframes = created.toSet()))
    }
}

/** Pastes copied keyframes onto [owner] at [atTimeUs] (owner-local time), keeping property and relative timing. */
class PasteKeyframes(
    val content: ClipboardKeyframes,
    val owner: AnimationOwner,
    val atTimeUs: Long,
    val newIds: List<String>,
) : Command {
    override val label get() = "Paste keyframes"
    override fun apply(state: EditorState): EditorState {
        if (content.isEmpty) invalid("Clipboard is empty")
        if (newIds.size != content.entries.size) invalid("One new id per keyframe is required")
        val tl = state.timeline.updateOwner(owner) { a ->
            var props = a.properties
            for ((i, e) in content.entries.withIndex()) {
                props = props.with(e.property, props.track(e.property).with(e.keyframe.copy(timeUs = atTimeUs + e.offsetUs, id = newIds[i])))
            }
            a.copy(properties = props)
        }
        return EditorState(
            tl,
            Selection(keyframes = content.entries.mapIndexed { i, e -> KeyframeRef(owner, e.property, newIds[i]) }.toSet()),
        )
    }
}

/** Sets a property at [timeUs] (owner-local): keyframe if the property is animated, constant otherwise. */
class SetPropertyValue(
    val owner: AnimationOwner,
    val property: String,
    val timeUs: Long,
    val value: Double,
) : Command {
    override val label get() = "Change property"
    override val mergeKey get() = "prop:${owner}:$property"
    override fun apply(state: EditorState): EditorState {
        if (!value.isFinite()) invalid("Value must be finite")
        return state.withTimeline(state.timeline.updateOwner(owner) { setPropertyAtTime(it, property, timeUs, value) })
    }
}

/** Turns animation off for a property: it keeps the value it has at [timeUs] as a constant and loses its keyframes. */
class ClearPropertyAnimation(val owner: AnimationOwner, val property: String, val timeUs: Long) : Command {
    override val label get() = "Remove animation"
    override fun apply(state: EditorState): EditorState = state.withTimeline(
        state.timeline.updateOwner(owner) { a ->
            val v = a.properties.valueAt(property, timeUs, id.motionlab.engine.animation.PropertyRegistry.defaultOf(property))
            a.copy(properties = a.properties.with(property, KeyframeTrack.EMPTY).withConstant(property, v))
        },
    )
}

/** Applies a preset by writing ordinary keyframes; the preset is only remembered as an [AppliedPreset] note. */
class ApplyPreset(
    val owner: AnimationOwner,
    val presetId: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val durationUs: Long? = null,
) : Command {
    override val label get() = "Apply preset"
    override fun apply(state: EditorState): EditorState {
        val preset = PresetLibrary.get(presetId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown preset $presetId")
        val total = ownerDurationUs(state.timeline, owner)
        val result = preset.build(total, canvasWidth, canvasHeight, durationUs ?: preset.defaultDurationUs)
        val created = ArrayList<KeyframeRef>()
        val tl = state.timeline.updateOwner(owner) { a ->
            var props = a.properties
            for ((prop, kfs) in result.keyframes) {
                var track = props.track(prop)
                for (k in kfs) track = track.with(k)
                props = props.with(prop, track)
                kfs.forEach { created += KeyframeRef(owner, prop, it.id) }
            }
            a.copy(properties = props, presets = a.presets + result.applied)
        }
        return EditorState(tl, Selection(keyframes = created.toSet()))
    }
}

/** Sets or removes the motion path of an owner. Position becomes (path point at progress) + position properties. */
class SetMotionPath(val owner: AnimationOwner, val path: MotionPath?) : Command {
    override val label get() = if (path == null) "Remove motion path" else "Edit motion path"
    override val mergeKey get() = "path:$owner"
    override fun apply(state: EditorState): EditorState =
        state.withTimeline(state.timeline.updateOwner(owner) { it.copy(motionPath = path) })
}

/** Replaces the text/colour/shape payload of a generated clip. */
class SetClipPayload(val clipId: String, val payload: Map<String, String>) : Command {
    override val label get() = "Edit content"
    override val mergeKey get() = "payload:$clipId"
    override fun apply(state: EditorState): EditorState {
        val (track, clip) = requireClipEditable(state.timeline, clipId)
        return state.withTimeline(state.timeline.updateTrack(track.id) { it.replaceClip(clip.copy(payload = payload)) })
    }
}

/** Several commands as one undo step (e.g. "split every selected clip"). Applied in order; all or nothing. */
class CompositeCommand(override val label: String, val commands: List<Command>) : Command {
    override fun apply(state: EditorState): EditorState = commands.fold(state) { s, c -> c.apply(s) }
}
