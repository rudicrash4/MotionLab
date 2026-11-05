package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.AssetRefDto
import kotlin.math.min

/** What adding an imported asset to the timeline will create. Pure, so it can be unit-tested. */
data class AssetPlacement(val trackKind: TrackKind, val durationUs: Long, val sourceDurationUs: Long?, val payload: Map<String, String>)

object AssetPlanner {
    const val DEFAULT_IMAGE_DURATION_US = 5_000_000L

    fun plan(asset: AssetRefDto, canvasW: Int, canvasH: Int): AssetPlacement {
        fun fit(): Map<String, String> {
            if (asset.width <= 0 || asset.height <= 0) return emptyMap()
            val scale = min(canvasW.toDouble() / asset.width, canvasH.toDouble() / asset.height)
            return mapOf("w" to (asset.width * scale).toInt().coerceAtLeast(1).toString(), "h" to (asset.height * scale).toInt().coerceAtLeast(1).toString())
        }
        return when (asset.kind) {
            "IMAGE" -> AssetPlacement(TrackKind.IMAGE, DEFAULT_IMAGE_DURATION_US, null, fit())
            "VIDEO" -> {
                if (asset.durationUs <= 0) throw MlException(ErrorCode.UNSUPPORTED, "'${asset.displayName ?: asset.id}' has no readable duration")
                AssetPlacement(TrackKind.VIDEO, asset.durationUs, asset.durationUs, fit())
            }
            "AUDIO" -> {
                if (asset.durationUs <= 0) throw MlException(ErrorCode.UNSUPPORTED, "'${asset.displayName ?: asset.id}' has no readable duration")
                AssetPlacement(TrackKind.AUDIO, asset.durationUs, asset.durationUs, emptyMap())
            }
            else -> throw MlException(ErrorCode.UNSUPPORTED, "${asset.kind} assets cannot be placed on the timeline (fonts are used by text layers)")
        }
    }
}
