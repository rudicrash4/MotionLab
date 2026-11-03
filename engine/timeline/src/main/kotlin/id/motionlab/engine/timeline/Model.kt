package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.AppliedPreset
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeIds
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.MotionPath

/**
 * What a [Track] holds. A track is a horizontal LANE in the timeline: it orders clips in time and decides
 * stacking order (z-order) and audibility/visibility. It is not a transform node; that is the job of [Layer].
 */
enum class TrackKind(val label: String, val hasVisuals: Boolean) {
    VIDEO("Video", true),
    AUDIO("Audio", false),
    IMAGE("Image", true),
    TEXT("Text", true),
    OBJECT("Object", true),
    SHAPE("Shape", true),

    /** Kept so projects written by earlier versions still load. Behaves like a visual track. */
    OVERLAY("Overlay", true),
    ;

    /** Video and audio clips play a range of an imported file, so they cannot be stretched past its end. */
    val isSourceLimited: Boolean get() = this == VIDEO || this == AUDIO
}

/** A time range placed on a track. Times are microseconds on the timeline. */
interface Clip {
    val id: String
    val startUs: Long
    val durationUs: Long
    val endUs: Long get() = startUs + durationUs
}

/** Everything about an object that can be animated, shared by clips and layers so one set of commands serves both. */
data class AnimData(
    val properties: AnimatedProperties = AnimatedProperties.NONE,
    val motionPath: MotionPath? = null,
    val presets: List<AppliedPreset> = emptyList(),
)

const val MIN_CLIP_DURATION_US = 10_000L

/**
 * A clip backed by (a range of) an imported asset or generated content (text, shape...). [sourceStartUs] is where
 * in the asset playback starts, and [properties] use CLIP-LOCAL time (0 = clip start) so moving a clip keeps its
 * animation intact. The clip only REFERENCES the asset by id: no operation here ever touches the asset itself.
 */
