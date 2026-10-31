package id.motionlab.engine.rendering

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real GPU test: renders a 2x2 colour texture into an offscreen 64x64 framebuffer and reads the pixels back.
 * Needs a device or emulator with OpenGL ES (software GLES in emulators is fine).
 */
@RunWith(AndroidJUnit4::class)
class RenderingInstrumentedTest {
    private fun readPixel(x: Int, y: Int): Int {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        return Color.argb(
            buf.get(3).toInt() and 0xFF,
            buf.get(0).toInt() and 0xFF,
            buf.get(1).toInt() and 0xFF,
            buf.get(2).toInt() and 0xFF,
        )
    }

    private fun near(expected: Int, actual: Int, tolerance: Int): Boolean =
        kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    @Test
    fun texturedQuadRendersExpectedColours() {
        val thread = RenderThread("test-render")
        try {
            val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, Color.RED)
                setPixel(1, 0, Color.GREEN)
                setPixel(0, 1, Color.BLUE)
                setPixel(1, 1, Color.WHITE)
            }
            val pixels = thread.call { ctx ->
                assertTrue(ctx.capabilities.maxTextureSize >= 64)
                val fbo = IntArray(1)
                val tex = IntArray(1)
                GLES20.glGenFramebuffers(1, fbo, 0)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 64, 64, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
                assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE, GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER))
                GLES20.glViewport(0, 0, 64, 64)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val renderer = TexturedQuadRenderer()
                val texture = renderer.uploadBitmap(bmp)
                renderer.draw(texture)
                // Sample well inside each quadrant (linear filtering blends near the centre).
                // GL's origin is bottom-left, so bitmap row 1 (BLUE, WHITE) is at the bottom.
                val result = listOf(readPixel(8, 8), readPixel(56, 8), readPixel(8, 56), readPixel(56, 56))
                renderer.close()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteFramebuffers(1, fbo, 0)
                GLES20.glDeleteTextures(1, tex, 0)
                result
            }
            assertTrue("bottom-left should be blue, was ${Integer.toHexString(pixels[0])}", near(Color.BLUE, pixels[0], 40))
            assertTrue("bottom-right should be white, was ${Integer.toHexString(pixels[1])}", near(Color.WHITE, pixels[1], 40))
            assertTrue("top-left should be red, was ${Integer.toHexString(pixels[2])}", near(Color.RED, pixels[2], 40))
            assertTrue("top-right should be green, was ${Integer.toHexString(pixels[3])}", near(Color.GREEN, pixels[3], 40))
        } finally {
            thread.release()
        }
    }

    @Test
    fun renderThreadReleasesCleanlyAndRejectsFurtherCalls() {
        val thread = RenderThread("test-render-2")
        assertTrue(thread.call { it.egl.glesVersion >= 2 })
        thread.release()
        thread.release() // idempotent
        try {
            thread.call { }
            throw AssertionError("expected failure after release")
        } catch (e: MlException) {
            assertEquals(ErrorCode.GPU, e.code)
        }
    }

    @Test
    fun invalidShaderIsReportedNotCrashed() {
        val thread = RenderThread("test-render-3")
        try {
            val message = try {
                thread.call { GlUtil.compileShader(GLES20.GL_VERTEX_SHADER, "this is not glsl") }
                null
            } catch (e: MlException) {
                e.message
            }
            assertTrue(message != null && message.contains("Shader compile failed"))
        } finally {
            thread.release()
        }
    }
}
