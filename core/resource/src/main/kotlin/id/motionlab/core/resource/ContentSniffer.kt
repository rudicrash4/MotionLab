package id.motionlab.core.resource

/** What the first bytes of a file say it is. [label] is a human name ("PNG image", "Matroska video"). */
data class SniffResult(val kind: ResourceKind, val label: String)

/**
 * Identifies a file from its leading bytes, so a wrong extension or MIME label (".mp4" that is really a PNG, "octet-stream"
 * from a document provider) never decides what the importer does. Pure and allocation-free apart from the result.
 *
 * It only recognises container signatures; it does NOT promise the codec inside is decodable. The Android layer still
 * checks MediaExtractor/MediaCodec availability and reports a clear error for codecs the device lacks.
 * [HEADER_BYTES] bytes are enough for every signature below.
 */
object ContentSniffer {
    const val HEADER_BYTES = 64

    fun sniff(h: ByteArray, length: Int = h.size): SniffResult? {
        val n = minOf(length, h.size)
        fun at(i: Int, vararg b: Int): Boolean = i + b.size <= n && b.indices.all { (h[i + it].toInt() and 0xFF) == b[it] }
        fun ascii(i: Int, s: String): Boolean = i + s.length <= n && s.indices.all { h[i + it].toInt().toChar() == s[it] }
        if (n < 4) return null

        // ---- images ----
        if (at(0, 0xFF, 0xD8, 0xFF)) return SniffResult(ResourceKind.IMAGE, "JPEG image")
        if (at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return SniffResult(ResourceKind.IMAGE, "PNG image")
        if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return SniffResult(ResourceKind.IMAGE, "GIF image")
        if (ascii(0, "BM") && n >= 14) return SniffResult(ResourceKind.IMAGE, "BMP image")
        if (ascii(0, "RIFF") && ascii(8, "WEBP")) return SniffResult(ResourceKind.IMAGE, "WebP image")

        // ---- RIFF family ----
        if (ascii(0, "RIFF") && ascii(8, "WAVE")) return SniffResult(ResourceKind.AUDIO, "WAV audio")
        if (ascii(0, "RIFF") && ascii(8, "AVI ")) return SniffResult(ResourceKind.VIDEO, "AVI video")

        // ---- ISO base media (mp4 / mov / m4a / heic / avif): "ftyp" box at offset 4 ----
        if (ascii(4, "ftyp") && n >= 12) {
            val brand = String(CharArray(4) { h[8 + it].toInt().toChar() })
            return when {
                brand == "qt  " -> SniffResult(ResourceKind.VIDEO, "QuickTime video")
                brand in IMAGE_BRANDS -> SniffResult(ResourceKind.IMAGE, "HEIF/AVIF image")
                brand == "M4A " || brand == "M4B " || brand == "F4A " -> SniffResult(ResourceKind.AUDIO, "M4A audio")
                else -> SniffResult(ResourceKind.VIDEO, "MP4 video") // mp41, mp42, isom, avc1, 3gp4 ...
            }
        }
        // QuickTime files may start with a "wide"/"mdat"/"moov" box instead of ftyp.
        if (ascii(4, "moov") || ascii(4, "mdat") || ascii(4, "wide") || ascii(4, "free")) return SniffResult(ResourceKind.VIDEO, "QuickTime video")

        // ---- Matroska / WebM: EBML header ----
        if (at(0, 0x1A, 0x45, 0xDF, 0xA3)) return SniffResult(ResourceKind.VIDEO, "Matroska / WebM video") // audio-only .mka/.weba is probed later

        // ---- audio ----
        if (ascii(0, "OggS")) return SniffResult(ResourceKind.AUDIO, "Ogg audio")
        if (ascii(0, "fLaC")) return SniffResult(ResourceKind.AUDIO, "FLAC audio")
        if (ascii(0, "ID3")) return SniffResult(ResourceKind.AUDIO, "MP3 audio")
        if (ascii(0, "#!AMR")) return SniffResult(ResourceKind.AUDIO, "AMR audio")
        if (ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC"))) return SniffResult(ResourceKind.AUDIO, "AIFF audio")
        if (n >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xF6) == 0xF0) return SniffResult(ResourceKind.AUDIO, "AAC (ADTS) audio")
        if (n >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0 && (h[1].toInt() and 0x06) != 0) return SniffResult(ResourceKind.AUDIO, "MP3 audio")

        // ---- fonts ----
        if (at(0, 0x00, 0x01, 0x00, 0x00) || ascii(0, "true")) return SniffResult(ResourceKind.FONT, "TrueType font")
        if (ascii(0, "OTTO")) return SniffResult(ResourceKind.FONT, "OpenType (CFF) font")
        return null
    }

    private val IMAGE_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")

    /**
     * Combines the label-based guess ([declared], from MIME / extension) with the real content. Content wins when it is
     * recognised; an unrecognised header keeps the declared kind (some valid files, e.g. raw ADTS with junk, are not matched).
     */
    fun resolveKind(declared: ResourceKind, sniffed: SniffResult?): ResourceKind = sniffed?.kind ?: declared
}
