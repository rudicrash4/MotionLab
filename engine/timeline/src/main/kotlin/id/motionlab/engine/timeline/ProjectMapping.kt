package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.ClipDto
import id.motionlab.core.project.KeyframeDto
import id.motionlab.core.project.ProjectDocument
import id.motionlab.core.project.TrackDto
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack

/** Converts between the on-disk project DTOs and the runtime [Timeline]. */
object TimelineMapper {
    fun toTimeline(document: ProjectDocument): Timeline = try {
        Timeline(document.tracks.map(::toTrack))
    } catch (e: IllegalArgumentException) {
        throw MlException(ErrorCode.CORRUPT_DATA, "Invalid timeline data: ${e.message}", e)
    }

    /** Returns [document] with its tracks and duration replaced by the state of [timeline]. */
    fun applyTo(document: ProjectDocument, timeline: Timeline): ProjectDocument = document.copy(
        config = document.config.copy(durationUs = timeline.durationUs),
        tracks = timeline.tracks.map(::toDto),
    )

    private fun toTrack(dto: TrackDto): Track {
        val kind = TrackKind.entries.firstOrNull { it.name == dto.kind }
            ?: throw MlException(ErrorCode.UNSUPPORTED, "Unknown track kind '${dto.kind}'")
        return Track(dto.id, dto.name, kind, dto.clips.map(::toClip), dto.muted, dto.locked)
    }

    private fun toClip(dto: ClipDto): MediaClip = MediaClip(
        id = dto.id,
        startUs = dto.startUs,
        durationUs = dto.durationUs,
        assetId = dto.assetId,
        sourceStartUs = dto.sourceStartUs,
        properties = AnimatedProperties(
            dto.properties.mapValues { (_, keys) -> KeyframeTrack.ofUnsorted(keys.map(::toKeyframe)) },
        ),
    )

    private fun toKeyframe(dto: KeyframeDto): Keyframe {
        val interpolation = when (dto.interpolation) {
            "hold" -> Interpolation.Hold
            "linear" -> Interpolation.Linear
            "bezier" -> Interpolation.Bezier(dto.x1, dto.y1, dto.x2, dto.y2)
            else -> throw MlException(ErrorCode.CORRUPT_DATA, "Unknown interpolation '${dto.interpolation}'")
        }
        return Keyframe(dto.timeUs, dto.value, interpolation)
    }

    private fun toDto(track: Track) = TrackDto(
        id = track.id,
        name = track.name,
        kind = track.kind.name,
        muted = track.muted,
        locked = track.locked,
        clips = track.clips.map { clip ->
            ClipDto(
                id = clip.id,
                assetId = clip.assetId,
                startUs = clip.startUs,
                durationUs = clip.durationUs,
                sourceStartUs = clip.sourceStartUs,
                properties = clip.properties.tracks.mapValues { (_, t) -> t.keyframes.map(::toDto) },
            )
        },
    )

    private fun toDto(k: Keyframe): KeyframeDto = when (val i = k.interpolation) {
        Interpolation.Hold -> KeyframeDto(k.timeUs, k.value, "hold")
        Interpolation.Linear -> KeyframeDto(k.timeUs, k.value, "linear")
        is Interpolation.Bezier -> KeyframeDto(k.timeUs, k.value, "bezier", i.x1, i.y1, i.x2, i.y2)
    }
}
