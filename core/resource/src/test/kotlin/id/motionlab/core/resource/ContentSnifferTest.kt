package id.motionlab.core.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentSnifferTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String, pad: Int = 64) = ByteArray(maxOf(pad, s.length)).also { s.forEachIndexed { i, c -> it[i] = c.code.toByte() } }
    private fun ftyp(brand: String) = ByteArray(64).also {
        it[3] = 0x18; "ftyp$brand".forEachIndexed { i, c -> it[4 + i] = c.code.toByte() }
    }
    private fun riff(form: String) = ByteArray(64).also { "RIFF".forEachIndexed { i, c -> it[i] = c.code.toByte() }; form.forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } }

    private fun kind(b: ByteArray) = ContentSniffer.sniff(b)?.kind

    @Test fun images() {
        assertEquals(ResourceKind.IMAGE, kind(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals(ResourceKind.IMAGE, kind(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals(ResourceKind.IMAGE, kind(ascii("GIF89a")))
        assertEquals(ResourceKind.IMAGE, kind(ascii("GIF87a")))
        assertEquals(ResourceKind.IMAGE, kind(ascii("BM")))
        assertEquals(ResourceKind.IMAGE, kind(riff("WEBP")))
        assertEquals(ResourceKind.IMAGE, kind(ftyp("heic")))
        assertEquals(ResourceKind.IMAGE, kind(ftyp("avif")))
    }

    @Test fun videos() {
        assertEquals("MP4 video", ContentSniffer.sniff(ftyp("isom"))?.label)
        assertEquals("MP4 video", ContentSniffer.sniff(ftyp("mp42"))?.label)
        assertEquals("QuickTime video", ContentSniffer.sniff(ftyp("qt  "))?.label)
        assertEquals(ResourceKind.VIDEO, kind(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F)))
        assertEquals("AVI video", ContentSniffer.sniff(riff("AVI "))?.label)
    }

    @Test fun audio() {
        assertEquals("WAV audio", ContentSniffer.sniff(riff("WAVE"))?.label)
        assertEquals("M4A audio", ContentSniffer.sniff(ftyp("M4A "))?.label)
        assertEquals("Ogg audio", ContentSniffer.sniff(ascii("OggS"))?.label)
        assertEquals("FLAC audio", ContentSniffer.sniff(ascii("fLaC"))?.label)
        assertEquals("MP3 audio", ContentSniffer.sniff(ascii("ID3"))?.label)
        assertEquals("MP3 audio", ContentSniffer.sniff(bytes(0xFF, 0xFB, 0x90, 0x00))?.label)
        assertEquals("AAC (ADTS) audio", ContentSniffer.sniff(bytes(0xFF, 0xF1, 0x50, 0x80))?.label)
    }

    @Test fun fonts() {
        assertEquals(ResourceKind.FONT, kind(bytes(0, 1, 0, 0, 0, 10)))
        assertEquals(ResourceKind.FONT, kind(ascii("OTTO")))
    }

    @Test fun unknownAndTinyInputAreNotGuessed() {
        assertNull(kind(ByteArray(0)))
        assertNull(kind(bytes(1, 2)))
        assertNull(kind(ascii("hello world this is text")))
    }

    @Test fun contentBeatsALyingLabel() {
        // ".mp4" that is really a PNG: the content decides.
        val png = ContentSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0))
        assertEquals(ResourceKind.IMAGE, ContentSniffer.resolveKind(MediaClassifier.classify(null, "clip.mp4"), png))
        // unrecognised header: the declared kind stands (the decoder then has the final word)
        assertEquals(ResourceKind.AUDIO, ContentSniffer.resolveKind(ResourceKind.AUDIO, null))
    }

    @Test fun lengthLimitsWhatIsRead() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertNull(ContentSniffer.sniff(png, 4)) // only 4 valid bytes: an incomplete PNG signature is not accepted
    }
}
