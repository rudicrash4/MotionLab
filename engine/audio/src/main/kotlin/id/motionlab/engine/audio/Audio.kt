package id.motionlab.engine.audio

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import kotlin.math.pow

data class AudioFormat(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels in 1..8) { "channels must be 1..8" }
    }
}

object Gain {
    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) Float.NEGATIVE_INFINITY else (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()
}

data class AudioTrackState(
    val id: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    init {
        require(volume >= 0f && !volume.isNaN()) { "volume must be >= 0" }
    }
}

/**
 * Holds per-track mixer state and decides audibility the way a DAW does:
 * if any track is soloed only soloed tracks are heard; muted tracks are always silent.
 */
class AudioController {
    private val tracks = LinkedHashMap<String, AudioTrackState>()

    @Volatile
    var masterVolume: Float = 1f
        set(value) {
            require(value >= 0f && !value.isNaN()) { "masterVolume must be >= 0" }
            field = value
        }

    @Synchronized
    fun upsert(state: AudioTrackState) {
        tracks[state.id] = state
    }

    @Synchronized
    fun remove(id: String) {
        tracks.remove(id)
    }

    @Synchronized
    fun get(id: String): AudioTrackState? = tracks[id]

    @Synchronized
    fun update(id: String, transform: (AudioTrackState) -> AudioTrackState) {
        val current = tracks[id] ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown audio track $id")
        tracks[id] = transform(current)
    }

    /** Linear gain to apply to the track's samples before mixing (includes master volume). */
    @Synchronized
    fun effectiveGain(id: String): Float {
        val t = tracks[id] ?: return 0f
        if (t.muted) return 0f
        val anySolo = tracks.values.any { it.solo }
        if (anySolo && !t.solo) return 0f
        return t.volume * masterVolume
    }
}

class MixInput(val samples: ShortArray, val gain: Float)

interface AudioMixer {
    val format: AudioFormat

    /** Mixes [inputs] into [output] (interleaved PCM16). Shorter inputs are treated as silence after their end. */
    fun mix(inputs: List<MixInput>, output: ShortArray)
}

/** Sums PCM16 inputs in 32-bit float and hard-clips to the Short range. */
class PcmMixer(override val format: AudioFormat) : AudioMixer {
    override fun mix(inputs: List<MixInput>, output: ShortArray) {
        for (i in output.indices) {
            var acc = 0f
            for (input in inputs) {
                if (i < input.samples.size) acc += input.samples[i] * input.gain
            }
            output[i] = acc.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }
}
