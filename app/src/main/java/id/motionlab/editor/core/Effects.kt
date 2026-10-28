package id.motionlab.editor.core

/** Kumpulan efek & warna sebuah klip (untuk salin/tempel dan preset). */
data class EffectSet(
    val props: Map<String, Float>,
    val keyframes: Map<String, KeyframeTrack>,
    val disabled: Set<String>,
    val order: List<String>,
    val lutUri: String
)

enum class EffectPreset(val label: String, val values: Map<String, Float>) {
    CINEMATIC("Cinematic", mapOf(Props.CONTRAST to 1.15f, Props.SATURATION to 1.1f, Props.TEMPERATURE to 0.15f, Props.VIGNETTE to 0.35f)),
    VINTAGE("Vintage", mapOf(Props.SATURATION to 0.7f, Props.TEMPERATURE to 0.35f, Props.CONTRAST to 0.92f, Props.VIGNETTE to 0.5f, Props.BRIGHTNESS to 0.03f)),
    NOIR("Noir", mapOf(Props.SATURATION to 0f, Props.CONTRAST to 1.3f, Props.VIGNETTE to 0.6f)),
    DREAMY("Dreamy", mapOf(Props.BLUR to 0.12f, Props.GLOW to 0.5f, Props.BRIGHTNESS to 0.05f, Props.SATURATION to 1.15f)),
    GLITCH_POP("Glitch Pop", mapOf(Props.WAVE to 0.3f, Props.DISPLACE to 0.25f, Props.SATURATION to 1.3f, Props.CONTRAST to 1.1f)),
    WARM_GLOW("Warm Glow", mapOf(Props.TEMPERATURE to 0.4f, Props.GLOW to 0.35f, Props.VIGNETTE to 0.2f))
}

object Effects {
    /** Semua kunci properti warna + efek. */
    val keys: List<String> = (Props.group(Props.GROUP_COLOR) + Props.group(Props.GROUP_FX)).map { it.key }

    fun capture(c: Clip): EffectSet {
        val p = HashMap<String, Float>()
        val k = HashMap<String, KeyframeTrack>()
        for (key in keys) {
            c.props[key]?.let { p[key] = it }
            c.keyframes[key]?.let { k[key] = it }
        }
        return EffectSet(p, k, c.fxDisabled, c.fxOrder, c.lutUri)
    }

    /** Menimpa efek klip [c] dengan [s]; properti transform & keyframe non-efek tidak disentuh. */
    fun apply(c: Clip, s: EffectSet): Clip {
        val props = HashMap(c.props)
        val kf = HashMap(c.keyframes)
        for (key in keys) { props.remove(key); kf.remove(key) }
        props.putAll(s.props)
        for ((key, t) in s.keyframes) {
            // Keyframe di luar durasi klip tujuan dibuang; nilai tetap kontinu karena bernilai tetap di ujung.
            val frames = t.frames.filter { it.timeMs <= c.durationMs }
            if (frames.isNotEmpty()) kf[key] = KeyframeTrack(frames)
        }
        return c.copy(props = props, keyframes = kf, fxDisabled = s.disabled, fxOrder = s.order, lutUri = s.lutUri)
    }

    fun applyPreset(c: Clip, preset: EffectPreset): Clip {
        val props = HashMap(c.props)
        val kf = HashMap(c.keyframes)
        for (key in keys) { props.remove(key); kf.remove(key) }
        props.putAll(preset.values)
        return c.copy(props = props, keyframes = kf, fxDisabled = emptySet())
    }

    fun clear(c: Clip): Clip {
        val props = HashMap(c.props)
        val kf = HashMap(c.keyframes)
        for (key in keys) { props.remove(key); kf.remove(key) }
        return c.copy(props = props, keyframes = kf, fxDisabled = emptySet(), lutUri = "")
    }
}
