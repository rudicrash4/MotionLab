package id.motionlab.engine.effects

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/** One numeric, range-limited parameter of an effect. Phase 1 supports float parameters only. */
data class ParamSpec(
    val key: String,
    val displayName: String,
    val min: Float,
    val max: Float,
    val default: Float,
) {
    init {
        require(min <= max) { "min must be <= max" }
        require(default in min..max) { "default must be within min..max" }
    }
}

/** Static description of an effect type (what the UI lists and the registry validates against). */
data class EffectDefinition(
    val id: String,
    val displayName: String,
    val params: List<ParamSpec>,
) {
    init {
        require(id.isNotBlank()) { "effect id must not be blank" }
        require(params.map { it.key }.toSet().size == params.size) { "duplicate parameter keys in $id" }
    }
}

/** A configured use of an effect on a clip. Stores only values that differ from the definition's defaults. */
data class EffectInstance(val effectId: String, val values: Map<String, Float> = emptyMap())

/**
 * Catalog of effect definitions. Creating an instance validates parameter names and ranges, so invalid
 * values can never reach a GPU pipeline.
 */
class EffectRegistry {
    private val definitions = LinkedHashMap<String, EffectDefinition>()

    @Synchronized
    fun register(definition: EffectDefinition) {
        if (definitions.containsKey(definition.id)) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Effect already registered: ${definition.id}")
        }
        definitions[definition.id] = definition
    }

    @Synchronized
    fun get(id: String): EffectDefinition? = definitions[id]

    @Synchronized
    fun all(): List<EffectDefinition> = definitions.values.toList()

    /** Returns problems with [instance] (empty = valid). */
    fun validate(instance: EffectInstance): List<String> {
        val def = get(instance.effectId) ?: return listOf("unknown effect '${instance.effectId}'")
        val problems = ArrayList<String>()
        for ((key, value) in instance.values) {
            val spec = def.params.firstOrNull { it.key == key }
            when {
                spec == null -> problems += "unknown parameter '$key' for ${def.id}"
                value.isNaN() || value.isInfinite() -> problems += "'$key' must be finite"
                value < spec.min || value > spec.max -> problems += "'$key'=$value is outside ${spec.min}..${spec.max}"
            }
        }
        return problems
    }

    fun create(effectId: String, values: Map<String, Float> = emptyMap()): EffectInstance {
        val instance = EffectInstance(effectId, values)
        val problems = validate(instance)
        if (problems.isNotEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, problems.joinToString("; "))
        return instance
    }

    /** Value of [key] for [instance], falling back to the definition default. */
    fun valueOf(instance: EffectInstance, key: String): Float {
        val def = get(instance.effectId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown effect ${instance.effectId}")
        val spec = def.params.firstOrNull { it.key == key }
            ?: throw MlException(ErrorCode.INVALID_ARGUMENT, "Unknown parameter '$key' for ${def.id}")
        return instance.values[key] ?: spec.default
    }
}
