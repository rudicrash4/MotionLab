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
import org.robolectric.annotation.GraphicsMode

/**
 * NATIVE graphics mode on purpose: Robolectric's default (LEGACY) mode shadows Paint/StaticLayout with stubs that do not
 * measure or break text, so a wrapping test can never see more than one line there. With the real native text stack
 * these tests validate the layout the app actually produces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        val typo = TypographyConfig(sizePx = 30f)
        val oneLine = renderer.render(TextObject("a", "word word word word", typo))
        val wrapped = renderer.render(TextObject("b", "word word word word", typo, maxWidthPx = oneLine.width / 3))
        assertEquals(oneLine.width / 3, wrapped.width)
        // Guard the premise: without real text measurement both heights are the 1 px minimum and the next check is meaningless.
        assertTrue("single line must have a real height, was ${oneLine.height}", oneLine.height >= 20)
        assertTrue("wrapped ${wrapped.height} px must be taller than single line ${oneLine.height} px", wrapped.height > oneLine.height)
    }

    @Test
    fun layoutHeightGrowsWithTheNumberOfLines() {
        val typo = TypographyConfig(sizePx = 30f)
        val one = renderer.render(TextObject("1", "line", typo)).height
        val three = renderer.render(TextObject("3", "line\nline\nline", typo)).height
        // Line height is constant, so 3 lines are about 3x one line (allow rounding of per-line metrics).
        assertTrue("one=$one three=$three", three >= one * 3 - 3 && three <= one * 3 + 3)
        // Wrapping into 2 narrow columns vs. an explicit 2-line text must agree.
        val wide = renderer.render(TextObject("w", "aaaa bbbb", typo))
        val narrow = renderer.render(TextObject("n", "aaaa bbbb", typo, maxWidthPx = wide.width / 2 + 1))
        val explicit = renderer.render(TextObject("e", "aaaa\nbbbb", typo))
        assertEquals(explicit.height, narrow.height)
    }

    @Test
    fun lineSpacingMultiplierChangesHeight() {
        val tight = renderer.render(TextObject("t", "a\nb", TypographyConfig(sizePx = 30f, lineSpacingMultiplier = 1f))).height
        val loose = renderer.render(TextObject("l", "a\nb", TypographyConfig(sizePx = 30f, lineSpacingMultiplier = 2f))).height
        assertTrue("tight=$tight loose=$loose", loose > tight)
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
    fun unknownFamilyFallsBackToTheDefaultFamilyAndKeepsTheRequestedStyle() {
        val fm = FontManager()
        val bold = fm.resolve("no-such-family", bold = true, italic = false)
        assertTrue("bold request must keep the bold style", bold.isBold)
        assertTrue(!bold.isItalic)
        val italic = fm.resolve("no-such-family", bold = false, italic = true)
        assertTrue(italic.isItalic && !italic.isBold)
        // Fallback proof: text in an unknown family is laid out exactly like the platform default family.
        val typo = TypographyConfig(sizePx = 40f)
        val unknown = renderer.render(TextObject("u", "Fallback", typo.copy(fontFamily = "no-such-family")))
        val default = renderer.render(TextObject("d", "Fallback", typo.copy(fontFamily = "sans-serif")))
        assertEquals(default.width, unknown.width)
        assertEquals(default.height, unknown.height)
    }
}
