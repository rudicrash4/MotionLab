package id.motionlab.editor.core

data class PropDef(
    val key: String,
    val label: String,
    val group: String,
    val def: Float,
    val min: Float,
    val max: Float
)

/**
 * Katalog semua properti yang bisa dianimasikan dengan keyframe.
 * Posisi X/Y adalah pecahan lebar/tinggi kanvas dari titik tengah (0 = tengah).
 */
object Props {
    const val X = "x"
    const val Y = "y"
    const val SCALE = "scale"
    const val STRETCH = "stretch"
    const val ROTATION = "rotation"
    const val OPACITY = "opacity"

    const val BRIGHTNESS = "brightness"
    const val CONTRAST = "contrast"
    const val SATURATION = "saturation"
    const val TEMPERATURE = "temperature"
    const val VIGNETTE = "vignette"
    const val LUT = "lut"

    const val WAVE = "wave"
    const val SWIRL = "swirl"
    const val DISPLACE = "displace"
    const val BLUR = "blur"
    const val GLOW = "glow"

    /** Kecepatan pemutaran sumber (kelipatan). Nilai dasar = kecepatan konstan, keyframe = kurva Speed Ramp. */
    const val SPEED = "speed"

    const val GROUP_TRANSFORM = "Transform"
    const val GROUP_COLOR = "Warna"
    const val GROUP_FX = "Efek"
    const val GROUP_SPEED = "Kecepatan"

    val all: List<PropDef> = listOf(
        PropDef(X, "Posisi X", GROUP_TRANSFORM, 0f, -1.5f, 1.5f),
        PropDef(Y, "Posisi Y", GROUP_TRANSFORM, 0f, -1.5f, 1.5f),
        PropDef(SCALE, "Skala", GROUP_TRANSFORM, 1f, 0.05f, 5f),
        PropDef(STRETCH, "Lebar (stretch)", GROUP_TRANSFORM, 1f, 0.1f, 4f),
        PropDef(ROTATION, "Rotasi", GROUP_TRANSFORM, 0f, -360f, 360f),
        PropDef(OPACITY, "Opacity", GROUP_TRANSFORM, 1f, 0f, 1f),

        PropDef(BRIGHTNESS, "Brightness", GROUP_COLOR, 0f, -1f, 1f),
        PropDef(CONTRAST, "Contrast", GROUP_COLOR, 1f, 0f, 2f),
        PropDef(SATURATION, "Saturation", GROUP_COLOR, 1f, 0f, 2f),
        PropDef(TEMPERATURE, "Temperature", GROUP_COLOR, 0f, -1f, 1f),
        PropDef(VIGNETTE, "Vignette", GROUP_COLOR, 0f, 0f, 1f),
        PropDef(LUT, "Intensitas LUT", GROUP_COLOR, 1f, 0f, 1f),

        PropDef(WAVE, "Wave", GROUP_FX, 0f, 0f, 1f),
        PropDef(SWIRL, "Swirl", GROUP_FX, 0f, -1f, 1f),
        PropDef(DISPLACE, "Displacement", GROUP_FX, 0f, 0f, 1f),
        PropDef(BLUR, "Gaussian Blur", GROUP_FX, 0f, 0f, 1f),
        PropDef(GLOW, "Glow", GROUP_FX, 0f, 0f, 1f),

        PropDef(SPEED, "Kecepatan", GROUP_SPEED, 1f, SpeedLimits.MIN, SpeedLimits.MAX)
    )

    private val byKey: Map<String, PropDef> = all.associateBy { it.key }

    fun get(key: String): PropDef? = byKey[key]

    fun def(key: String): Float = byKey[key]?.def ?: 0f

    fun group(group: String): List<PropDef> = all.filter { it.group == group }
}
