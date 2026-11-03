package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.animation.Keyframe
import kotlin.math.abs

/** Addresses one keyframe: which owner, which property, which keyframe id. */
data class KeyframeRef(val owner: AnimationOwner, val property: String, val keyframeId: String)

/** What is selected. Clips, layers and keyframes can be selected together. */
data class Selection(
    val clipIds: Set<String> = emptySet(),
    val layerIds: Set<String> = emptySet(),
    val keyframes: Set<KeyframeRef> = emptySet(),
) {
    val isEmpty: Boolean get() = clipIds.isEmpty() && layerIds.isEmpty() && keyframes.isEmpty()

    fun selectClip(id: String, additive: Boolean): Selection =
        if (additive) copy(clipIds = if (id in clipIds) clipIds - id else clipIds + id)
        else Selection(clipIds = setOf(id))

    fun selectKeyframe(ref: KeyframeRef, additive: Boolean): Selection =
        if (additive) copy(keyframes = if (ref in keyframes) keyframes - ref else keyframes + ref)
        else Selection(keyframes = setOf(ref))

    /** Drops references to things that no longer exist (after delete / undo). */
    fun prunedTo(timeline: Timeline): Selection = Selection(
        clipIds = clipIds.filter { timeline.findClip(it) != null }.toSet(),
        layerIds = layerIds.filter { timeline.findLayer(it) != null }.toSet(),
        keyframes = keyframes.filter { ref ->
            val anim = when (val o = ref.owner) {
                is AnimationOwner.ClipOwner -> timeline.findClip(o.clipId)?.second?.anim
                is AnimationOwner.LayerOwner -> timeline.findLayer(o.layerId)?.anim
            }
            anim?.properties?.track(ref.property)?.byId(ref.keyframeId) != null
        }.toSet(),
    )

    companion object {
        val EMPTY = Selection()
    }
}

/** Selection of a whole group: the layer plus every clip beneath it. */
fun Selection.withLayerGroup(timeline: Timeline, layerId: String): Selection {
    val ids = Hierarchy.subtree(timeline, layerId)
    return copy(layerIds = ids, clipIds = Hierarchy.clipsInSubtree(timeline, layerId).map { it.id }.toSet())
}

/** Snapping to clip edges, the playhead and time zero. */
object SnapEngine {
    class Result(val deltaUs: Long, val snappedTo: Long?)

    /** Interesting times: both edges of every clip not in [excludeClipIds], the playhead and 0. */
    fun targets(timeline: Timeline, excludeClipIds: Set<String>, playheadUs: Long): LongArray {
        val set = java.util.TreeSet<Long>()
        set += 0L
        set += playheadUs
        for (t in timeline.tracks) for (c in t.clips) {
            if (c.id in excludeClipIds) continue
            set += c.startUs
            set += c.endUs
        }
        return set.toLongArray()
    }

    /**
     * Finds the smallest shift (within +-[thresholdUs]) that makes any of [candidateTimesUs] (e.g. the moving clip's
     * start and end) land exactly on a target. Returns delta 0 and null when nothing is close enough.
     */
    fun snap(candidateTimesUs: List<Long>, targets: LongArray, thresholdUs: Long): Result {
        var bestDelta = 0L
        var bestTarget: Long? = null
        var bestAbs = Long.MAX_VALUE
        for (c in candidateTimesUs) {
            var lo = 0
            var hi = targets.size
            while (lo < hi) { // first index with targets[i] >= c
                val mid = (lo + hi) ushr 1
                if (targets[mid] < c) lo = mid + 1 else hi = mid
            }
            for (i in intArrayOf(lo - 1, lo)) {
                if (i < 0 || i >= targets.size) continue
                val d = targets[i] - c
                if (abs(d) <= thresholdUs && abs(d) < bestAbs) {
                    bestAbs = abs(d)
                    bestDelta = d
                    bestTarget = targets[i]
                }
            }
        }
        return Result(bestDelta, bestTarget)
    }
}

/**
 * Keeps a drag legal: given the clips being moved and the delta the finger asks for, returns the closest delta
 * that does not overlap clips that stay behind and does not push anything before time 0.
 */
object MoveSolver {
    fun clampDelta(timeline: Timeline, movingIds: Set<String>, desiredDeltaUs: Long): Long {
        var lo = Long.MIN_VALUE
        var hi = Long.MAX_VALUE
        for (track in timeline.tracks) {
            val moving = track.clips.filter { it.id in movingIds }
            if (moving.isEmpty()) continue
            val staying = track.clips.filter { it.id !in movingIds }
            for (m in moving) {
                lo = maxOf(lo, -m.startUs)
                val prev = staying.lastOrNull { it.endUs <= m.startUs }
                if (prev != null) lo = maxOf(lo, prev.endUs - m.startUs)
                val next = staying.firstOrNull { it.startUs >= m.endUs }
                if (next != null) hi = minOf(hi, next.startUs - m.endUs)
            }
        }
        if (lo > hi) return 0L
        return desiredDeltaUs.coerceIn(lo, hi)
    }
}

/** Copied clips, remembered relative to the earliest one so they can be pasted anywhere. */
class ClipboardClips(val entries: List<Entry>) {
    class Entry(val trackOffset: Int, val kind: TrackKind, val offsetUs: Long, val clip: MediaClip)

    val isEmpty: Boolean get() = entries.isEmpty()
    val spanUs: Long get() = entries.maxOfOrNull { it.offsetUs + it.clip.durationUs } ?: 0L

