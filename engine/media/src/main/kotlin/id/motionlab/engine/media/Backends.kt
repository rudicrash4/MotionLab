package id.motionlab.engine.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.nativecore.NativeCore
import id.motionlab.nativecore.NativeException

/** Reads metadata for a file. Implementations must close every native/Android handle they open. */
interface MetadataBackend {
    val name: String

    /** Cheap check; false means [extract] must not be called. */
    fun isAvailable(): Boolean

    /** Throws [MlException] (DECODE / IO / UNSUPPORTED) when the file cannot be read by this backend. */
    fun extract(uri: String): MediaMetadata
}

/** Uses the platform's MediaMetadataRetriever (hardware-codec aware, handles content:// URIs). */
class AndroidMetadataBackend(private val context: Context) : MetadataBackend {
    override val name = "android"

    override fun isAvailable(): Boolean = true

    override fun extract(uri: String): MediaMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == null || parsed.scheme == "file") {
                retriever.setDataSource(parsed.path ?: uri)
            } else {
                retriever.setDataSource(context, parsed)
            }
            fun str(key: Int): String? = retriever.extractMetadata(key)
            val hasVideo = str(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = str(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasVideo && !hasAudio && str(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.startsWith("image/") != true) {
                throw MlException(ErrorCode.UNSUPPORTED, "No audio or video track found")
            }
            val durationMs = str(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = str(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = str(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = str(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fps = str(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
            val bitrate = str(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val mime = str(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            return MediaMetadata(
                container = mime,
                durationUs = durationMs * 1000L,
                video = if (hasVideo) VideoStreamInfo(codec = "", width = width, height = height, frameRate = fps, bitRate = bitrate) else null,
                audio = if (hasAudio) AudioStreamInfo(codec = "", sampleRate = 0, channels = 0, bitRate = 0) else null,
                rotationDegrees = rotation,
                source = name,
            )
        } catch (e: MlException) {
            throw e
        } catch (e: RuntimeException) {
            throw MlException(ErrorCode.DECODE, "Android could not read $uri: ${e.message}", e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}

/** Uses the optional bundled FFmpeg (LGPL build); only available when scripts/build_ffmpeg_android.sh was run. */
class FfmpegMetadataBackend : MetadataBackend {
    override val name = "ffmpeg"

    override fun isAvailable(): Boolean = NativeCore.ffmpegAvailable()

    override fun extract(uri: String): MediaMetadata {
        if (!isAvailable()) throw MlException(ErrorCode.UNSUPPORTED, "FFmpeg backend is not available in this build")
        val path = Uri.parse(uri).let { if (it.scheme == null || it.scheme == "file") it.path ?: uri else null }
            ?: throw MlException(ErrorCode.UNSUPPORTED, "FFmpeg backend reads local files only (got $uri)")
        return try {
            FfmpegProbeParser.parse(NativeCore.ffmpegProbe(path))
        } catch (e: NativeException) {
            throw MlException(ErrorCode.DECODE, "FFmpeg could not read $path: ${e.message}", e)
        }
    }
}

/**
 * Tries backends in order and returns the first success. A failure of one backend is logged and the next is
 * tried (graceful fallback); if all fail the last error is thrown with the earlier ones in the message.
 */
class MetadataResolver(private val backends: List<MetadataBackend>) {
    fun resolve(uri: String): MediaMetadata {
        val errors = ArrayList<String>()
        var last: MlException? = null
        for (backend in backends) {
            if (!backend.isAvailable()) continue
            try {
                return backend.extract(uri)
            } catch (e: MlException) {
                MlLog.w("MetadataResolver", "${backend.name} failed for $uri: ${e.message}")
                errors += "${backend.name}: ${e.message}"
                last = e
            }
        }
        throw MlException(
            last?.code ?: ErrorCode.UNSUPPORTED,
            if (errors.isEmpty()) "No metadata backend available" else "All backends failed (${errors.joinToString("; ")})",
            last,
        )
    }
}

/** What this device's hardware/software codecs can do. Computed once from MediaCodecList. */
class MediaCapabilities private constructor(
    private val decoders: Set<String>,
    private val encoders: Set<String>,
) {
    fun canDecode(mime: String): Boolean = mime.lowercase() in decoders
    fun canEncode(mime: String): Boolean = mime.lowercase() in encoders

    /** Best export video codec available: H.264 is required by Phase 1 export; HEVC is reported for the UI. */
    fun supportedExportVideoMimes(): List<String> =
        listOf("video/avc", "video/hevc").filter { canEncode(it) }

    companion object {
        fun detect(): MediaCapabilities {
            val dec = HashSet<String>()
            val enc = HashSet<String>()
            try {
                for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                    val target = if (info.isEncoder) enc else dec
                    for (type in info.supportedTypes) target += type.lowercase()
                }
            } catch (e: Exception) {
                MlLog.e("MediaCapabilities", "codec enumeration failed", e)
            }
            return MediaCapabilities(dec, enc)
        }

        /** For tests. */
        fun of(decoders: Set<String>, encoders: Set<String>) =
            MediaCapabilities(decoders.map { it.lowercase() }.toSet(), encoders.map { it.lowercase() }.toSet())
    }
}
