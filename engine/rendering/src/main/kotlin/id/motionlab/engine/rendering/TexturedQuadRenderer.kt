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

    /** Approximate GPU memory: RGBA8, no mipmaps. */
    val sizeBytes: Long get() = width.toLong() * height * 4
}

/**
 * Draws textured quads (the building block of the compositor). Needs a current GL context for every call.
 * The quad fills the viewport and is then scaled / rotated / translated by [draw]'s arguments.
 *
 * Per-frame cost: no allocation (matrix scratch array and vertex buffer are reused; the vertices live in a VBO)
 * and no glGetError unless [GlUtil.debugChecks] is on.
 */
class TexturedQuadRenderer : AutoCloseable {
    private val program: Int
    private val positionLoc: Int
    private val texCoordLoc: Int
    private val mvpLoc: Int
    private val alphaLoc: Int
    private val samplerLoc: Int
    private val vbo: Int
    private val textures = HashSet<GlTexture>()
    private val mvp = FloatArray(16)
    private val idScratch = IntArray(1)
    private var abandoned = false
    private var closed = false

    var liveTextureCount: Int = 0
        private set
    var liveTextureBytes: Long = 0
        private set

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
        // Vertex data uploaded once to a VBO instead of being copied from client memory on every draw.
        val vertices = GlUtil.floatBuffer(
            floatArrayOf(
                // x, y, u, v  (v flipped so a Bitmap's top row appears at the top)
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            ),
        )
        GLES20.glGenBuffers(1, idScratch, 0)
        vbo = idScratch[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 16 * 4, vertices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        try {
            GlUtil.checkError("TexturedQuadRenderer.init")
        } catch (e: MlException) {
            GLES20.glDeleteProgram(program)
            idScratch[0] = vbo
            GLES20.glDeleteBuffers(1, idScratch, 0)
            throw e
        }
    }

    fun uploadBitmap(bitmap: Bitmap): GlTexture {
        check(!closed && !abandoned) { "renderer is closed" }
        GLES20.glGenTextures(1, idScratch, 0)
        val id = idScratch[0]
        if (id == 0) throw MlException(ErrorCode.GPU, "glGenTextures failed")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        try {
            GlUtil.checkError("uploadBitmap")
        } catch (e: MlException) {
            idScratch[0] = id
            GLES20.glDeleteTextures(1, idScratch, 0)
            throw e
        }
        val texture = GlTexture(id, bitmap.width, bitmap.height)
        textures += texture
        liveTextureCount++
        liveTextureBytes += texture.sizeBytes
        return texture
    }

    fun deleteTexture(texture: GlTexture) {
        if (texture.deleted) return
        texture.deleted = true
        if (textures.remove(texture)) {
            liveTextureCount--
            liveTextureBytes -= texture.sizeBytes
        }
        if (!abandoned) {
            idScratch[0] = texture.id
            GLES20.glDeleteTextures(1, idScratch, 0)
        }
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
        check(!closed && !abandoned) { "renderer is closed" }
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, translateX, translateY, 0f)
        Matrix.rotateM(mvp, 0, rotationDegrees, 0f, 0f, 1f)
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f)
        drawMatrix(texture, mvp, alpha)
    }

    /**
     * Draws the quad with a complete column-major 4x4 matrix (16 floats) that maps the unit quad (-1..1) to clip
     * space. Needed for transforms [draw] cannot express (skew, anchor points, parent hierarchies). Same cost and
     * state handling as [draw]; [draw] itself is unchanged in behaviour.
     */
    fun drawMatrix(texture: GlTexture, matrix: FloatArray, alpha: Float = 1f) {
        check(!closed && !abandoned) { "renderer is closed" }
        require(matrix.size >= 16) { "matrix needs 16 floats" }
        if (texture.deleted) throw MlException(ErrorCode.GPU, "Texture already deleted")

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // textures from Bitmap are premultiplied
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, matrix, 0)
        GLES20.glUniform1f(alphaLoc, alpha.coerceIn(0f, 1f))

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (GlUtil.debugChecks) GlUtil.checkError("draw")
    }

    /**
     * Forget every GL object WITHOUT calling GL. Use after the context was lost: the objects no longer exist and
     * calling glDelete* on stale names could delete unrelated objects in the new context.
     */
    fun abandon() {
        abandoned = true
        for (t in textures) t.deleted = true
        textures.clear()
        liveTextureCount = 0
        liveTextureBytes = 0
    }

    /** Deletes the program, the VBO and every texture still alive. Call with the context current. */
    override fun close() {
        if (closed) return
        closed = true
        if (abandoned) return
        for (t in textures.toList()) deleteTexture(t)
        idScratch[0] = vbo
        GLES20.glDeleteBuffers(1, idScratch, 0)
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
