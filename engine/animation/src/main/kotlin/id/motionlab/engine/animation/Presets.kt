package id.motionlab.engine.animation

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

enum class PresetCategory(val label: String) {
    FADE("Fade"),
    SLIDE("Slide"),
    SCALE("Scale"),
    ROTATION("Rotation"),
    BOUNCE("Bounce"),
    SMOOTH_MOVEMENT("Smooth movement"),
}

/** Where in the owner's time the preset runs. */
enum class PresetAnchor {
    /** Starts at local time 0 (the beginning of the clip). */
    IN,

    /** Ends at the end of the clip. */
    OUT,

    /** Spans the whole clip. */
    WHOLE,
}

/** Record of a preset that was applied, kept with the owner so it survives save/load. */
data class AppliedPreset(val presetId: String, val startUs: Long, val durationUs: Long)

/**
 * A preset is nothing but a recipe that produces ordinary keyframes: after applying it, the keyframes are
 * indistinguishable from hand-made ones (visible on the timeline, editable in the graph editor, undoable).
 * Values assume the property is at its default rest value (centre position, scale 1, opacity 1, rotation 0).
 */
class AnimationPreset(
    val id: String,
    val name: String,
    val category: PresetCategory,
    val anchor: PresetAnchor,
    val defaultDurationUs: Long,
    private val builder: (startUs: Long, durationUs: Long, canvasWidth: Int, canvasHeight: Int) -> Map<String, List<Keyframe>>,
) {
    /** Time range this preset occupies on a clip of [clipDurationUs]; the duration is capped to the clip. */
    fun range(clipDurationUs: Long, requestedDurationUs: Long = defaultDurationUs): Pair<Long, Long> {
        val d = if (anchor == PresetAnchor.WHOLE) clipDurationUs else requestedDurationUs.coerceIn(1L, clipDurationUs)
        val start = if (anchor == PresetAnchor.OUT) clipDurationUs - d else 0L
        return start to d
    }

    fun build(clipDurationUs: Long, canvasWidth: Int, canvasHeight: Int, requestedDurationUs: Long = defaultDurationUs): PresetResult {
        if (clipDurationUs <= 0) throw MlException(ErrorCode.INVALID_ARGUMENT, "clip duration must be > 0")
        val (start, duration) = range(clipDurationUs, requestedDurationUs)
        return PresetResult(
            AppliedPreset(id, start, duration),
            builder(start, duration, canvasWidth, canvasHeight),
        )
    }
}

class PresetResult(val applied: AppliedPreset, val keyframes: Map<String, List<Keyframe>>)

object PresetLibrary {
    private val presets = LinkedHashMap<String, AnimationPreset>()

    @Synchronized
    fun register(preset: AnimationPreset) {
        presets[preset.id] = preset
    }

    @Synchronized
    fun get(id: String): AnimationPreset? = presets[id]

    @Synchronized
    fun all(): List<AnimationPreset> = presets.values.toList()

    fun byCategory(category: PresetCategory): List<AnimationPreset> = all().filter { it.category == category }

    private fun kf(t: Long, v: Double, i: Interpolation = Interpolation.Linear) = Keyframe(t, v, i)

    /** Two keyframes from [from] to [to] across the preset range. */
    private fun ramp(prop: String, start: Long, d: Long, from: Double, to: Double, i: Interpolation) =
        prop to listOf(kf(start, from, i), kf(start + d, to))

    private val OVERSHOOT = Interpolation.Bezier(0.34, 1.56, 0.64, 1.0)

