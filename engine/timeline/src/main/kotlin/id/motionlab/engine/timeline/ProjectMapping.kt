package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.ClipDto
import id.motionlab.core.project.KeyframeDto
import id.motionlab.core.project.LayerDto
import id.motionlab.core.project.MotionPathDto
import id.motionlab.core.project.PathPointDto
import id.motionlab.core.project.PresetDto
import id.motionlab.core.project.ProjectDocument
import id.motionlab.core.project.TrackDto
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.AppliedPreset
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeIds
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.MotionPath
import id.motionlab.engine.animation.PathPoint

/** Converts between the on-disk project DTOs and the runtime [Timeline]. */
object TimelineMapper {
    fun toTimeline(document: ProjectDocument): Timeline = try {
        Timeline(document.tracks.map(::toTrack), document.layers.map(::toLayer))
    } catch (e: IllegalArgumentException) {
        throw MlException(ErrorCode.CORRUPT_DATA, "Invalid timeline data: ${e.message}", e)
    }

    /** Returns [document] with its tracks, layers and duration replaced by the state of [timeline]. */
    fun applyTo(document: ProjectDocument, timeline: Timeline): ProjectDocument = document.copy(
        config = document.config.copy(durationUs = timeline.durationUs),
        tracks = timeline.tracks.map(::toDto),
        layers = timeline.layers.map(::toDto),
    )

    private fun toTrack(dto: TrackDto): Track {
        val kind = TrackKind.entries.firstOrNull { it.name == dto.kind }
            ?: throw MlException(ErrorCode.UNSUPPORTED, "Unknown track kind '${dto.kind}'")
        return Track(dto.id, dto.name, kind, dto.clips.map(::toClip), dto.muted, dto.locked, dto.layerId, dto.visible, dto.collapsed)
    }

    private fun toLayer(dto: LayerDto): Layer {
        val type = LayerType.entries.firstOrNull { it.name == dto.type }
            ?: throw MlException(ErrorCode.UNSUPPORTED, "Unknown layer type '${dto.type}'")
        return Layer(
            id = dto.id, name = dto.name, type = type, parentId = dto.parentId,
            properties = toProperties(dto.properties, dto.constants),
            motionPath = dto.motionPath?.let(::toPath),
            presets = dto.presets.map { AppliedPreset(it.presetId, it.startUs, it.durationUs) },
            visible = dto.visible, locked = dto.locked, collapsed = dto.collapsed,
        )
    }

    private fun toClip(dto: ClipDto): MediaClip = MediaClip(
        id = dto.id,
        startUs = dto.startUs,
        durationUs = dto.durationUs,
        assetId = dto.assetId,
        sourceStartUs = dto.sourceStartUs,
        properties = toProperties(dto.properties, dto.constants),
        sourceDurationUs = dto.sourceDurationUs,
        payload = dto.payload,
        motionPath = dto.motionPath?.let(::toPath),
        presets = dto.presets.map { AppliedPreset(it.presetId, it.startUs, it.durationUs) },
    )

    private fun toProperties(tracks: Map<String, List<KeyframeDto>>, constants: Map<String, Double>) = AnimatedProperties(
        tracks.mapValues { (_, keys) -> KeyframeTrack.ofUnsorted(keys.map(::toKeyframe)) },
        constants,
    )

    private fun toPath(dto: MotionPathDto): MotionPath = try {
        MotionPath(dto.points.map { PathPoint(it.id, it.x, it.y, it.inDx, it.inDy, it.outDx, it.outDy) }, dto.rotateAlongPath)
    } catch (e: MlException) {
        throw MlException(ErrorCode.CORRUPT_DATA, "Invalid motion path: ${e.message}", e)
    }

    private fun toKeyframe(dto: KeyframeDto): Keyframe {
        val interpolation = when (dto.interpolation) {
            "hold" -> Interpolation.Hold
            "linear" -> Interpolation.Linear
            "bezier" -> Interpolation.Bezier(dto.x1, dto.y1, dto.x2, dto.y2)
            else -> throw MlException(ErrorCode.CORRUPT_DATA, "Unknown interpolation '${dto.interpolation}'")
        }
        // Schema-1 files have no keyframe ids: give them one now (stable from the first save onwards).
        return Keyframe(dto.timeUs, dto.value, interpolation, dto.id.ifEmpty { KeyframeIds.next() })
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
                constants = clip.properties.constants,
                sourceDurationUs = clip.sourceDurationUs,
                payload = clip.payload,
                motionPath = clip.motionPath?.let(::toDto),
                presets = clip.presets.map { PresetDto(it.presetId, it.startUs, it.durationUs) },
            )
        },
        layerId = track.layerId,
        visible = track.visible,
        collapsed = track.collapsed,
    )

    private fun toDto(layer: Layer) = LayerDto(
        id = layer.id, name = layer.name, type = layer.type.name, parentId = layer.parentId,
        properties = layer.properties.tracks.mapValues { (_, t) -> t.keyframes.map(::toDto) },
        constants = layer.properties.constants,
        motionPath = layer.motionPath?.let(::toDto),
        presets = layer.presets.map { PresetDto(it.presetId, it.startUs, it.durationUs) },
        visible = layer.visible, locked = layer.locked, collapsed = layer.collapsed,
    )

    private fun toDto(p: MotionPath) = MotionPathDto(
        p.points.map { PathPointDto(it.id, it.x, it.y, it.inDx, it.inDy, it.outDx, it.outDy) },
        p.rotateAlongPath,
    )

    private fun toDto(k: Keyframe): KeyframeDto = when (val i = k.interpolation) {
        Interpolation.Hold -> KeyframeDto(k.timeUs, k.value, "hold", id = k.id)
        Interpolation.Linear -> KeyframeDto(k.timeUs, k.value, "linear", id = k.id)
        is Interpolation.Bezier -> KeyframeDto(k.timeUs, k.value, "bezier", i.x1, i.y1, i.x2, i.y2, k.id)
    }
}
