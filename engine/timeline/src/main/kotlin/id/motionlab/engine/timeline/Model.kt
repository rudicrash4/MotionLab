package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.KeyframeTrack

enum class TrackKind { VIDEO, AUDIO, TEXT, OVERLAY }

/** A time range placed on a track. Times are microseconds on the timeline. */
interface Clip {
    val id: String
    val startUs: Long
    val durationUs: Long
    val endUs: Long get() = startUs + durationUs
}

/**
 * A clip backed by (a range of) an imported asset. [sourceStartUs] is where in the asset playback starts,
 * and [properties] use CLIP-LOCAL time (0 = clip start) so moving a clip keeps its animation intact.
 */
data class MediaClip(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val assetId: String? = null,
    val sourceStartUs: Long = 0,
    val properties: AnimatedProperties = AnimatedProperties.NONE,
) : Clip {
    init {
        require(id.isNotBlank()) { "clip id must not be blank" }
        require(startUs >= 0) { "startUs must be >= 0" }
        require(durationUs > 0) { "durationUs must be > 0" }
        require(sourceStartUs >= 0) { "sourceStartUs must be >= 0" }
    }

    fun movedTo(newStartUs: Long): MediaClip = copy(startUs = newStartUs)

    /** Splits at an absolute timeline time strictly inside the clip. The right half gets [rightId]. */
    fun splitAt(timeUs: Long, rightId: String): Pair<MediaClip, MediaClip> {
        if (timeUs <= startUs || timeUs >= endUs) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Split time $timeUs is not inside clip $id [$startUs, $endUs)")
        }
        val leftDuration = timeUs - startUs
        val left = copy(durationUs = leftDuration)
        // Animation keyframes are clip-local, so shift the right half's keyframes back by leftDuration.
        val shifted = AnimatedProperties(
            properties.tracks.mapValues { (_, track) ->
                KeyframeTrack.ofUnsorted(
                    track.keyframes.map { it.copy(timeUs = it.timeUs - leftDuration) },
                )
            },
        )
        val right = copy(
            id = rightId,
            startUs = timeUs,
            durationUs = durationUs - leftDuration,
            sourceStartUs = sourceStartUs + leftDuration,
            properties = shifted,
        )
        return left to right
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
    ) = Track(id, name, kind, clips, muted, locked)

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

/** An immutable ordered stack of tracks (index 0 = bottom layer). */
class Timeline(val tracks: List<Track> = emptyList()) {
    init {
        val ids = tracks.map { it.id }
        require(ids.size == ids.toSet().size) { "duplicate track ids" }
        val clipIds = tracks.flatMap { t -> t.clips.map { it.id } }
        require(clipIds.size == clipIds.toSet().size) { "clip ids must be unique across the timeline" }
    }

    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L

    fun track(trackId: String): Track =
        tracks.firstOrNull { it.id == trackId } ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown track $trackId")

    fun addTrack(track: Track): Timeline {
        if (tracks.any { it.id == track.id }) throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate track ${track.id}")
        return Timeline(tracks + track)
    }

    fun removeTrack(trackId: String): Timeline {
        track(trackId)
        return Timeline(tracks.filter { it.id != trackId })
    }

    private fun updateTrack(trackId: String, transform: (Track) -> Track): Timeline =
        Timeline(tracks.map { if (it.id == trackId) transform(it) else it })

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
