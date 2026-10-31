package id.motionlab.engine.text

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextTest {
    private val renderer = TextRenderer(FontManager())

    @Test
    fun typographyValidation() {
        assertTrue(TypographyConfig().validate().isEmpty())
        assertEquals(3, TypographyConfig(sizePx = 0f, letterSpacingEm = 9f, lineSpacingMultiplier = 0f).validate().size)
    }

    @Test
    fun renderProducesBitmapWithPositiveSize() {
        val bmp = renderer.render(TextObject("t", "Hello", TypographyConfig(sizePx = 40f)))
        assertTrue(bmp.width > 0)
        assertTrue(bmp.height > 0)
    }

    @Test
    fun wrappingWidthIsHonouredAndMoreLinesAreTaller() {
        val oneLine = renderer.render(TextObject("a", "word word word word", TypographyConfig(sizePx = 30f)))
        val wrapped = renderer.render(
            TextObject("b", "word word word word", TypographyConfig(sizePx = 30f), maxWidthPx = oneLine.width / 3),
        )
        assertEquals(oneLine.width / 3, wrapped.width)
        assertTrue(wrapped.height > oneLine.height)
    }

    @Test
    fun invalidInputIsRejected() {
        try {
            renderer.render(TextObject("e", ""))
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
        try {
            renderer.render(TextObject("e", "x", TypographyConfig(sizePx = 5000f)))
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun registeringMissingOrBrokenFontFailsGracefully() {
        val fm = FontManager()
        val missing = fm.registerFontFile("x", File("/nope/font.ttf")) as MlResult.Failure
        assertEquals(ErrorCode.NOT_FOUND, missing.error.code)

        val junk = File.createTempFile("badfont", ".ttf")
        try {
            junk.writeText("this is not a font")
            val r = fm.registerFontFile("junk", junk)
            // Behaviour of createFromFile on garbage differs between Robolectric/device; either way it must not throw.
            assertTrue(r is MlResult.Failure || fm.registeredNames().contains("junk"))
        } finally {
            junk.delete()
        }
    }

    @Test
    fun unknownFamilyFallsBackToADefaultTypeface() {
        assertTrue(FontManager().resolve("no-such-family", bold = true, italic = false) != null)
    }
}
