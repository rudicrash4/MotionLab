package id.motionlab.engine.rendering

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/** A 2D GL texture owned by the render thread's context. */
class GlTexture internal constructor(val id: Int, val width: Int, val height: Int) {
    internal var deleted = false
}

/**
 * Draws textured quads (the building block of the compositor). Needs a current GL context for every call.
 * The quad fills the viewport and is then scaled / rotated / translated by [draw]'s arguments.
 */
class TexturedQuadRenderer : AutoCloseable {
    private val program: Int
    private val positionLoc: Int
    private val texCoordLoc: Int
    private val mvpLoc: Int
    private val alphaLoc: Int
    private val samplerLoc: Int
    private val textures = HashSet<GlTexture>()

    private val vertices = GlUtil.floatBuffer(
        floatArrayOf(
            // x, y, u, v  (v flipped so a Bitmap's top row appears at the top)
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        ),
    )

    init {
        program = GlUtil.linkProgram(VERTEX, FRAGMENT)
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
        alphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        samplerLoc = GLES20.glGetUniformLocation(program, "uTexture")
        if (positionLoc < 0 || texCoordLoc < 0 || mvpLoc < 0 || alphaLoc < 0 || samplerLoc < 0) {
            GLES20.glDeleteProgram(program)
            throw MlException(ErrorCode.GPU, "Shader locations missing")
        }
        GlUtil.checkError("TexturedQuadRenderer.init")
    }

    fun uploadBitmap(bitmap: Bitmap): GlTexture {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) throw MlException(ErrorCode.GPU, "glGenTextures failed")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        try {
            GlUtil.checkError("uploadBitmap")
        } catch (e: MlException) {
            GLES20.glDeleteTextures(1, ids, 0)
            throw e
        }
        return GlTexture(ids[0], bitmap.width, bitmap.height).also { textures += it }
    }

    fun deleteTexture(texture: GlTexture) {
        if (texture.deleted) return
        texture.deleted = true
        textures -= texture
        GLES20.glDeleteTextures(1, intArrayOf(texture.id), 0)
    }

    /**
     * @param scaleX/scaleY size relative to the full viewport (1 = fills it)
     * @param translateX/translateY offset in NDC (-1..1)
     * @param rotationDegrees counter-clockwise rotation
     */
    fun draw(
        texture: GlTexture,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        translateX: Float = 0f,
        translateY: Float = 0f,
        rotationDegrees: Float = 0f,
        alpha: Float = 1f,
    ) {
        if (texture.deleted) throw MlException(ErrorCode.GPU, "Texture already deleted")
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, translateX, translateY, 0f)
        Matrix.rotateM(mvp, 0, rotationDegrees, 0f, 0f, 1f)
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // textures from Bitmap are premultiplied
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
        GLES20.glUniform1f(alphaLoc, alpha.coerceIn(0f, 1f))

        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionLoc)
        vertices.position(2)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(texCoordLoc)
        GlUtil.checkError("draw")
    }

    /** Deletes the program and every texture still alive. Call with the context current. */
    override fun close() {
        for (t in textures.toList()) deleteTexture(t)
        GLES20.glDeleteProgram(program)
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = aTexCoord;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;
            }
        """
    }
}
