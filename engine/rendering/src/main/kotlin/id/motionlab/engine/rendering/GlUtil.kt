package id.motionlab.engine.rendering

import android.opengl.GLES20
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun checkError(where: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw MlException(ErrorCode.GPU, "GL error 0x${Integer.toHexString(err)} at $where")
    }

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw MlException(ErrorCode.GPU, "glCreateShader failed")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw MlException(ErrorCode.GPU, "Shader compile failed: $log")
        }
        return shader
    }

    fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (e: MlException) {
            GLES20.glDeleteShader(vs)
            throw e
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        // Shaders are no longer needed once linked (or failed to link).
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw MlException(ErrorCode.GPU, "Program link failed: $log")
        }
        return program
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }
}

/** Snapshot of the GL driver, used for adaptive quality decisions. Must be created with a current context. */
data class GpuCapabilities(
    val vendor: String,
    val renderer: String,
    val version: String,
    val maxTextureSize: Int,
) {
    companion object {
        fun query(): GpuCapabilities {
            val size = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
            return GpuCapabilities(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "",
                renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "",
                version = GLES20.glGetString(GLES20.GL_VERSION) ?: "",
                maxTextureSize = size[0],
            )
        }
    }
}
