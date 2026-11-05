package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a clip is composited over what is below it.
 *
 * [exactOnGpu] says whether the preview renderer can reproduce the mode with fixed-function GL blending on an opaque
 * destination (exact for Normal, Multiply, Screen, Add). DARKEN, LIGHTEN and OVERLAY need a shader that reads the
 * destination colour (GL_MIN/GL_MAX ignore alpha and would blacken transparent pixels), which the current renderer does
 * not have, so they are stored and listed but flagged as NOT supported: the UI must not offer them as working modes and
 * the renderer draws them as NORMAL.
 */
enum class BlendMode(val label: String, val exactOnGpu: Boolean) {
    NORMAL("Normal", true),
    MULTIPLY("Multiply", true),
    SCREEN("Screen", true),
    ADD("Add", true),
    DARKEN("Darken", false),
    LIGHTEN("Lighten", false),
    OVERLAY("Overlay", false),
    ;

    companion object {
        fun parse(text: String?): BlendMode = entries.firstOrNull { it.name == text } ?: NORMAL
    }
}

enum class MaskShape { RECTANGLE, ELLIPSE }

/**
 * A non-destructive mask on a clip. All geometry is NORMALISED to the clip's content box so it survives resizing:
 * [cx]/[cy] are the mask centre as a fraction of the content size from its centre (0 = centred), [w]/[h] its size as a
 * fraction of the content size (1 = full), [feather] the soft edge as a fraction of the shorter content side.
 * [opacity] is how strongly the mask applies (1 = fully hides what is outside, 0 = no effect).
 */
