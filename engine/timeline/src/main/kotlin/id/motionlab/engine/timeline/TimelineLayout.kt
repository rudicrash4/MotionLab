package id.motionlab.engine.timeline

/** Vertical geometry of one displayed track row. The clip bar is the top part; an expanded row adds a keyframe lane. */
class RowGeometry(val trackId: String, val top: Float, val height: Float, val barHeight: Float, val expanded: Boolean) {
    val bottom: Float get() = top + height
    val laneTop: Float get() = top + barHeight
    val laneCenterY: Float get() = laneTop + (height - barHeight) / 2f
}

/** One diamond on a keyframe lane: all keyframes of one owner at one absolute time. */
class KeyframeMark(val owner: AnimationOwner, val absoluteTimeUs: Long, val refs: List<KeyframeRef>)

enum class ClipZone { BODY, TRIM_START, TRIM_END }

sealed interface TimelineHit {
    data class OnClip(val trackId: String, val clipId: String, val zone: ClipZone) : TimelineHit
    data class OnKeyframes(val trackId: String, val mark: KeyframeMark) : TimelineHit
    data class OnEmpty(val trackId: String?) : TimelineHit
}

/**
 * Pure layout and hit-testing for the timeline canvas, so the gesture rules can be unit-tested without a device.
 * Rows are shown top to bottom in reverse track order (the last track is drawn on top, like the layer stack).
 */
object TimelineLayout {
    fun rows(timeline: Timeline, expandedHeightPx: Float, collapsedHeightPx: Float, barFractionExpanded: Float = 0.55f): List<RowGeometry> {
        var y = 0f
        return timeline.tracks.asReversed().map { t ->
            val h = if (t.collapsed) collapsedHeightPx else expandedHeightPx
            val bar = if (t.collapsed) h else h * barFractionExpanded
            RowGeometry(t.id, y, h, bar, !t.collapsed).also { y += h }
        }
    }

    fun totalHeight(rows: List<RowGeometry>): Float = rows.lastOrNull()?.bottom ?: 0f

    fun rowAt(rows: List<RowGeometry>, yPx: Float): RowGeometry? = rows.firstOrNull { yPx >= it.top && yPx < it.bottom }

    /** Diamonds of a track: its clips' keyframes (clip-local time + clip start) and its linked layer's keyframes. */
    fun marks(timeline: Timeline, trackId: String): List<KeyframeMark> {
        val track = timeline.track(trackId)
        val out = ArrayList<KeyframeMark>()
        fun collect(owner: AnimationOwner, anim: AnimData, baseUs: Long) {
            val byTime = java.util.TreeMap<Long, MutableList<KeyframeRef>>()
            for ((prop, kt) in anim.properties.tracks) for (k in kt.keyframes) {
                byTime.getOrPut(k.timeUs) { ArrayList() } += KeyframeRef(owner, prop, k.id)
            }
            for ((t, refs) in byTime) out += KeyframeMark(owner, baseUs + t, refs)
        }
        for (c in track.clips) collect(AnimationOwner.ClipOwner(c.id), c.anim, c.startUs)
        track.layerId?.let { id -> timeline.findLayer(id)?.let { collect(AnimationOwner.LayerOwner(id), it.anim, 0L) } }
        return out
    }

    /**
     * What is under ([xPx], [yPx]) (content coordinates, y already includes vertical scroll). Priority: keyframe
     * diamond, then trim handles, then clip body. Handles are inside the clip but never wider than a third of it so
     * short clips stay movable.
     */
    fun hit(
        timeline: Timeline,
        rows: List<RowGeometry>,
        viewport: TimelineViewport,
        xPx: Float,
        yPx: Float,
        handlePx: Float,
        keyframeRadiusPx: Float,
    ): TimelineHit {
        val row = rowAt(rows, yPx) ?: return TimelineHit.OnEmpty(null)
        val track = timeline.track(row.trackId)
        if (row.expanded && yPx >= row.laneTop) {
            var best: KeyframeMark? = null
            var bestD = Float.MAX_VALUE
            for (m in marks(timeline, row.trackId)) {
                val d = kotlin.math.abs(viewport.timeToPx(m.absoluteTimeUs) - xPx)
                if (d <= keyframeRadiusPx && d < bestD) { best = m; bestD = d }
            }
            if (best != null) return TimelineHit.OnKeyframes(row.trackId, best)
        }
        if (yPx < row.laneTop || !row.expanded) {
            val time = viewport.pxToTime(xPx)
            val clip = track.clips.firstOrNull { time >= it.startUs && time <= it.endUs }
            if (clip != null) {
                val left = viewport.timeToPx(clip.startUs)
                val right = viewport.timeToPx(clip.endUs)
                val h = minOf(handlePx, (right - left) / 3f)
                val zone = when {
                    xPx <= left + h -> ClipZone.TRIM_START
                    xPx >= right - h -> ClipZone.TRIM_END
                    else -> ClipZone.BODY
                }
                return TimelineHit.OnClip(track.id, clip.id, zone)
            }
        }
        return TimelineHit.OnEmpty(track.id)
    }

    /** Clips whose time range intersects the visible window: the only ones the canvas draws (virtualisation). */
    fun visibleClips(track: Track, fromUs: Long, toUs: Long): List<MediaClip> {
        val list = track.clips
        // clips are sorted and non-overlapping, so binary search for the first clip that ends after fromUs
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].endUs < fromUs) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<MediaClip>()
        var i = lo
        while (i < list.size && list[i].startUs <= toUs) out += list[i++]
        return out
    }
}