    companion object {
        fun copy(timeline: Timeline, clipIds: Collection<String>): ClipboardClips {
            if (clipIds.isEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, "Nothing selected to copy")
            val found = clipIds.map { id ->
                val (track, clip) = timeline.requireClip(id)
                Triple(timeline.trackIndex(track.id), track.kind, clip)
            }
            val firstTrack = found.minOf { it.first }
            val firstStart = found.minOf { it.third.startUs }
            return ClipboardClips(
                found.sortedWith(compareBy({ it.first }, { it.third.startUs })).map {
                    Entry(it.first - firstTrack, it.second, it.third.startUs - firstStart, it.third)
                },
            )
        }
    }
}

/** Copied keyframes keep their property and their timing relative to the earliest copied keyframe. */
class ClipboardKeyframes(val entries: List<Entry>) {
    class Entry(val property: String, val offsetUs: Long, val keyframe: Keyframe)

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        fun copy(timeline: Timeline, refs: Collection<KeyframeRef>): ClipboardKeyframes {
            if (refs.isEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, "No keyframes selected to copy")
            val found = refs.map { ref ->
                val anim = animOf(timeline, ref.owner)
                val kf = anim.properties.track(ref.property).byId(ref.keyframeId)
                    ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown keyframe ${ref.keyframeId}")
                ref.property to kf
            }
            val first = found.minOf { it.second.timeUs }
            return ClipboardKeyframes(found.map { Entry(it.first, it.second.timeUs - first, it.second) })
        }
    }
}

internal fun animOf(timeline: Timeline, owner: AnimationOwner): AnimData = when (owner) {
    is AnimationOwner.ClipOwner -> timeline.requireClip(owner.clipId).second.anim
    is AnimationOwner.LayerOwner -> timeline.layer(owner.layerId).anim
}

/**
 * Timeline view window: converts between time and horizontal pixels and implements zoom around a focus point
 * and scrolling. Pure maths so gestures and tests share it.
 */
class TimelineViewport(
    val pxPerSecond: Double = DEFAULT_PX_PER_SECOND,
    val scrollUs: Long = 0,
) {
    init {
        require(pxPerSecond in MIN_PX_PER_SECOND..MAX_PX_PER_SECOND) { "pxPerSecond out of range" }
        require(scrollUs >= 0) { "scroll must be >= 0" }
    }

    fun timeToPx(timeUs: Long): Float = ((timeUs - scrollUs) * pxPerSecond / 1_000_000.0).toFloat()

    fun pxToTime(px: Float): Long = scrollUs + (px * 1_000_000.0 / pxPerSecond).toLong()

    fun durationToPx(durationUs: Long): Float = (durationUs * pxPerSecond / 1_000_000.0).toFloat()

    fun pxToDuration(px: Float): Long = (px * 1_000_000.0 / pxPerSecond).toLong()

    /** First and last time visible in a viewport [widthPx] wide. */
    fun visibleRange(widthPx: Float): LongRange = scrollUs..pxToTime(widthPx)

    /** Zooms by [factor] keeping the time under [focusPx] where it is (pinch). */
    fun zoomed(factor: Double, focusPx: Float): TimelineViewport {
        val focusTime = pxToTime(focusPx)
        val newScale = (pxPerSecond * factor).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND)
        val newScroll = (focusTime - (focusPx * 1_000_000.0 / newScale)).toLong().coerceAtLeast(0)
        return TimelineViewport(newScale, newScroll)
    }

    fun scrolledByPx(dxPx: Float): TimelineViewport =
        TimelineViewport(pxPerSecond, (scrollUs + pxToDuration(dxPx)).coerceAtLeast(0))

    /** Scrolls the least amount that makes [timeUs] visible (follow the playhead). */
    fun revealing(timeUs: Long, widthPx: Float, marginPx: Float = 24f): TimelineViewport {
        val px = timeToPx(timeUs)
        return when {
            px < marginPx -> TimelineViewport(pxPerSecond, (timeUs - pxToDuration(marginPx)).coerceAtLeast(0))
            px > widthPx - marginPx -> TimelineViewport(pxPerSecond, (timeUs - pxToDuration(widthPx - marginPx)).coerceAtLeast(0))
            else -> this
        }
    }

    /** Ruler tick spacing: the smallest "nice" step that is at least [minPx] wide on screen. */
    fun rulerStepUs(minPx: Float = 64f): Long {
        for (step in NICE_STEPS_US) if (durationToPx(step) >= minPx) return step
        return NICE_STEPS_US.last()
    }

    companion object {
        const val DEFAULT_PX_PER_SECOND = 100.0
        const val MIN_PX_PER_SECOND = 2.0
        const val MAX_PX_PER_SECOND = 4000.0
        private val NICE_STEPS_US = longArrayOf(
            10_000, 20_000, 50_000, 100_000, 200_000, 500_000,
            1_000_000, 2_000_000, 5_000_000, 10_000_000, 30_000_000, 60_000_000, 300_000_000, 600_000_000,
        )
    }
}

object Timecode {
    /** "MM:SS:FF" (hours added when needed) for a project at [fps] frames per second. */
    fun format(timeUs: Long, fps: Double): String {
        val t = timeUs.coerceAtLeast(0)
        val seconds = t / 1_000_000L
        val frames = ((t % 1_000_000L) * fps / 1_000_000.0 + 1e-6).toLong()
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) "%d:%02d:%02d:%02d".format(h, m, s, frames) else "%02d:%02d:%02d".format(m, s, frames)
    }

    /** Snaps a time to the nearest frame boundary. */
    fun snapToFrame(timeUs: Long, fps: Double): Long {
        val frame = Math.round(timeUs / 1_000_000.0 * fps)
        return Math.round(frame * 1_000_000.0 / fps)
    }
}
