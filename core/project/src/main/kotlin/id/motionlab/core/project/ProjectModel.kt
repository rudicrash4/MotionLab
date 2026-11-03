package id.motionlab.core.project

import kotlinx.serialization.Serializable

/**
 * On-disk project format (DTOs). It is deliberately separate from the engine's runtime model so the file
 * format can be versioned and migrated without touching the engine. Mapping lives in engine:timeline.
 */
const val CURRENT_SCHEMA_VERSION = 2

@Serializable
data class ProjectConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRateNumerator: Int = 30,
    val frameRateDenominator: Int = 1,
    val durationUs: Long = 0,
) {
    val frameRate: Double get() = frameRateNumerator.toDouble() / frameRateDenominator.toDouble()

    fun validate(): List<String> = buildList {
        if (width !in 16..8192) add("width must be within 16..8192")
        if (height !in 16..8192) add("height must be within 16..8192")
        if (frameRateNumerator <= 0 || frameRateDenominator <= 0) add("frame rate must be positive")
        else if (frameRate !in 1.0..240.0) add("frame rate must be within 1..240 fps")
        if (durationUs < 0) add("durationUs must be >= 0")
    }
}

@Serializable
data class AssetRefDto(
    val id: String,
    val uri: String,
    /** VIDEO, AUDIO, IMAGE, FONT or OTHER (kept as a string so unknown future kinds still load). */
    val kind: String,
    val displayName: String? = null,
)

@Serializable
data class KeyframeDto(
    val timeUs: Long,
    val value: Double,
    /** "hold", "linear" or "bezier"; the four control values are only used for "bezier". */
    val interpolation: String = "linear",
    val x1: Double = 0.0,
    val y1: Double = 0.0,
    val x2: Double = 1.0,
    val y2: Double = 1.0,
    /** Stable keyframe id (schema 2). Empty in schema 1 files: the loader generates one. */
    val id: String = "",
)

@Serializable
data class PathPointDto(
    val id: String,
    val x: Double,
    val y: Double,
    val inDx: Double = 0.0,
    val inDy: Double = 0.0,
    val outDx: Double = 0.0,
    val outDy: Double = 0.0,
)

@Serializable
data class MotionPathDto(
    val points: List<PathPointDto>,
    val rotateAlongPath: Boolean = false,
)

/** Record of an animation preset that was applied (the keyframes themselves live in `properties`). */
@Serializable
data class PresetDto(val presetId: String, val startUs: Long, val durationUs: Long)

@Serializable
data class ClipDto(
    val id: String,
    val assetId: String? = null,
    val startUs: Long,
    val durationUs: Long,
    val sourceStartUs: Long = 0,
    val properties: Map<String, List<KeyframeDto>> = emptyMap(),
    // ---- schema 2 ----
    /** Fixed (non-animated) property values. */
    val constants: Map<String, Double> = emptyMap(),
    val sourceDurationUs: Long? = null,
    /** Content of generated clips: text, color, shape, w, h ... */
    val payload: Map<String, String> = emptyMap(),
    val motionPath: MotionPathDto? = null,
    val presets: List<PresetDto> = emptyList(),
)

@Serializable
data class TrackDto(
    val id: String,
    val name: String,
    val kind: String,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val clips: List<ClipDto> = emptyList(),
    // ---- schema 2 ----
    val layerId: String? = null,
    val visible: Boolean = true,
    val collapsed: Boolean = false,
)

/** A transform-hierarchy node (layer / group / null object). Animated in timeline time. */
@Serializable
data class LayerDto(
    val id: String,
    val name: String,
    /** CONTENT, GROUP or NULL. */
    val type: String = "CONTENT",
    val parentId: String? = null,
    val properties: Map<String, List<KeyframeDto>> = emptyMap(),
    val constants: Map<String, Double> = emptyMap(),
    val motionPath: MotionPathDto? = null,
    val presets: List<PresetDto> = emptyList(),
    val visible: Boolean = true,
    val locked: Boolean = false,
    val collapsed: Boolean = false,
)

@Serializable
data class ProjectDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    val config: ProjectConfig = ProjectConfig(),
    val assets: List<AssetRefDto> = emptyList(),
    val tracks: List<TrackDto> = emptyList(),
    /** Schema 2: the layer hierarchy. */
    val layers: List<LayerDto> = emptyList(),
)
