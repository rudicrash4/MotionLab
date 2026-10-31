package id.motionlab.engine.compositing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositingTest {
    private val eps = 1e-5f

    @Test
    fun channelBlendFormulas() {
        assertEquals(0.5f, Blend.channel(BlendMode.NORMAL, 0.2f, 0.5f), eps)
        assertEquals(0.25f, Blend.channel(BlendMode.MULTIPLY, 0.5f, 0.5f), eps)
        assertEquals(0.75f, Blend.channel(BlendMode.SCREEN, 0.5f, 0.5f), eps)
        assertEquals(0.2f, Blend.channel(BlendMode.DARKEN, 0.2f, 0.5f), eps)
        assertEquals(0.5f, Blend.channel(BlendMode.LIGHTEN, 0.2f, 0.5f), eps)
        assertEquals(1f, Blend.channel(BlendMode.ADD, 0.8f, 0.9f), eps)
        // overlay: dark backdrop multiplies, light backdrop screens
        assertEquals(2f * 0.25f * 0.5f, Blend.channel(BlendMode.OVERLAY, 0.25f, 0.5f), eps)
        assertEquals(1f - 2f * (1f - 0.75f) * (1f - 0.5f), Blend.channel(BlendMode.OVERLAY, 0.75f, 0.5f), eps)
    }

    @Test
    fun multiplyByWhiteAndScreenWithBlackAreIdentity() {
        for (c in listOf(0f, 0.3f, 1f)) {
            assertEquals(c, Blend.channel(BlendMode.MULTIPLY, c, 1f), eps)
            assertEquals(c, Blend.channel(BlendMode.SCREEN, c, 0f), eps)
        }
    }

    @Test
    fun opaqueNormalReplacesBackdrop() {
        val out = Blend.rgba(BlendMode.NORMAL, floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f))
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), out, eps)
    }

    @Test
    fun halfAlphaNormalOverOpaqueMixesColors() {
        val out = Blend.rgba(BlendMode.NORMAL, floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0.5f))
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f, 1f), out, eps)
    }

    @Test
    fun transparentSourceKeepsBackdropAndEmptyStaysEmpty() {
        val kept = Blend.rgba(BlendMode.MULTIPLY, floatArrayOf(0.2f, 0.4f, 0.6f, 1f), floatArrayOf(1f, 1f, 1f, 0f))
        assertArrayEquals(floatArrayOf(0.2f, 0.4f, 0.6f, 1f), kept, eps)
        val empty = Blend.rgba(BlendMode.NORMAL, floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 0f))
        assertEquals(0f, empty[3], 0f)
    }

    @Test
    fun rectMaskHardAndFeathered() {
        val hard = RectMask(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(1f, hard.coverage(0.5f, 0.5f), 0f)
        assertEquals(0f, hard.coverage(0.1f, 0.5f), 0f)

        val soft = RectMask(0f, 0f, 1f, 1f, feather = 0.2f)
        assertEquals(0f, soft.coverage(0f, 0.5f), eps)
        assertEquals(1f, soft.coverage(0.5f, 0.5f), eps)
        val mid = soft.coverage(0.1f, 0.5f)
        assertTrue(mid > 0f && mid < 1f)
    }

    @Test
    fun ellipseAndCombinators() {
        val e = EllipseMask(0.5f, 0.5f, 0.25f, 0.25f)
        assertEquals(1f, e.coverage(0.5f, 0.5f), 0f)
        assertEquals(0f, e.coverage(0.9f, 0.5f), 0f)
        assertEquals(0f, InvertedMask(e).coverage(0.5f, 0.5f), 0f)
        assertEquals(1f, InvertedMask(e).coverage(0.9f, 0.5f), 0f)

        val both = IntersectionMask(listOf(e, RectMask(0.5f, 0f, 1f, 1f)))
        assertEquals(1f, both.coverage(0.6f, 0.5f), 0f)
        assertEquals(0f, both.coverage(0.4f, 0.5f), 0f)
    }
}
