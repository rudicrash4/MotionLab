package id.motionlab.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegProbeParserTest {
    @Test
    fun parsesVideoAndAudioStreams() {
        val text = """
            format=mov,mp4,m4a,3gp,3g2,mj2
            duration_us=5000000
            bit_rate=2500000
            stream=video|h264|1920|1080|29.97|0|0|2300000
            stream=audio|aac|0|0|0|48000|2|128000
            stream=audio|opus|0|0|0|44100|1|64000
        """.trimIndent()
        val m = FfmpegProbeParser.parse(text)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", m.container)
        assertEquals(5_000_000, m.durationUs)
        assertEquals("ffmpeg", m.source)
        assertEquals(VideoStreamInfo("h264", 1920, 1080, 29.97, 2_300_000), m.video)
        // the first audio stream wins
        assertEquals(AudioStreamInfo("aac", 48_000, 2, 128_000), m.audio)
        assertTrue(m.hasVideo && m.hasAudio)
        assertFalse(m.isImage)
    }

    @Test
    fun unknownDurationAndGarbageAreTolerated() {
        val m = FfmpegProbeParser.parse("format=mp3\nduration_us=-1\nnonsense\nstream=audio|mp3|0|0|0|44100|2|0\nstream=short|x")
        assertEquals(0, m.durationUs)
        assertNull(m.video)
        assertNotNull(m.audio)
    }

    @Test
    fun emptyInputGivesEmptyMetadata() {
        val m = FfmpegProbeParser.parse("")
        assertNull(m.container)
        assertFalse(m.hasVideo || m.hasAudio)
    }
}
