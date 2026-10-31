package id.motionlab.engine.effects

import androidx.media3.common.Effect
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbAdjustment
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/**
 * The effects MotionLab Phase 1 can actually run, all executed by Media3's OpenGL effect pipeline
 * (the same pipeline is used for export). Custom GLSL effects are NOT implemented yet.
 */
object BuiltInEffects {
    const val BRIGHTNESS = "brightness"
    const val CONTRAST = "contrast"
    const val RGB_ADJUST = "rgb_adjust"

    val definitions: List<EffectDefinition> = listOf(
        EffectDefinition(
            BRIGHTNESS, "Brightness",
            listOf(ParamSpec("value", "Brightness", -1f, 1f, 0f)),
        ),
        EffectDefinition(
            CONTRAST, "Contrast",
            listOf(ParamSpec("value", "Contrast", -1f, 1f, 0f)),
        ),
        EffectDefinition(
            RGB_ADJUST, "RGB scale",
            listOf(
                ParamSpec("red", "Red", 0f, 4f, 1f),
                ParamSpec("green", "Green", 0f, 4f, 1f),
                ParamSpec("blue", "Blue", 0f, 4f, 1f),
            ),
        ),
    )

    fun createRegistry(): EffectRegistry = EffectRegistry().also { r -> definitions.forEach(r::register) }
}

/** Translates validated [EffectInstance]s into Media3 video effects. */
class Media3EffectFactory(private val registry: EffectRegistry) {
    fun create(instance: EffectInstance): Effect {
        val problems = registry.validate(instance)
        if (problems.isNotEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, problems.joinToString("; "))
        return when (instance.effectId) {
            BuiltInEffects.BRIGHTNESS -> Brightness(registry.valueOf(instance, "value"))
            BuiltInEffects.CONTRAST -> Contrast(registry.valueOf(instance, "value"))
            BuiltInEffects.RGB_ADJUST -> RgbAdjustment.Builder()
                .setRedScale(registry.valueOf(instance, "red"))
                .setGreenScale(registry.valueOf(instance, "green"))
                .setBlueScale(registry.valueOf(instance, "blue"))
                .build()
            else -> throw MlException(ErrorCode.UNSUPPORTED, "No Media3 mapping for effect '${instance.effectId}'")
        }
    }

    fun createAll(instances: List<EffectInstance>): List<Effect> = instances.map(::create)
}
