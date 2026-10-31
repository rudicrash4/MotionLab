package id.motionlab.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import id.motionlab.core.common.MlLog
import id.motionlab.engine.rendering.ContextListener
import id.motionlab.engine.rendering.FrameLoop
import id.motionlab.engine.rendering.FrameRenderer
import id.motionlab.engine.rendering.RenderContext
import id.motionlab.engine.rendering.RenderThread
import id.motionlab.engine.rendering.TextureCache
import id.motionlab.engine.rendering.TexturedQuadRenderer

/**
 * The GL objects of the demo. Created in [onContextCreated] (also after a context loss), forgotten without GL calls
 * in [onContextLost]. Render thread only.
 */
private class QuadScene : ContextListener, FrameRenderer {
    private var renderer: TexturedQuadRenderer? = null
    private var cache: TextureCache? = null

    @Volatile
    var textureBytes = 0L
        private set

    @Volatile
    var uploads = 0
        private set

    override fun onContextCreated(ctx: RenderContext) {
        val r = TexturedQuadRenderer()
        renderer = r
        cache = TextureCache(r).also { it.getOrUpload(CHECKER_KEY) { checkerBitmap() } }
        publishCounters()
    }

    override fun onContextLost() {
        renderer?.abandon()
        cache?.abandon()
        renderer = null
        cache = null
        publishCounters()
    }

    override fun renderFrame(ctx: RenderContext, frameTimeNs: Long, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.07f, 0.07f, 0.1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val r = renderer ?: return
        val c = cache ?: return
        val texture = c.getOrUpload(CHECKER_KEY) { checkerBitmap() }
        // Time-based, so the speed is the same whatever the frame rate: 90 degrees per second.
        val angle = ((frameTimeNs / 1_000_000L) % 4_000L) * 0.09f
        val aspect = width.toFloat() / height.toFloat()
        // Keep the quad square on screen whatever the surface shape is.
        val sx = if (aspect >= 1f) 0.6f / aspect else 0.6f
        val sy = if (aspect >= 1f) 0.6f else 0.6f * aspect
        r.draw(texture, scaleX = sx, scaleY = sy, rotationDegrees = angle)
    }

    /** Releases GL objects with the context current. */
    fun close() {
        cache?.close()
        renderer?.close()
        cache = null
        renderer = null
        publishCounters()
    }

    private fun publishCounters() {
        textureBytes = renderer?.liveTextureBytes ?: 0L
        uploads = cache?.uploads ?: 0
    }

    private fun checkerBitmap(): Bitmap {
        val size = 8
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val on = (x + y) % 2 == 0
                bmp.setPixel(x, y, if (on) Color.rgb(255, 140 + y * 14, 40) else Color.rgb(30, 60 + x * 18, 200))
            }
        }
        return bmp
    }

    private companion object {
        const val CHECKER_KEY = "checker-8x8"
    }
}

/**
 * On-screen proof of the rendering foundation: a SurfaceView rendered by [RenderThread] (EGL + GLES), driven by a
 * [FrameLoop] on the render thread (vsync-paced via eglSwapInterval), drawing a procedurally generated texture on a
 * rotating quad.
 *
 * Lifecycle: the thread and its GL objects live from the first surfaceCreated to onDetachedFromWindow. A surface that
 * is destroyed and recreated (rotation, app switch) only stops/starts the loop and re-attaches an EGL window surface.
 * The loop also stops while the window is not visible, so nothing renders in the background.
 */
class RenderDemoView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var thread: RenderThread? = null
    private var loop: FrameLoop? = null
    private var scene: QuadScene? = null
    private var surfaceReady = false
    private var visible = true

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        try {
            val t = thread ?: createPipeline()
            t.attachSurface(holder.surface)
            updateLoop()
        } catch (e: Exception) {
            MlLog.e("RenderDemoView", "GL setup failed", e)
            teardown()
        }
    }

    private fun createPipeline(): RenderThread {
        val t = RenderThread("ml-render-demo")
        val s = QuadScene()
        try {
            t.setContextListener(s)
        } catch (e: Exception) {
            t.release()
            throw e
        }
        val hz = display?.refreshRate ?: 60f
        thread = t
        scene = s
        loop = FrameLoop(t, hz, s)
        return t
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        loop?.setViewport(w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        loop?.stop()
        try {
            thread?.detachSurface() // must happen before the Surface is invalidated
        } catch (e: Exception) {
            MlLog.w("RenderDemoView", "detach failed", e)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visible = visibility == View.VISIBLE
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        teardown()
    }

    private fun updateLoop() {
        val l = loop ?: return
        if (surfaceReady && visible) l.start() else l.stop()
    }

    private fun teardown() {
        loop?.stop()
        loop = null
        val t = thread
        val s = scene
        thread = null
        scene = null
        if (t != null) {
            try {
                if (s != null && !t.isReleased) t.call { s.close() }
            } catch (e: Exception) {
                MlLog.w("RenderDemoView", "scene cleanup failed", e)
            }
            t.release()
        }
    }

    /** Text for the on-screen overlay. Every number comes from measurements made by [FrameLoop] / [RenderThread]. */
    fun diagnosticsText(): String {
        val t = thread ?: return "No render thread (surface not created)."
        val l = loop ?: return "No frame loop."
        val d = l.diagnostics()
        val s = d.stats
        val st = t.status()
        val gpu = st.gpu
        return buildString {
            appendLine("Loop: ${if (d.running) "running" else "stopped"}   display ${"%.1f".format(d.refreshHz)} Hz   swap interval ${d.swapInterval}")
            appendLine("Target ${"%.0f".format(s.targetFps)} fps   measured ${"%.1f".format(s.fps)} fps (last ${s.windowFrames} frames)")
            appendLine("Frame ms: avg ${"%.2f".format(s.avgFrameMs)}  p95 ${"%.2f".format(s.p95FrameMs)}  max ${"%.2f".format(s.maxFrameMs)}")
            appendLine("CPU render ms: avg ${"%.2f".format(s.avgCpuMs)}   swap wait ms: avg ${"%.2f".format(s.avgSwapMs)}")
            appendLine("Frames ${s.totalFrames}   dropped ${s.droppedFrames}   pacing sleeps ${d.pacingSleeps}   render errors ${d.renderErrors}")
            appendLine("Surface ${width}x${height}   texture memory ${scene?.textureBytes ?: 0} B (${scene?.uploads ?: 0} upload(s))")
            appendLine("EGL ${st.state}  ES ${st.glesVersion}  window surface ${if (st.hasWindowSurface) "yes" else "no"}")
            appendLine("Contexts created ${st.contextCreations}  lost ${st.contextLosses}  surfaces attached ${st.surfaceAttachments}  lost ${st.surfaceLosses}")
            st.lastError?.let { appendLine("Last error: $it") }
            appendLine("GPU ${gpu?.renderer ?: "?"} / ${gpu?.version ?: "?"}")
            append(
                "GPU time: " + if (gpu?.hasDisjointTimerQuery == true) {
                    "driver advertises GL_EXT_disjoint_timer_query but the Android SDK has no binding for it, so it is not measured"
                } else {
                    "not available (no GL_EXT_disjoint_timer_query); swap wait above includes vsync wait and is not GPU time"
                },
            )
        }
    }
}