data class MediaClip(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val assetId: String? = null,
    val sourceStartUs: Long = 0,
    val properties: AnimatedProperties = AnimatedProperties.NONE,
    /** Length of the source asset, if known; limits how far a media clip can be extended. */
    val sourceDurationUs: Long? = null,
    /** Content parameters of generated clips, e.g. text="Hello", color="#FF8800", shape="ellipse", w="400", h="200". */
    val payload: Map<String, String> = emptyMap(),
    val motionPath: MotionPath? = null,
    val presets: List<AppliedPreset> = emptyList(),
) : Clip {
    init {
        require(id.isNotBlank()) { "clip id must not be blank" }
        require(startUs >= 0) { "startUs must be >= 0" }
        require(durationUs > 0) { "durationUs must be > 0" }
        require(sourceStartUs >= 0) { "sourceStartUs must be >= 0" }
    }

    val anim: AnimData get() = AnimData(properties, motionPath, presets)

    fun withAnim(a: AnimData): MediaClip = copy(properties = a.properties, motionPath = a.motionPath, presets = a.presets)

    fun movedTo(newStartUs: Long): MediaClip = copy(startUs = newStartUs)

    /** Splits at an absolute timeline time strictly inside the clip. The right half gets [rightId]. */
    fun splitAt(timeUs: Long, rightId: String): Pair<MediaClip, MediaClip> {
        if (timeUs <= startUs || timeUs >= endUs) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Split time $timeUs is not inside clip $id [$startUs, $endUs)")
        }
        val leftDuration = timeUs - startUs
        val left = copy(durationUs = leftDuration, presets = presets.filter { it.startUs + it.durationUs <= leftDuration })
        // Animation keyframes are clip-local, so shift the right half's keyframes back by leftDuration.
        val right = copy(
            id = rightId,
            startUs = timeUs,
            durationUs = durationUs - leftDuration,
            sourceStartUs = sourceStartUs + leftDuration,
            properties = shiftKeyframes(properties, -leftDuration, freshIds = true),
            presets = rebasePresets(presets, -leftDuration, durationUs - leftDuration),
        )
        return left to right
    }

    /**
     * Moves the start edge to [newStartUs] while the end edge stays. The content that is still visible keeps its
     * timeline position: keyframes are re-based so an animation does not slide when the clip is trimmed.
     * With [limitToSource] the start cannot move before the beginning of the source file.
     */
    fun trimmedStart(newStartUs: Long, limitToSource: Boolean): MediaClip {
        if (newStartUs < 0) throw MlException(ErrorCode.INVALID_ARGUMENT, "Trim start must be >= 0")
        if (newStartUs > endUs - MIN_CLIP_DURATION_US) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Clip $id would become shorter than $MIN_CLIP_DURATION_US us")
        }
        val delta = newStartUs - startUs
        if (limitToSource && sourceStartUs + delta < 0) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Clip $id cannot start before the beginning of its source")
        }
        val newDuration = endUs - newStartUs
        return copy(
            startUs = newStartUs,
            durationUs = newDuration,
            sourceStartUs = if (limitToSource) sourceStartUs + delta else sourceStartUs,
            properties = shiftKeyframes(properties, -delta, freshIds = false),
            presets = rebasePresets(presets, -delta, newDuration),
        )
    }

    /** Moves the end edge to [newEndUs]. With [limitToSource] it cannot pass the end of the source (when known). */
    fun trimmedEnd(newEndUs: Long, limitToSource: Boolean): MediaClip {
        if (newEndUs < startUs + MIN_CLIP_DURATION_US) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Clip $id would become shorter than $MIN_CLIP_DURATION_US us")
        }
        val newDuration = newEndUs - startUs
        val limit = sourceDurationUs
        if (limitToSource && limit != null && sourceStartUs + newDuration > limit) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Clip $id cannot extend past the end of its source")
        }
        return copy(durationUs = newDuration, presets = presets.filter { it.startUs + it.durationUs <= newDuration })
    }

    /** Smallest allowed start for [trimmedStart] / largest allowed end for [trimmedEnd], so UIs can clamp drags. */
    fun minStartUs(limitToSource: Boolean): Long = if (limitToSource) (startUs - sourceStartUs).coerceAtLeast(0) else 0L

    fun maxStartUs(): Long = endUs - MIN_CLIP_DURATION_US

    fun minEndUs(): Long = startUs + MIN_CLIP_DURATION_US

    fun maxEndUs(limitToSource: Boolean): Long {
        val limit = sourceDurationUs
        return if (limitToSource && limit != null) startUs + (limit - sourceStartUs) else Long.MAX_VALUE
    }

    /** A copy with a new id/position and fresh keyframe ids (so selection by keyframe id stays unambiguous). */
    fun duplicatedAs(newId: String, newStartUs: Long): MediaClip =
        copy(id = newId, startUs = newStartUs, properties = shiftKeyframes(properties, 0, freshIds = true))

    companion object {
        fun shiftKeyframes(p: AnimatedProperties, deltaUs: Long, freshIds: Boolean): AnimatedProperties =
            p.mapTracks { track ->
                KeyframeTrack.ofUnsorted(
                    track.keyframes.map {
                        it.copy(timeUs = it.timeUs + deltaUs, id = if (freshIds) KeyframeIds.next() else it.id)
                    },
                )
            }

        fun rebasePresets(list: List<AppliedPreset>, deltaUs: Long, newDurationUs: Long): List<AppliedPreset> =
            list.map { it.copy(startUs = it.startUs + deltaUs) }
                .filter { it.startUs >= 0 && it.startUs + it.durationUs <= newDurationUs }
    }
}