    init {
        register(AnimationPreset("fade_in", "Fade in", PresetCategory.FADE, PresetAnchor.IN, 700_000) { s, d, _, _ ->
            mapOf(ramp(PropertyNames.OPACITY, s, d, 0.0, 1.0, Easing.EASE_OUT))
        })
        register(AnimationPreset("fade_out", "Fade out", PresetCategory.FADE, PresetAnchor.OUT, 700_000) { s, d, _, _ ->
            mapOf(ramp(PropertyNames.OPACITY, s, d, 1.0, 0.0, Easing.EASE_IN))
        })

        register(AnimationPreset("slide_in_left", "Slide in from left", PresetCategory.SLIDE, PresetAnchor.IN, 600_000) { s, d, w, _ ->
            mapOf(ramp(PropertyNames.POSITION_X, s, d, -w.toDouble(), 0.0, Easing.EASE_OUT))
        })
        register(AnimationPreset("slide_in_right", "Slide in from right", PresetCategory.SLIDE, PresetAnchor.IN, 600_000) { s, d, w, _ ->
            mapOf(ramp(PropertyNames.POSITION_X, s, d, w.toDouble(), 0.0, Easing.EASE_OUT))
        })
        register(AnimationPreset("slide_in_top", "Slide in from top", PresetCategory.SLIDE, PresetAnchor.IN, 600_000) { s, d, _, h ->
            mapOf(ramp(PropertyNames.POSITION_Y, s, d, -h.toDouble(), 0.0, Easing.EASE_OUT))
        })
        register(AnimationPreset("slide_in_bottom", "Slide in from bottom", PresetCategory.SLIDE, PresetAnchor.IN, 600_000) { s, d, _, h ->
            mapOf(ramp(PropertyNames.POSITION_Y, s, d, h.toDouble(), 0.0, Easing.EASE_OUT))
        })
        register(AnimationPreset("slide_out_right", "Slide out to right", PresetCategory.SLIDE, PresetAnchor.OUT, 600_000) { s, d, w, _ ->
            mapOf(ramp(PropertyNames.POSITION_X, s, d, 0.0, w.toDouble(), Easing.EASE_IN))
        })
        register(AnimationPreset("slide_out_left", "Slide out to left", PresetCategory.SLIDE, PresetAnchor.OUT, 600_000) { s, d, w, _ ->
            mapOf(ramp(PropertyNames.POSITION_X, s, d, 0.0, -w.toDouble(), Easing.EASE_IN))
        })

        register(AnimationPreset("scale_pop_in", "Pop in", PresetCategory.SCALE, PresetAnchor.IN, 500_000) { s, d, _, _ ->
            mapOf(
                ramp(PropertyNames.SCALE_X, s, d, 0.0, 1.0, OVERSHOOT),
                ramp(PropertyNames.SCALE_Y, s, d, 0.0, 1.0, OVERSHOOT),
            )
        })
        register(AnimationPreset("scale_out", "Shrink out", PresetCategory.SCALE, PresetAnchor.OUT, 500_000) { s, d, _, _ ->
            mapOf(
                ramp(PropertyNames.SCALE_X, s, d, 1.0, 0.0, Easing.EASE_IN),
                ramp(PropertyNames.SCALE_Y, s, d, 1.0, 0.0, Easing.EASE_IN),
            )
        })
        register(AnimationPreset("slow_zoom", "Slow zoom in", PresetCategory.SCALE, PresetAnchor.WHOLE, 0) { s, d, _, _ ->
            mapOf(
                ramp(PropertyNames.SCALE_X, s, d, 1.0, 1.15, Interpolation.Linear),
                ramp(PropertyNames.SCALE_Y, s, d, 1.0, 1.15, Interpolation.Linear),
            )
        })

        register(AnimationPreset("spin_in", "Spin in", PresetCategory.ROTATION, PresetAnchor.IN, 700_000) { s, d, _, _ ->
            mapOf(
                ramp(PropertyNames.ROTATION, s, d, -180.0, 0.0, Easing.EASE_OUT),
                ramp(PropertyNames.OPACITY, s, d, 0.0, 1.0, Easing.EASE_OUT),
            )
        })
        register(AnimationPreset("rotate_360", "Full turn", PresetCategory.ROTATION, PresetAnchor.IN, 1_000_000) { s, d, _, _ ->
            mapOf(ramp(PropertyNames.ROTATION, s, d, 0.0, 360.0, Easing.EASE_IN_OUT))
        })

        register(AnimationPreset("bounce_in", "Bounce in", PresetCategory.BOUNCE, PresetAnchor.IN, 1_000_000) { s, d, _, h ->
            fun at(f: Double) = s + (d * f).toLong()
            val hh = h.toDouble()
            mapOf(
                PropertyNames.POSITION_Y to listOf(
                    kf(at(0.0), -hh / 2, Easing.EASE_IN),
                    kf(at(0.5), 0.0, Easing.EASE_OUT),
                    kf(at(0.72), -hh / 8, Easing.EASE_IN),
                    kf(at(0.86), 0.0, Easing.EASE_OUT),
                    kf(at(0.94), -hh / 30, Easing.EASE_IN),
                    kf(at(1.0), 0.0),
                ),
            )
        })
        register(AnimationPreset("pulse", "Pulse", PresetCategory.BOUNCE, PresetAnchor.IN, 600_000) { s, d, _, _ ->
            fun scale(): List<Keyframe> = listOf(
                kf(s, 1.0, Easing.EASE_OUT),
                kf(s + d * 2 / 5, 1.25, Easing.EASE_IN_OUT),
                kf(s + d * 7 / 10, 0.92, Easing.EASE_IN_OUT),
                kf(s + d, 1.0),
            )
            mapOf(PropertyNames.SCALE_X to scale(), PropertyNames.SCALE_Y to scale())
        })

        register(AnimationPreset("drift_right", "Smooth drift right", PresetCategory.SMOOTH_MOVEMENT, PresetAnchor.WHOLE, 0) { s, d, w, _ ->
            mapOf(ramp(PropertyNames.POSITION_X, s, d, 0.0, w / 8.0, Easing.EASE_IN_OUT))
        })
        register(AnimationPreset("float", "Gentle float", PresetCategory.SMOOTH_MOVEMENT, PresetAnchor.WHOLE, 0) { s, d, _, h ->
            mapOf(
                PropertyNames.POSITION_Y to listOf(
                    kf(s, 0.0, Easing.EASE_IN_OUT),
                    kf(s + d / 2, -h / 40.0, Easing.EASE_IN_OUT),
                    kf(s + d, 0.0),
                ),
            )
        })
    }
}
