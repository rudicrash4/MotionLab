package id.motionlab.core.resource

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class MediaClassifierTest {
    @Test fun mimeWins() {
        assertEquals(ResourceKind.IMAGE, MediaClassifier.classify("image/webp", "x.bin"))
        assertEquals(ResourceKind.VIDEO, MediaClassifier.classify("video/mp4", null))
        assertEquals(ResourceKind.AUDIO, MediaClassifier.classify("audio/flac", "a"))
        assertEquals(ResourceKind.FONT, MediaClassifier.classify("font/ttf", "a"))
    }

    @Test fun extensionIsTheFallbackForGenericMime() {
        assertEquals(ResourceKind.FONT, MediaClassifier.classify("application/octet-stream", "Roboto.OTF"))
        assertEquals(ResourceKind.AUDIO, MediaClassifier.classify(null, "song.m4a"))
        assertEquals(ResourceKind.IMAGE, MediaClassifier.classify("", "pic.JPG"))
        assertEquals(ResourceKind.OTHER, MediaClassifier.classify("application/pdf", "doc.pdf"))
    }
}

/** Builds a minimal but structurally valid TrueType file in memory. */
internal object FontFixture {
    fun ttf(family: String = "Test Sans", dropTable: String? = null, outlines: Boolean = true, tag: Long = 0x00010000L): ByteArray {
        val nameTable = run {
            val s = family.toByteArray(Charsets.UTF_16BE)
            val b = ByteArrayOutputStream()
            fun u16(v: Int) { b.write(v shr 8); b.write(v and 0xFF) }
            u16(0); u16(1); u16(6 + 12)
            u16(3); u16(1); u16(0x409); u16(1); u16(s.size); u16(0)
            b.write(s)
            b.toByteArray()
        }
        val names = mutableListOf("cmap", "head", "hhea", "hmtx", "maxp", "name")
        if (outlines) names += "glyf"
        if (dropTable != null) names -= dropTable
        val bodies = names.associateWith { if (it == "name") nameTable else ByteArray(16) { 1 } }
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun u32(v: Long) { u16((v shr 16).toInt() and 0xFFFF); u16((v and 0xFFFF).toInt()) }
        u32(tag); u16(names.size); u16(0); u16(0); u16(0)
        var offset = 12L + names.size * 16
        for (n in names) {
            out.write(n.padEnd(4).toByteArray(Charsets.ISO_8859_1)); u32(0); u32(offset); u32(bodies[n]!!.size.toLong())
            offset += bodies[n]!!.size
        }
        for (n in names) out.write(bodies[n]!!)
        return out.toByteArray()
    }
}

class FontValidatorTest {
    private fun rejects(bytes: ByteArray, part: String) {
        try { FontValidator.validate(bytes); fail("expected rejection") } catch (e: MlException) {
            assertEquals(ErrorCode.UNSUPPORTED, e.code)
            assertTrue(e.message!!, e.message!!.contains(part, ignoreCase = true))
        }
    }

    @Test fun validFontIsAcceptedAndFamilyRead() {
        val info = FontValidator.validate(FontFixture.ttf("My Family"))
        assertEquals("My Family", info.family)
        assertEquals(false, info.isOpenType)
    }

    @Test fun otfNeedsCffOutlines() {
        rejects(FontFixture.ttf(tag = 0x4F54544FL), "outlines")
    }

    @Test fun garbageTinyAndWoffAreRejectedWithAReason() {
        rejects(ByteArray(4), "too small")
        rejects(ByteArray(64) { 7 }, "bad header")
        rejects(byteArrayOf(0x77, 0x4F, 0x46, 0x46) + ByteArray(32), "woff")
    }

    @Test fun missingRequiredTableAndBitmapOnlyAreRejected() {
        rejects(FontFixture.ttf(dropTable = "cmap"), "cmap")
        rejects(FontFixture.ttf(outlines = false), "outlines")
    }

    @Test fun truncatedFileIsRejected() {
        val ok = FontFixture.ttf()
        rejects(ok.copyOf(ok.size - 20), "outside the file")
    }
}

class WaveformTest {
    @Test fun peaksFollowTheSignalAndStayBounded() {
        val rate = 1000
        val b = WaveformPeaks.Builder(bucketCount = 10, durationUs = 1_000_000, sampleRate = rate, channels = 2)
        // first half silent, second half loud; stereo interleaved, fed in small chunks (streaming)
        val chunk = ShortArray(200)
        var frame = 0
        while (frame < rate) {
            for (i in 0 until 100) { val loud = frame + i >= 500; val v = if (loud) 16384 else 0; chunk[i * 2] = v.toShort(); chunk[i * 2 + 1] = (-v).toShort() }
            b.addPcm16(chunk, 200); frame += 100
        }
        val w = b.build()
        assertEquals(0f, w.peakBetween(0, 400_000), 0.001f)
        assertEquals(0.5f, w.peakBetween(600_000, 1_000_000), 0.02f)
        assertTrue(w.max(9) > 0.4f && w.min(9) <= 0f)
        val round = WaveformPeaks.fromBytes(1_000_000, w.toBytes())
        assertEquals(w.peakBetween(0, 1_000_000), round.peakBetween(0, 1_000_000), 0f)
    }

    @Test fun bucketCountIsClamped() {
        assertEquals(64, WaveformPeaks.bucketsFor(1000))
        assertEquals(6000, WaveformPeaks.bucketsFor(10_000_000_000))
    }
}

class BoundedCacheTest {
    @Test fun evictsLeastRecentlyUsedWhenOverBudget() {
        val evicted = ArrayList<String>()
        val c = BoundedCache<String, ByteArray>(10, { it.size.toLong() }, { k, _ -> evicted += k })
        c.put("a", ByteArray(4)); c.put("b", ByteArray(4))
        c["a"] // a is now most recent
        c.put("c", ByteArray(4))
        assertEquals(listOf("b"), evicted)
        assertNull(c["b"])
        assertTrue(c.totalCost <= 10)
    }

    @Test fun oversizedSingleEntryIsKeptAndReplacementFreesOld() {
        val c = BoundedCache<String, ByteArray>(5, { it.size.toLong() })
        c.put("big", ByteArray(50))
        assertEquals(1, c.size)
        c.put("big", ByteArray(3))
        assertEquals(3L, c.totalCost)
    }

    @Test fun removeWhereAndClear() {
        val c = BoundedCache<String, ByteArray>(100, { 1 })
        c.put("x:1", ByteArray(1)); c.put("x:2", ByteArray(1)); c.put("y:1", ByteArray(1))
        c.removeWhere { it.startsWith("x:") }
        assertEquals(1, c.size)
        c.clear(); assertEquals(0L, c.totalCost)
    }
}