/** One lane of non-overlapping clips. Tracks are immutable; every edit returns a new track. */
class Track(
    val id: String,
    val name: String,
    val kind: TrackKind,
    clips: List<MediaClip> = emptyList(),
    val muted: Boolean = false,
    val locked: Boolean = false,
    /** The [Layer] (transform hierarchy node) its clips are drawn under; null = directly under the canvas. */
    val layerId: String? = null,
    val visible: Boolean = true,
    val collapsed: Boolean = false,
) {
    val clips: List<MediaClip> = clips.sortedBy { it.startUs }

    init {
        require(id.isNotBlank()) { "track id must not be blank" }
        for (i in 1 until this.clips.size) {
            if (this.clips[i].startUs < this.clips[i - 1].endUs) {
                throw MlException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Clips ${this.clips[i - 1].id} and ${this.clips[i].id} overlap on track $id",
                )
            }
        }
    }

    val endUs: Long get() = clips.lastOrNull()?.endUs ?: 0L

    fun clip(clipId: String): MediaClip? = clips.firstOrNull { it.id == clipId }

    fun clipAt(timeUs: Long): MediaClip? = clips.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }

    fun copy(
        name: String = this.name,
        clips: List<MediaClip> = this.clips,
        muted: Boolean = this.muted,
        locked: Boolean = this.locked,
        layerId: String? = this.layerId,
        visible: Boolean = this.visible,
        collapsed: Boolean = this.collapsed,
    ) = Track(id, name, kind, clips, muted, locked, layerId, visible, collapsed)

    /** True if a clip of [durationUs] fits at [startUs] without touching clips other than [ignoreIds]. */
    fun isFree(startUs: Long, durationUs: Long, ignoreIds: Set<String> = emptySet()): Boolean =
        startUs >= 0 && clips.none { it.id !in ignoreIds && it.startUs < startUs + durationUs && it.endUs > startUs }

    /** Earliest start >= [fromUs] where a clip of [durationUs] fits. */
    fun firstFreeStart(fromUs: Long, durationUs: Long, ignoreIds: Set<String> = emptySet()): Long {
        var candidate = fromUs.coerceAtLeast(0)
        for (c in clips) {
            if (c.id in ignoreIds) continue
            if (c.endUs <= candidate) continue
            if (c.startUs >= candidate + durationUs) break
            candidate = c.endUs
        }
        return candidate
    }

    fun addClip(clip: MediaClip): Track {
        if (this.clip(clip.id) != null) throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate clip id ${clip.id}")
        return copy(clips = clips + clip)
    }

    fun removeClip(clipId: String): Track {
        if (clip(clipId) == null) throw MlException(ErrorCode.NOT_FOUND, "Unknown clip $clipId")
        return copy(clips = clips.filter { it.id != clipId })
    }

    fun replaceClip(updated: MediaClip): Track {
        if (clip(updated.id) == null) throw MlException(ErrorCode.NOT_FOUND, "Unknown clip ${updated.id}")
        return copy(clips = clips.map { if (it.id == updated.id) updated else it })
    }
}

/**
 * An immutable ordered stack of tracks (index 0 = bottom layer in draw order) plus the [Layer] hierarchy the
 * tracks' clips are attached to. Tracks answer "when and in which lane", layers answer "where, under which
 * parent, with which inherited transform".
 */
class Timeline(val tracks: List<Track> = emptyList(), val layers: List<Layer> = emptyList()) {
    init {
        val ids = tracks.map { it.id }
        require(ids.size == ids.toSet().size) { "duplicate track ids" }
        val clipIds = tracks.flatMap { t -> t.clips.map { it.id } }
        require(clipIds.size == clipIds.toSet().size) { "clip ids must be unique across the timeline" }
        val layerIds = layers.map { it.id }
        require(layerIds.size == layerIds.toSet().size) { "duplicate layer ids" }
        val known = layerIds.toSet()
        for (l in layers) {
            require(l.parentId == null || l.parentId in known) { "layer ${l.id} has unknown parent ${l.parentId}" }
        }
        for (l in layers) {
            // walk up; a cycle would never terminate
            var cur: String? = l.parentId
            var steps = 0
            while (cur != null) {
                require(cur != l.id && ++steps <= layers.size) { "layer hierarchy contains a cycle at ${l.id}" }
                cur = layers.first { it.id == cur }.parentId
            }
        }
        for (t in tracks) {
            require(t.layerId == null || t.layerId in known) { "track ${t.id} is attached to unknown layer ${t.layerId}" }
        }
    }

    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L

