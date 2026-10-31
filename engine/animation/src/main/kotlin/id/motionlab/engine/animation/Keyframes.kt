package id.motionlab.engine.animation

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/** Time is in microseconds (matching Media3 / Android media timestamps). */
data class Keyframe(
    val timeUs: Long,
    val value: Double,
    val interpolation: Interpolation = Interpolation.Linear,
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

/** Named animatable properties of one object (e.g. "transform.x"). */
class AnimatedProperties(val tracks: Map<String, KeyframeTrack> = emptyMap()) {
    fun with(name: String, track: KeyframeTrack): AnimatedProperties = AnimatedProperties(tracks + (name to track))

    /** Evaluates every property; names in [defaults] that have no animation keep their default value. */
    fun evaluate(timeUs: Long, defaults: Map<String, Double> = emptyMap()): Map<String, Double> {
        val result = LinkedHashMap<String, Double>(defaults)
        for ((name, track) in tracks) {
            result[name] = track.evaluate(timeUs, defaults[name] ?: 0.0)
        }
        return result
    }

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
}
