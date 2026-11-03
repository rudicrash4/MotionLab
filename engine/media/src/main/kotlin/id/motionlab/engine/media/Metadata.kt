package id.motionlab.engine.media

data class VideoStreamInfo(
    val codec: String,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val bitRate: Long,
)

data class AudioStreamInfo(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Long,
)

/** Everything the editor needs to know about a media file. Missing values are null / 0, never guessed. */
data class MediaMetadata(
    val container: String?,
    val durationUs: Long,
    val video: VideoStreamInfo?,
    val audio: AudioStreamInfo?,
    val rotationDegrees: Int = 0,
    /** Which backend produced this metadata ("android" or "ffmpeg"). */
    val source: String,
) {
    val hasVideo: Boolean get() = video != null
    val hasAudio: Boolean get() = audio != null
    val isImage: Boolean get() = video != null && durationUs <= 0 && audio == null
}

/** Parses the text produced by the native FFmpeg probe (see ffmpeg_probe.cpp for the format). */
object FfmpegProbeParser {
    fun parse(text: String): MediaMetadata {
        var container: String? = null
        var durationUs = 0L
        var video: VideoStreamInfo? = null
        var audio: AudioStreamInfo? = null
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "format" -> container = value.ifBlank { null }
                "duration_us" -> durationUs = value.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                "stream" -> {
                    val f = value.split('|')
                    if (f.size < 8) continue
                    when (f[0]) {
                        "video" -> if (video == null) {
                            video = VideoStreamInfo(
                                codec = f[1],
                                width = f[2].toIntOrNull() ?: 0,
                                height = f[3].toIntOrNull() ?: 0,
                                frameRate = f[4].toDoubleOrNull() ?: 0.0,
                                bitRate = f[7].toLongOrNull() ?: 0L,
                            )
                        }
                        "audio" -> if (audio == null) {
                            audio = AudioStreamInfo(
                                codec = f[1],
                                sampleRate = f[5].toIntOrNull() ?: 0,
                                channels = f[6].toIntOrNull() ?: 0,
                                bitRate = f[7].toLongOrNull() ?: 0L,
                            )
                        }
                    }
                }
            }
        }
        return MediaMetadata(container, durationUs, video, audio, 0, "ffmpeg")
    }
}