    fun track(trackId: String): Track =
        tracks.firstOrNull { it.id == trackId } ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown track $trackId")

    fun trackIndex(trackId: String): Int {
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i < 0) throw MlException(ErrorCode.NOT_FOUND, "Unknown track $trackId")
        return i
    }

    fun layer(layerId: String): Layer =
        layers.firstOrNull { it.id == layerId } ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown layer $layerId")

    fun findLayer(layerId: String): Layer? = layers.firstOrNull { it.id == layerId }

    /** The track holding [clipId] and the clip itself, or null. */
    fun findClip(clipId: String): Pair<Track, MediaClip>? {
        for (t in tracks) t.clip(clipId)?.let { return t to it }
        return null
    }

    fun requireClip(clipId: String): Pair<Track, MediaClip> =
        findClip(clipId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown clip $clipId")

    fun withTracks(newTracks: List<Track>) = Timeline(newTracks, layers)

    fun withLayers(newLayers: List<Layer>) = Timeline(tracks, newLayers)

    fun addTrack(track: Track): Timeline {
        if (tracks.any { it.id == track.id }) throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate track ${track.id}")
        return Timeline(tracks + track, layers)
    }

    fun insertTrack(track: Track, index: Int): Timeline {
        if (tracks.any { it.id == track.id }) throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate track ${track.id}")
        val i = index.coerceIn(0, tracks.size)
        return Timeline(tracks.take(i) + track + tracks.drop(i), layers)
    }

    fun removeTrack(trackId: String): Timeline {
        track(trackId)
        return Timeline(tracks.filter { it.id != trackId }, layers)
    }

    /** Moves a track in the stack (z-order). */
    fun moveTrack(trackId: String, newIndex: Int): Timeline {
        val t = track(trackId)
        val rest = tracks.filter { it.id != trackId }
        val i = newIndex.coerceIn(0, rest.size)
        return Timeline(rest.take(i) + t + rest.drop(i), layers)
    }

    fun updateTrack(trackId: String, transform: (Track) -> Track): Timeline {
        track(trackId)
        return Timeline(tracks.map { if (it.id == trackId) transform(it) else it }, layers)
    }

    fun updateLayer(layerId: String, transform: (Layer) -> Layer): Timeline {
        layer(layerId)
        return Timeline(tracks, layers.map { if (it.id == layerId) transform(it) else it })
    }

    fun addClip(trackId: String, clip: MediaClip): Timeline = updateTrack(trackId) { it.addClip(clip) }

    fun removeClip(trackId: String, clipId: String): Timeline = updateTrack(trackId) { it.removeClip(clipId) }

    fun moveClip(trackId: String, clipId: String, newStartUs: Long): Timeline = updateTrack(trackId) { t ->
        val c = t.clip(clipId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown clip $clipId")
        if (newStartUs < 0) throw MlException(ErrorCode.INVALID_ARGUMENT, "newStartUs must be >= 0")
        t.replaceClip(c.movedTo(newStartUs))
    }

    fun splitClip(trackId: String, clipId: String, atUs: Long, newClipId: String): Timeline =
        updateTrack(trackId) { t ->
            val c = t.clip(clipId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown clip $clipId")
            val (left, right) = c.splitAt(atUs, newClipId)
            t.replaceClip(left).addClip(right)
        }

    /** Clips active at [timeUs], bottom layer first (draw order). Muted tracks are skipped. */
    fun activeClipsAt(timeUs: Long): List<Pair<Track, MediaClip>> =
        tracks.filter { !it.muted }.mapNotNull { t -> t.clipAt(timeUs)?.let { t to it } }
}
