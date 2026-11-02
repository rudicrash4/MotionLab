package id.motionlab.engine.animation

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/** Generates keyframe ids that are unique across sessions and projects. */
object KeyframeIds {
    fun next(): String = "kf-" + java.util.UUID.randomUUID().toString()
}

/**
 * Time is in microseconds (matching Media3 / Android media timestamps), relative to the owner's own time base
 * (clip start for clips, timeline start for layers). [id] is stable: moving or editing a keyframe keeps it, so
 * selection, undo/redo and the graph editor can refer to the same keyframe.
 */
data class Keyframe(
    val timeUs: Long,
    val value: Double,
    val interpolation: Interpolation = Interpolation.Linear,
    val id: String = KeyframeIds.next(),
) {
    init {
        require(!value.isNaN() && !value.isInfinite()) { "Keyframe value must be finite" }
    }
}

/** Immutable animation curve for one numeric property. Keyframes are kept sorted with unique times. */
class KeyframeTrack private constructor(val keyframes: List<Keyframe>) {
    val isEmpty: Boolean get() = keyframes.isEmpty()

    /** Adds a keyframe, replacing any keyframe at the same time. */
    fun with(keyframe: Keyframe): KeyframeTrack {
        val result = keyframes.filter { it.timeUs != keyframe.timeUs } + keyframe
        return KeyframeTrack(result.sortedBy { it.timeUs })
    }

    fun without(timeUs: Long): KeyframeTrack = KeyframeTrack(keyframes.filter { it.timeUs != timeUs })

    fun byId(id: String): Keyframe? = keyframes.firstOrNull { it.id == id }

    fun withoutIds(ids: Set<String>): KeyframeTrack = KeyframeTrack(keyframes.filter { it.id !in ids })

    /** Keyframe at exactly [timeUs], if any. */
    fun at(timeUs: Long): Keyframe? = keyframes.firstOrNull { it.timeUs == timeUs }

    /**
     * Replaces the keyframe with the same id as [updated] (time, value and interpolation may all change).
     * A different keyframe that already sits at the new time is replaced (the edited one wins).
     */
    fun replaced(updated: Keyframe): KeyframeTrack {
        if (byId(updated.id) == null) throw MlException(ErrorCode.NOT_FOUND, "Unknown keyframe ${updated.id}")
        val others = keyframes.filter { it.id != updated.id && it.timeUs != updated.timeUs }
        return KeyframeTrack((others + updated).sortedBy { it.timeUs })
    }

    /** Previous keyframe strictly before [timeUs], for keyframe navigation. */
    fun previousBefore(timeUs: Long): Keyframe? = keyframes.lastOrNull { it.timeUs < timeUs }

    /** Next keyframe strictly after [timeUs], for keyframe navigation. */
    fun nextAfter(timeUs: Long): Keyframe? = keyframes.firstOrNull { it.timeUs > timeUs }

    /**
     * Rate of change in value units per second at [timeUs] (central difference over +-1 ms, clamped to the
     * keyframe range). Used by the speed graph; it is derived from [evaluate], so it can never disagree with it.
     */
    fun speedPerSecond(timeUs: Long): Double {
        if (keyframes.size < 2) return 0.0
        val lo = maxOf(timeUs - SPEED_WINDOW_US, keyframes.first().timeUs)
        val hi = minOf(timeUs + SPEED_WINDOW_US, keyframes.last().timeUs)
        if (hi <= lo) return 0.0
        return (evaluate(hi) - evaluate(lo)) / ((hi - lo) / 1_000_000.0)
    }

