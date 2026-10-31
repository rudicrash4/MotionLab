package id.motionlab.engine.export

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.timeline.Timeline
import id.motionlab.engine.timeline.TrackKind

/** One piece of a sequence: either media from [uri] (a window of the source file) or silence/black ([uri] == null). */
data class PlanSegment(
    val clipId: String?,
    val uri: String?,
    val sourceStartUs: Long,
    val durationUs: Long,
    val isImage: Boolean = false,
) {
    val isGap: Boolean get() = uri == null
}

/** One output lane. A VIDEO sequence produces video (+ its own audio); an AUDIO sequence only audio. */
data class PlanSequence(val trackId: String, val kind: TrackKind, val segments: List<PlanSegment>)

class ExportPlan(val sequences: List<PlanSequence>, val durationUs: Long) {
    val isEmpty: Boolean get() = sequences.all { seq -> seq.segments.all { it.isGap } }
}

/** What the planner needs to know about an asset, supplied by the caller (from ResourceManager / metadata). */
data class PlannerAsset(val uri: String, val isImage: Boolean = false)

/**
 * Turns the runtime [Timeline] into a flat, Media3-independent plan: per track, clips in order with explicit
 * gaps between them. Kept free of Android types so it can be unit-tested on the JVM.
 *
 * Phase 1 limits (reported, never silently ignored): TEXT/OVERLAY tracks are not exported yet, and clip
 * transform/opacity animation is not applied to the exported video yet.
 */
object ExportPlanner {
    class Result(val plan: ExportPlan, val warnings: List<String>)

    fun plan(timeline: Timeline, assets: (String) -> PlannerAsset?): Result {
        val warnings = ArrayList<String>()
        val sequences = ArrayList<PlanSequence>()
        for (track in timeline.tracks) {
            if (track.muted) continue
            if (track.kind == TrackKind.TEXT || track.kind == TrackKind.OVERLAY) {
                if (track.clips.isNotEmpty()) warnings += "Track '${track.name}' (${track.kind}) is not exported yet"
                continue
            }
            val segments = ArrayList<PlanSegment>()
            var cursor = 0L
            for (clip in track.clips) {
                val assetId = clip.assetId
                    ?: throw MlException(ErrorCode.INVALID_ARGUMENT, "Clip ${clip.id} has no asset")
                val asset = assets(assetId)
                    ?: throw MlException(ErrorCode.NOT_FOUND, "Clip ${clip.id} references unknown asset $assetId")
                if (clip.startUs > cursor) segments += PlanSegment(null, null, 0, clip.startUs - cursor)
                segments += PlanSegment(clip.id, asset.uri, clip.sourceStartUs, clip.durationUs, asset.isImage)
                if (clip.properties.tracks.isNotEmpty()) {
                    warnings += "Clip ${clip.id}: keyframe animation is not applied to the export yet"
                }
                cursor = clip.endUs
            }
            if (segments.isNotEmpty()) sequences += PlanSequence(track.id, track.kind, segments)
        }
        if (sequences.isEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, "Nothing to export")
        return Result(ExportPlan(sequences, timeline.durationUs), warnings)
    }
}
