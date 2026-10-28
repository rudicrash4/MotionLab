package id.motionlab.editor.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import id.motionlab.editor.core.PlaybackClock
import id.motionlab.editor.core.Project
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderer untuk GLSurfaceView preview. Pada setiap frame ia membaca waktu dari [PlaybackClock]
 * dan Project terbaru (immutable snapshot), lalu meminta [GlCompositor] menggambar.
 *
 * - Saat memutar: RENDERMODE_CONTINUOUSLY (vsync, target 60 FPS).
 * - Saat jeda: RENDERMODE_WHEN_DIRTY, hanya merender jika ada perubahan (hemat baterai).
 */
class PreviewRenderer(
    context: Context,
    private val clock: PlaybackClock,
    private val projectProvider: () -> Project
) : GLSurfaceView.Renderer {

    @Volatile private var glView: GLSurfaceView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val compositor = GlCompositor(context.applicationContext) { glView?.requestRender() }

    /** Bertambah setiap kali EGL context dibuat ulang; pemutar harus mengambil Surface baru. */
    @Volatile var generation = 0
        private set

    private var width = 1
    private var height = 1

    val errorMessage: String get() = compositor.lastError

    fun attach(view: GLSurfaceView) {
        glView = view
    }

    fun detach(view: GLSurfaceView) {
        if (glView === view) glView = null
    }

    fun requestRender() {
        glView?.requestRender()
    }

    fun setContinuous(continuous: Boolean) {
        val v = glView ?: return
        v.renderMode = if (continuous) GLSurfaceView.RENDERMODE_CONTINUOUSLY else GLSurfaceView.RENDERMODE_WHEN_DIRTY
        if (!continuous) v.requestRender()
    }

    fun setVideoAspect(clipId: String, aspect: Float) {
        compositor.setVideoAspect(clipId, aspect)
        requestRender()
    }

    fun videoAspect(clipId: String): Float? = compositor.videoAspect(clipId)

    /** Meminta Surface untuk klip video. Callback dipanggil di thread utama dengan (surface, generasi). */
    fun requestVideoSurface(clipId: String, callback: (Surface?, Int) -> Unit) {
        val v = glView
        if (v == null) {
            mainHandler.post { callback(null, -1) }
            return
        }
        v.queueEvent {
            val s = try { compositor.createVideoSurface(clipId) } catch (e: Exception) { null }
            val gen = generation
            mainHandler.post { callback(s, gen) }
        }
    }

    fun releaseVideoSurface(clipId: String) {
        glView?.queueEvent {
            try { compositor.releaseVideoSurface(clipId) } catch (_: Exception) { }
        }
    }

    fun shutdown() {
        compositor.shutdown()
    }

    // ---------------------------------------------------------------- GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        compositor.initGl()
        generation++
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onDrawFrame(gl: GL10?) {
        compositor.renderFrame(projectProvider(), clock.positionMs(), width, height)
    }
}