    /** Returns [default] when the track has no keyframes. Outside the keyframe range the end values are held. */
    fun evaluate(timeUs: Long, default: Double = 0.0): Double {
        if (keyframes.isEmpty()) return default
        val first = keyframes.first()
        if (timeUs <= first.timeUs) return first.value
        val last = keyframes.last()
        if (timeUs >= last.timeUs) return last.value

        // Binary search: greatest index i with keyframes[i].timeUs <= timeUs (i < last index here).
        var lo = 0
        var hi = keyframes.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keyframes[mid].timeUs <= timeUs) lo = mid else hi = mid
        }
        val a = keyframes[lo]
        val b = keyframes[lo + 1]
        val progress = (timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs).toDouble()
        return when (val interp = a.interpolation) {
            Interpolation.Hold -> a.value
            Interpolation.Linear -> a.value + (b.value - a.value) * progress
            is Interpolation.Bezier -> a.value + (b.value - a.value) * BezierEasing.solve(interp, progress)
        }
    }

    override fun equals(other: Any?): Boolean = other is KeyframeTrack && other.keyframes == keyframes
    override fun hashCode(): Int = keyframes.hashCode()
    override fun toString(): String = "KeyframeTrack($keyframes)"

    companion object {
        private const val SPEED_WINDOW_US = 1_000L
        val EMPTY = KeyframeTrack(emptyList())

        /** Strict factory: rejects unsorted input and duplicate times instead of silently fixing it. */
        fun of(keyframes: List<Keyframe>): KeyframeTrack {
            for (i in 1 until keyframes.size) {
                if (keyframes[i].timeUs <= keyframes[i - 1].timeUs) {
                    throw MlException(
                        ErrorCode.INVALID_ARGUMENT,
                        "Keyframes must have strictly increasing times (index $i)",
                    )
                }
            }
            return KeyframeTrack(keyframes.toList())
        }

        /** Lenient factory used when loading data: sorts and de-duplicates (last one wins). */
        fun ofUnsorted(keyframes: List<Keyframe>): KeyframeTrack =
            KeyframeTrack(keyframes.associateBy { it.timeUs }.values.sortedBy { it.timeUs })
    }
}

/**
 * Animatable properties of one object (e.g. "transform.x"). A property is in one of three states:
 *  - animated: it has a [KeyframeTrack] with at least one keyframe (wins over everything else),
 *  - constant: a fixed value in [constants],
 *  - default: neither (the default comes from [PropertyRegistry] or the caller).
 */
class AnimatedProperties(
    val tracks: Map<String, KeyframeTrack> = emptyMap(),
    val constants: Map<String, Double> = emptyMap(),
) {
    init {
        for ((name, v) in constants) require(v.isFinite()) { "constant $name must be finite" }
    }

    fun with(name: String, track: KeyframeTrack): AnimatedProperties =
        if (track.isEmpty) AnimatedProperties(tracks - name, constants) else AnimatedProperties(tracks + (name to track), constants)

    fun withConstant(name: String, value: Double): AnimatedProperties =
        AnimatedProperties(tracks, constants + (name to value))

    fun withoutConstant(name: String): AnimatedProperties = AnimatedProperties(tracks, constants - name)

    fun track(name: String): KeyframeTrack = tracks[name] ?: KeyframeTrack.EMPTY

    fun isAnimated(name: String): Boolean = tracks[name]?.isEmpty == false

    /** Value of one property at [timeUs]: keyframes, else constant, else [default]. */
    fun valueAt(name: String, timeUs: Long, default: Double): Double {
        val t = tracks[name]
        if (t != null && !t.isEmpty) return t.evaluate(timeUs, default)
        return constants[name] ?: default
    }

    /** Evaluates every property; names in [defaults] that have no animation keep their default value. */
    fun evaluate(timeUs: Long, defaults: Map<String, Double> = emptyMap()): Map<String, Double> {
        val result = LinkedHashMap<String, Double>(defaults)
        for ((name, v) in constants) result[name] = v
        for ((name, track) in tracks) {
            result[name] = track.evaluate(timeUs, constants[name] ?: defaults[name] ?: 0.0)
        }
        return result
    }

    /** Applies [transform] to every keyframe track (used by split / trim to re-base clip-local time). */
    fun mapTracks(transform: (KeyframeTrack) -> KeyframeTrack): AnimatedProperties =
        AnimatedProperties(tracks.mapValues { transform(it.value) }, constants)

    override fun equals(other: Any?): Boolean =
        other is AnimatedProperties && other.tracks == tracks && other.constants == constants

    override fun hashCode(): Int = 31 * tracks.hashCode() + constants.hashCode()

    companion object {
        val NONE = AnimatedProperties()
    }
}

/** Property names shared across the editor. */
object PropertyNames {
    const val POSITION_X = "transform.x"
    const val POSITION_Y = "transform.y"
    const val SCALE_X = "transform.scaleX"
    const val SCALE_Y = "transform.scaleY"
    const val ROTATION = "transform.rotation"
    const val OPACITY = "opacity"
    const val ANCHOR_X = "transform.anchorX"
    const val ANCHOR_Y = "transform.anchorY"
    const val SKEW_X = "transform.skewX"
    const val SKEW_Y = "transform.skewY"

    /** Progress 0..1 along the owner's motion path (arc-length parameterised). */
    const val MOTION_PROGRESS = "motion.progress"
}
