package id.motionlab.core.resource

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

data class FontInfo(val family: String, val isOpenType: Boolean, val tableCount: Int)

/**
 * Validates a TrueType / OpenType font BEFORE it is registered: checks the sfnt header, the table directory (bounds,
 * overlap with the file size) and the presence of the tables a text renderer needs. Reads the family name from the
 * `name` table when it can. Pure, works on the first bytes of the file plus the table directory.
 */
object FontValidator {
    const val MAX_FONT_BYTES = 32L * 1024 * 1024

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun fail(msg: String): Nothing = throw MlException(ErrorCode.UNSUPPORTED, msg)

    /** [bytes] is the complete file. Throws [MlException] (UNSUPPORTED) with a readable reason when it is not a usable font. */
    fun validate(bytes: ByteArray): FontInfo {
        if (bytes.size < 12) fail("File is too small to be a font")
        if (bytes.size > MAX_FONT_BYTES) fail("Font file is larger than ${MAX_FONT_BYTES / (1024 * 1024)} MB")
        val tag = u32(bytes, 0)
        val openType = when (tag) {
            0x00010000L, 0x74727565L -> false // 1.0 or 'true'
            0x4F54544FL -> true // 'OTTO' (CFF outlines)
            0x74746366L -> fail("Font collections (.ttc) are not supported")
            0x774F4646L, 0x774F4632L -> fail("WOFF web fonts are not supported; use TTF or OTF")
            else -> fail("Not a TrueType/OpenType font (bad header)")
        }
        val numTables = u16(bytes, 4)
        if (numTables == 0 || numTables > 64) fail("Corrupt font: implausible table count $numTables")
        val dirEnd = 12 + numTables * 16
        if (bytes.size < dirEnd) fail("Corrupt font: truncated table directory")
        val tables = HashMap<String, Pair<Long, Long>>()
        for (i in 0 until numTables) {
            val o = 12 + i * 16
            val name = String(bytes, o, 4, Charsets.ISO_8859_1)
            val offset = u32(bytes, o + 8)
            val length = u32(bytes, o + 12)
            if (offset < dirEnd || offset + length > bytes.size) fail("Corrupt font: table '$name' lies outside the file")
            tables[name] = offset to length
        }
        for (required in listOf("cmap", "head", "hhea", "hmtx", "maxp")) {
            if (required !in tables) fail("Corrupt font: required table '$required' is missing")
        }
        val hasOutlines = if (openType) "CFF " in tables || "CFF2" in tables else "glyf" in tables
        if (!hasOutlines) fail("Font has no glyph outlines (bitmap-only fonts are not supported)")
        return FontInfo(familyName(bytes, tables) ?: "Imported font", openType, numTables)
    }

    private fun familyName(bytes: ByteArray, tables: Map<String, Pair<Long, Long>>): String? {
        val (off, len) = tables["name"] ?: return null
        val o = off.toInt()
        if (len < 6) return null
        val count = u16(bytes, o + 2)
        val stringOffset = u16(bytes, o + 4)
        var best: String? = null
        for (i in 0 until count) {
            val r = o + 6 + i * 12
            if (r + 12 > bytes.size) break
            val platform = u16(bytes, r)
            val nameId = u16(bytes, r + 6)
            val length = u16(bytes, r + 8)
            val offset = u16(bytes, r + 10)
            if (nameId != 1) continue // family
            val s = o + stringOffset + offset
            if (s + length > bytes.size) continue
            val text = if (platform == 3 || platform == 0) String(bytes, s, length, Charsets.UTF_16BE) else String(bytes, s, length, Charsets.ISO_8859_1)
            if (text.isNotBlank()) {
                if (platform == 3) return text // Windows names are the most reliable
                if (best == null) best = text
            }
        }
        return best
    }
}
