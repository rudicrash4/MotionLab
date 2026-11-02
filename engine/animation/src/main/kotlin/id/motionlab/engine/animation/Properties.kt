package id.motionlab.engine.animation

/**
 * Describes one animatable property: how it is shown and edited, and its default. New effects register their own
 * definitions at start-up; nothing else needs to change for the editor to list, edit, keyframe and save them
 * (values are stored by name).
 */
data class PropertyDef(
    val name: String,
    val displayName: String,
    val defaultValue: Double,
    /** Group shown in the property panel ("Transform", "Opacity", ...). */
    val group: String,
    val unit: String = "",
    /** Hard limits applied when the user edits the value; null = unbounded. */
    val min: Double? = null,
    val max: Double? = null,
    /** Suggested UI step for sliders / nudging. */
    val step: Double = 1.0,
) {
    init {
        require(name.isNotBlank()) { "property name must not be blank" }
        require(defaultValue.isFinite()) { "default must be finite" }
        require(min == null || max == null || min <= max) { "min must be <= max" }
    }

    fun clamp(value: Double): Double {
        var v = value
        if (min != null && v < min) v = min
        if (max != null && v > max) v = max
        return v
    }
}

/** Registry of animatable properties. Thread-safe. */
object PropertyRegistry {
    private val defs = LinkedHashMap<String, PropertyDef>()

    init {
        register(PropertyDef(PropertyNames.POSITION_X, "Position X", 0.0, "Transform", "px", step = 1.0))
        register(PropertyDef(PropertyNames.POSITION_Y, "Position Y", 0.0, "Transform", "px", step = 1.0))
        register(PropertyDef(PropertyNames.SCALE_X, "Scale X", 1.0, "Transform", "x", min = -20.0, max = 20.0, step = 0.01))
        register(PropertyDef(PropertyNames.SCALE_Y, "Scale Y", 1.0, "Transform", "x", min = -20.0, max = 20.0, step = 0.01))
        register(PropertyDef(PropertyNames.ROTATION, "Rotation", 0.0, "Transform", "deg", step = 1.0))
        register(PropertyDef(PropertyNames.ANCHOR_X, "Anchor X", 0.0, "Transform", "px", step = 1.0))
        register(PropertyDef(PropertyNames.ANCHOR_Y, "Anchor Y", 0.0, "Transform", "px", step = 1.0))
        register(PropertyDef(PropertyNames.SKEW_X, "Skew X", 0.0, "Transform", "deg", min = -85.0, max = 85.0, step = 1.0))
        register(PropertyDef(PropertyNames.SKEW_Y, "Skew Y", 0.0, "Transform", "deg", min = -85.0, max = 85.0, step = 1.0))
        register(PropertyDef(PropertyNames.OPACITY, "Opacity", 1.0, "Opacity", "", min = 0.0, max = 1.0, step = 0.01))
        register(PropertyDef(PropertyNames.MOTION_PROGRESS, "Path progress", 0.0, "Motion path", "", min = 0.0, max = 1.0, step = 0.01))
    }

    @Synchronized
    fun register(def: PropertyDef) {
        defs[def.name] = def
    }

    @Synchronized
    fun get(name: String): PropertyDef? = defs[name]

    @Synchronized
    fun all(): List<PropertyDef> = defs.values.toList()

    @Synchronized
    fun defaults(): Map<String, Double> = defs.mapValues { it.value.defaultValue }

    fun defaultOf(name: String): Double = get(name)?.defaultValue ?: 0.0

    /** The standard 2D transform properties in display order. */
    val TRANSFORM: List<String> = listOf(
        PropertyNames.POSITION_X, PropertyNames.POSITION_Y,
        PropertyNames.SCALE_X, PropertyNames.SCALE_Y,
        PropertyNames.ROTATION,
        PropertyNames.ANCHOR_X, PropertyNames.ANCHOR_Y,
        PropertyNames.SKEW_X, PropertyNames.SKEW_Y,
        PropertyNames.OPACITY,
    )
}
