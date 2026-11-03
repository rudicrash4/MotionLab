package id.motionlab.engine.effects

import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbAdjustment
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EffectsTest {
    private val registry = BuiltInEffects.createRegistry()

    private fun expectError(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: MlException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun registryListsBuiltInsAndRejectsDuplicates() {
        assertEquals(listOf("brightness", "contrast", "rgb_adjust"), registry.all().map { it.id })
        expectError(ErrorCode.INVALID_ARGUMENT) { registry.register(BuiltInEffects.definitions.first()) }
    }

    @Test
    fun instanceValidationCatchesBadInput() {
        assertTrue(registry.validate(EffectInstance("brightness", mapOf("value" to 0.5f))).isEmpty())
        assertEquals(1, registry.validate(EffectInstance("brightness", mapOf("value" to 2f))).size)
        assertEquals(1, registry.validate(EffectInstance("brightness", mapOf("nope" to 0f))).size)
        assertEquals(1, registry.validate(EffectInstance("brightness", mapOf("value" to Float.NaN))).size)
        assertEquals(1, registry.validate(EffectInstance("ghost")).size)
        expectError(ErrorCode.INVALID_ARGUMENT) { registry.create("contrast", mapOf("value" to -5f)) }
    }

    @Test
    fun defaultsAreUsedWhenValueMissing() {
        val inst = registry.create("rgb_adjust", mapOf("red" to 2f))
        assertEquals(2f, registry.valueOf(inst, "red"), 0f)
        assertEquals(1f, registry.valueOf(inst, "green"), 0f)
    }

    @Test
    fun mapsToTheExpectedMedia3Classes() {
        val factory = Media3EffectFactory(registry)
        assertTrue(factory.create(registry.create("brightness", mapOf("value" to 0.2f))) is Brightness)
        assertTrue(factory.create(registry.create("contrast")) is Contrast)
        assertTrue(factory.create(registry.create("rgb_adjust", mapOf("blue" to 0.5f))) is RgbAdjustment)
        assertEquals(3, factory.createAll(listOf(EffectInstance("brightness"), EffectInstance("contrast"), EffectInstance("rgb_adjust"))).size)
    }

    @Test
    fun unregisteredEffectCannotBeMapped() {
        val factory = Media3EffectFactory(registry)
        expectError(ErrorCode.INVALID_ARGUMENT) { factory.create(EffectInstance("ghost")) }
    }
}
