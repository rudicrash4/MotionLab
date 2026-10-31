package id.motionlab.engine.rendering

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/** Outcome of presenting a frame. */
enum class SwapResult {
    OK,

    /** No window surface is attached (nothing to present to). */
    NO_SURFACE,

    /** The window surface became invalid (e.g. the Android Surface was destroyed). Attach a new one. */
    SURFACE_LOST,

    /** The GL context was lost (driver reset / GPU hang). All GL objects are gone and must be recreated. */
    CONTEXT_LOST,
}

/**
 * Owns one EGL display + context. All methods must be called from the thread that uses the context
 * (see [RenderThread]). Prefers OpenGL ES 3.0 and falls back to 2.0.
 */
class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var current: EGLSurface? = null

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

    /** No-op when [surface] is already current: eglMakeCurrent is not free, and it used to run on every frame. */
    fun makeCurrent(surface: EGLSurface) {
        if (current === surface) return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            current = null
            throw MlException(ErrorCode.GPU, "eglMakeCurrent failed: ${errorString()}")
        }
        current = surface
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        current = null
    }

    /** Applies to the current draw surface: 1 = every vsync, 2 = every second vsync, ... */
    fun setSwapInterval(interval: Int): Boolean = EGL14.eglSwapInterval(display, interval)

    fun swapBuffers(surface: EGLSurface): SwapResult {
        if (EGL14.eglSwapBuffers(display, surface)) return SwapResult.OK
        return when (EGL14.eglGetError()) {
            EGL14.EGL_CONTEXT_LOST -> SwapResult.CONTEXT_LOST
            else -> SwapResult.SURFACE_LOST // EGL_BAD_SURFACE, EGL_BAD_NATIVE_WINDOW, ...
        }
    }

    fun destroySurface(surface: EGLSurface) {
        if (current === surface) makeNothingCurrent()
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
        current = null
    }

    private fun errorString(): String = "0x" + Integer.toHexString(EGL14.eglGetError())
}
