package id.motionlab.engine.timeline

import id.motionlab.core.common.MlLog
import id.motionlab.engine.animation.PropertyNames

fun interface TimeSource {
    fun nowNanos(): Long

    companion object {
        val SYSTEM = TimeSource { System.nanoTime() }
    }
}

/**
 * Playback clock. Position advances from a monotonic [TimeSource] while playing, scaled by [speed].
 * It has no thread of its own: whoever drives rendering (Choreographer, render thread) polls it.
 */
class TimelineClock(private val source: TimeSource = TimeSource.SYSTEM) {
    private var basePositionUs = 0L
    private var baseNanos = 0L
    private var playing = false
    private var speedFactor = 1.0

    val isPlaying: Boolean @Synchronized get() = playing

    var speed: Double
        @Synchronized get() = speedFactor
        @Synchronized set(value) {
            require(value > 0.0 && value.isFinite()) { "speed must be a positive finite number" }
            rebase()
            speedFactor = value
        }

    @Synchronized
    fun currentTimeUs(): Long {
        if (!playing) return basePositionUs
        val elapsedUs = (source.nowNanos() - baseNanos) / 1_000.0
        return basePositionUs + (elapsedUs * speedFactor).toLong()
    }

    @Synchronized
    fun play() {
        if (playing) return
        baseNanos = source.nowNanos()
        playing = true
    }

    @Synchronized
    fun pause() {
        if (!playing) return
        basePositionUs = currentTimeUs()
        playing = false
    }

    @Synchronized
    fun seekTo(positionUs: Long) {
        basePositionUs = positionUs.coerceAtLeast(0)
        baseNanos = source.nowNanos()
    }

    private fun rebase() {
        if (playing) {
            basePositionUs = currentTimeUs()
            baseNanos = source.nowNanos()
        }
    }
}

/** A clip that is visible at the current time, with its animated property values already evaluated. */
class ActiveClip(
    val trackId: String,
    val clip: MediaClip,
    val localTimeUs: Long,
    val values: Map<String, Double>,
)

class PlaybackSnapshot(
    val timeUs: Long,
    val durationUs: Long,
    val isPlaying: Boolean,
    val activeClips: List<ActiveClip>,
) {
    val atEnd: Boolean get() = durationUs > 0 && timeUs >= durationUs
}

/**
 * Drives a [Timeline] with a [TimelineClock]. Call [snapshot] once per frame: it clamps the clock to the
 * timeline, stops at the end, and evaluates animation for the clips active at that instant.
 */
class TimelineController(
    timeline: Timeline = Timeline(),
    private val clock: TimelineClock = TimelineClock(),
) {
    @Volatile
    var timeline: Timeline = timeline
        private set

    @Synchronized
    fun setTimeline(newTimeline: Timeline) {
        timeline = newTimeline
        if (clock.currentTimeUs() > newTimeline.durationUs) clock.seekTo(newTimeline.durationUs)
    }

    @Synchronized
    fun play() {
        val duration = timeline.durationUs
        if (duration <= 0) return
        if (clock.currentTimeUs() >= duration) clock.seekTo(0)
        clock.play()
    }

    @Synchronized
    fun pause() = clock.pause()

    @Synchronized
    fun seekTo(positionUs: Long) = clock.seekTo(positionUs.coerceIn(0, timeline.durationUs))

    var speed: Double
        get() = clock.speed
        set(value) {
            clock.speed = value
        }

    @Synchronized
    fun snapshot(): PlaybackSnapshot {
        val duration = timeline.durationUs
        var time = clock.currentTimeUs()
        if (time >= duration) {
            time = duration
            if (clock.isPlaying) {
                clock.pause()
                clock.seekTo(duration)
                MlLog.d("TimelineController", "reached end at ${duration}us")
            }
        }
        val active = timeline.activeClipsAt(time).map { (track, clip) ->
            val local = time - clip.startUs
            ActiveClip(track.id, clip, local, clip.properties.evaluate(local, DEFAULT_VALUES))
        }
        return PlaybackSnapshot(time, duration, clock.isPlaying, active)
    }

    companion object {
        val DEFAULT_VALUES: Map<String, Double> = mapOf(
            PropertyNames.POSITION_X to 0.0,
            PropertyNames.POSITION_Y to 0.0,
            PropertyNames.SCALE_X to 1.0,
            PropertyNames.SCALE_Y to 1.0,
            PropertyNames.ROTATION to 0.0,
            PropertyNames.OPACITY to 1.0,
        )
    }
}
