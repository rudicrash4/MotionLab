package id.motionlab.core.resource

/**
 * Decides what a picked file is from its MIME type, falling back to the file extension (document providers often
 * report application/octet-stream). Pure; the Android layer supplies the MIME type from ContentResolver.
 */
object MediaClassifier {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val videoExt = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
    private val audioExt = setOf("mp3", "wav", "aac", "m4a", "ogg", "oga", "opus", "flac")
    private val fontExt = setOf("ttf", "otf")

    fun classify(mimeType: String?, displayName: String?): ResourceKind {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        when {
            mime == null -> Unit
            mime.startsWith("image/") -> return ResourceKind.IMAGE
            mime.startsWith("video/") -> return ResourceKind.VIDEO
            mime.startsWith("audio/") -> return ResourceKind.AUDIO
            mime == "font/ttf" || mime == "font/otf" || mime == "font/sfnt" || mime == "application/x-font-ttf" ||
                mime == "application/x-font-otf" || mime == "application/font-sfnt" || mime == "application/vnd.ms-opentype" -> return ResourceKind.FONT
            mime == "application/ogg" -> return ResourceKind.AUDIO
        }
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            in imageExt -> ResourceKind.IMAGE
            in videoExt -> ResourceKind.VIDEO
            in audioExt -> ResourceKind.AUDIO
            in fontExt -> ResourceKind.FONT
            else -> ResourceKind.OTHER
        }
    }
}

/**
 * EXIF orientation (values 1..8 of the TIFF/EXIF tag) as a rotation plus an optional mirror. Phone cameras store most
 * photos sideways and rely on this tag; ignoring it shows the picture rotated and with the wrong aspect ratio.
 */
object ExifOrientation {
    /** Clockwise degrees to rotate the stored pixels (0, 90, 180, 270). */
    fun degrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        6, 5 -> 90
        8, 7 -> 270
        else -> 0
    }

    /** True when the stored pixels are mirrored horizontally before rotating (orientations 2, 4, 5, 7). */
    fun mirrored(orientation: Int): Boolean = orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7

    /** True when width and height trade places after correcting (a 90 / 270 degree turn). */
    fun swapsAxes(orientation: Int): Boolean = degrees(orientation).let { it == 90 || it == 270 }
}
