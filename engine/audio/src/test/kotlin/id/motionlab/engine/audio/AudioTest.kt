package id.motionlab.engine.audio

import id.motionlab.core.common.MlException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AudioTest {
    @Test
    fun dbConversionRoundTrips() {
        assertEquals(1f, Gain.dbToLinear(0f), 1e-6f)
        assertEquals(0.5011872f, Gain.dbToLinear(-6f), 1e-5f)
        assertEquals(-6f, Gain.linearToDb(Gain.dbToLinear(-6f)), 1e-4f)
        assertEquals(Float.NEGATIVE_INFINITY, Gain.linearToDb(0f), 0f)
    }

    @Test
    fun muteAndSolo() {
        val c = AudioController()
        c.upsert(AudioTrackState("a", volume = 0.5f))
        c.upsert(AudioTrackState("b"))
        c.upsert(AudioTrackState("m", muted = true))
        assertEquals(0.5f, c.effectiveGain("a"), 0f)
        assertEquals(0f, c.effectiveGain("m"), 0f)
        assertEquals(0f, c.effectiveGain("unknown"), 0f)

        c.update("b") { it.copy(solo = true) }
        assertEquals(0f, c.effectiveGain("a"), 0f)
        assertEquals(1f, c.effectiveGain("b"), 0f)

        c.masterVolume = 0.5f
        assertEquals(0.5f, c.effectiveGain("b"), 0f)
    }

    @Test
    fun updateUnknownTrackFails() {
        try {
            AudioController().update("x") { it }
            fail()
        } catch (_: MlException) {
        }
    }

    @Test
    fun mixerSumsAppliesGainAndClips() {
        val mixer = PcmMixer(AudioFormat(48_000, 2))
        val out = ShortArray(4)
        mixer.mix(
            listOf(
                MixInput(shortArrayOf(1000, -1000, 30000, -30000), 1f),
                MixInput(shortArrayOf(500, 500, 30000, -30000), 0.5f),
            ),
            out,
        )
        assertArrayEquals(shortArrayOf(1250, -750, Short.MAX_VALUE, Short.MIN_VALUE), out)
    }

    @Test
    fun shortInputsActAsSilenceAfterTheirEnd() {
        val out = ShortArray(3)
        PcmMixer(AudioFormat(44_100, 1)).mix(listOf(MixInput(shortArrayOf(7), 1f)), out)
        assertArrayEquals(shortArrayOf(7, 0, 0), out)
    }
}
