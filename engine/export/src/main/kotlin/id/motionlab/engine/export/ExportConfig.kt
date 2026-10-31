package id.motionlab.engine.export

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

data class ExportConfig(
    val outputPath: String,
    /** Output height in pixels; width follows the source aspect ratio. null = keep source size. */
    val outputHeight: Int? = null,
    val videoMimeType: String = "video/avc",
    val audioMimeType: String = "audio/mp4a-latm",
    /** null = encoder default. */
    val videoBitrate: Int? = null,
    val frameRate: Int = 30,
) {
    fun validate() {
        if (outputPath.isBlank()) throw MlException(ErrorCode.INVALID_ARGUMENT, "outputPath is blank")
        if (!outputPath.endsWith(".mp4", ignoreCase = true)) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Phase 1 exports MP4 only")
        }
        outputHeight?.let { if (it !in 144..4320) throw MlException(ErrorCode.INVALID_ARGUMENT, "outputHeight out of range") }
        videoBitrate?.let { if (it < 100_000) throw MlException(ErrorCode.INVALID_ARGUMENT, "videoBitrate too low") }
        if (frameRate !in 1..120) throw MlException(ErrorCode.INVALID_ARGUMENT, "frameRate out of range")
    }
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Succeeded(val outputPath: String, val sizeBytes: Long, val durationMs: Long) : ExportState
    data class Failed(val error: MlException) : ExportState
    data object Cancelled : ExportState
}
