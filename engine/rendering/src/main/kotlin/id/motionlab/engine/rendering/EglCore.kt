package id.motionlab.engine.rendering

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/**
 * Owns one EGL display + context. All methods must be called from the thread that uses the context
 * (see [RenderThread]). Prefers OpenGL ES 3.0 and falls back to 2.0.
 */
class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    var glesVersion: Int = 0
        private set

    val isReleased: Boolean get() = display == EGL14.EGL_NO_DISPLAY

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw MlException(ErrorCode.GPU, "eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY
            throw MlException(ErrorCode.GPU, "eglInitialize failed: ${errorString()}")
        }
        try {
            if (!tryCreateContext(3) && !tryCreateContext(2)) {
                throw MlException(ErrorCode.GPU, "Could not create an OpenGL ES 2.0 context: ${errorString()}")
            }
        } catch (e: MlException) {
            release()
            throw e
        }
    }

    private fun tryCreateContext(version: Int): Boolean {
        val renderable = if (version >= 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] <= 0) return false
        val ctx = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE), 0,
        )
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return false
        context = ctx
        config = configs[0]
        glesVersion = version
        return true
    }

    fun createWindowSurface(nativeSurface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, nativeSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw MlException(ErrorCode.GPU, "eglCreateWindowSurface failed: ${errorString()}")
        }
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw MlException(ErrorCode.GPU, "eglCreatePbufferSurface failed: ${errorString()}")
        }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw MlException(ErrorCode.GPU, "eglMakeCurrent failed: ${errorString()}")
        }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    /** Returns false if the surface was lost (e.g. the Android Surface was destroyed). */
    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private fun errorString(): String = "0x" + Integer.toHexString(EGL14.eglGetError())
}