data class MaskSpec(
    val shape: MaskShape = MaskShape.RECTANGLE,
    val enabled: Boolean = true,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    val w: Double = 0.6,
    val h: Double = 0.6,
    val rotationDeg: Double = 0.0,
    val opacity: Double = 1.0,
    val feather: Double = 0.0,
    val inverted: Boolean = false,
) {
    init {
        require(w > 0 && h > 0) { "mask size must be > 0" }
        require(opacity in 0.0..1.0) { "mask opacity must be within 0..1" }
        require(feather in 0.0..1.0) { "mask feather must be within 0..1" }
        require(listOf(cx, cy, w, h, rotationDeg, opacity, feather).all { it.isFinite() }) { "mask values must be finite" }
    }

    /** True when this mask changes anything on screen. */
    val isActive: Boolean get() = enabled && opacity > 0.0

    fun toPayload(base: Map<String, String>): Map<String, String> = base.filterKeys { !it.startsWith(KEY) } + mapOf(
        KEY to shape.name, "$KEY.on" to enabled.toString(), "$KEY.cx" to cx.toString(), "$KEY.cy" to cy.toString(),
        "$KEY.w" to w.toString(), "$KEY.h" to h.toString(), "$KEY.rot" to rotationDeg.toString(),
        "$KEY.opacity" to opacity.toString(), "$KEY.feather" to feather.toString(), "$KEY.inv" to inverted.toString(),
    )

    companion object {
        const val KEY = "mask"

        fun fromPayload(p: Map<String, String>): MaskSpec? {
            val shape = MaskShape.entries.firstOrNull { it.name == p[KEY] } ?: return null
            fun d(k: String, def: Double) = p["$KEY.$k"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: def
            return try {
                MaskSpec(
                    shape, p["$KEY.on"] != "false", d("cx", 0.0), d("cy", 0.0), d("w", 0.6).coerceAtLeast(0.001), d("h", 0.6).coerceAtLeast(0.001),
                    d("rot", 0.0), d("opacity", 1.0).coerceIn(0.0, 1.0), d("feather", 0.0).coerceIn(0.0, 1.0), p["$KEY.inv"] == "true",
                )
            } catch (e: IllegalArgumentException) {
                null // a damaged mask must never stop a project from opening: the clip simply has no mask
            }
        }

        fun remove(p: Map<String, String>): Map<String, String> = p.filterKeys { !it.startsWith(KEY) }
    }
}

const val BLEND_KEY = "blend"

/** Blend mode and mask live in the clip payload, which is already persisted, copied by split/duplicate and undoable. */
val MediaClip.blendMode: BlendMode get() = BlendMode.parse(payload[BLEND_KEY])
val MediaClip.mask: MaskSpec? get() = MaskSpec.fromPayload(payload)

class SetBlendMode(val clipIds: List<String>, val mode: BlendMode) : Command {
    override val label get() = "Blend mode"
    override fun apply(state: EditorState): EditorState {
        if (clipIds.isEmpty()) invalid("No clips selected")
        var tl = state.timeline
        for (id in clipIds) {
            val (track, clip) = requireClipEditable(tl, id)
            val payload = if (mode == BlendMode.NORMAL) clip.payload - BLEND_KEY else clip.payload + (BLEND_KEY to mode.name)
            tl = tl.updateTrack(track.id) { it.replaceClip(clip.copy(payload = payload)) }
        }
        return state.withTimeline(tl)
    }
}

/** Sets (or, with null, removes) the mask of one clip. Gesture-friendly: absolute, so it can be previewed. */
class SetMask(val clipId: String, val mask: MaskSpec?) : Command {
    override val label get() = if (mask == null) "Remove mask" else "Edit mask"
    override val mergeKey get() = "mask:$clipId"
    override fun apply(state: EditorState): EditorState {
        val (track, clip) = requireClipEditable(state.timeline, clipId)
        val payload = mask?.toPayload(clip.payload) ?: MaskSpec.remove(clip.payload)
        return state.withTimeline(state.timeline.updateTrack(track.id) { it.replaceClip(clip.copy(payload = payload)) })
    }
}

/** Duplicates a whole track (all its clips get new ids and fresh keyframe ids) and puts the copy just above it. */
class DuplicateTrack(val trackId: String, val newTrackId: String, val newClipIds: List<String>) : Command {
    override val label get() = "Duplicate layer"
    override fun apply(state: EditorState): EditorState {
        val tl = state.timeline
        val src = tl.track(trackId)
        if (newClipIds.size != src.clips.size) invalid("One new id per clip is required")
        val copy = Track(
            id = newTrackId,
            name = src.name + " copy",
            kind = src.kind,
            clips = src.clips.mapIndexed { i, c -> c.duplicatedAs(newClipIds[i], c.startUs) },
            muted = src.muted,
            locked = false,
            layerId = src.layerId,
            visible = src.visible,
            collapsed = src.collapsed,
        )
        return EditorState(tl.insertTrack(copy, tl.trackIndex(trackId) + 1), Selection(clipIds = newClipIds.toSet()))
    }
}

/** Coverage of a [MaskSpec] over a width x height content bitmap, one alpha byte per pixel (255 = fully visible). */
object MaskRasterizer {
    fun alpha(spec: MaskSpec, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "size must be > 0" }
        val out = ByteArray(width * height)
        val rad = Math.toRadians(-spec.rotationDeg)
        val cosR = cos(rad)
        val sinR = sin(rad)
        val hw = spec.w * width / 2.0
        val hh = spec.h * height / 2.0
        val featherPx = max(spec.feather * min(width, height), 1.0)
        val mcx = width / 2.0 + spec.cx * width
        val mcy = height / 2.0 + spec.cy * height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x + 0.5 - mcx
                val dy = y + 0.5 - mcy
                val px = dx * cosR - dy * sinR
                val py = dx * sinR + dy * cosR
                val sd = when (spec.shape) {
                    MaskShape.RECTANGLE -> {
                        val qx = abs(px) - hw
                        val qy = abs(py) - hh
                        sqrt(max(qx, 0.0) * max(qx, 0.0) + max(qy, 0.0) * max(qy, 0.0)) + min(max(qx, qy), 0.0)
                    }
                    MaskShape.ELLIPSE -> {
                        val k = sqrt((px / hw) * (px / hw) + (py / hh) * (py / hh))
                        (k - 1.0) * min(hw, hh)
                    }
                }
                val t = (0.5 - sd / featherPx).coerceIn(0.0, 1.0)
                var cov = t * t * (3 - 2 * t)
                if (spec.inverted) cov = 1.0 - cov
                // opacity 1 = mask fully applied, 0 = untouched
                val a = 1.0 - spec.opacity * (1.0 - cov)
                out[y * width + x] = (a * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
