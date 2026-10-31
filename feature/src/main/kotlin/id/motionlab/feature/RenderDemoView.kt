package id.motionlab.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import id.motionlab.core.common.MlLog
import id.motionlab.engine.rendering.GlTexture
import id.motionlab.engine.rendering.RenderThread
import id.motionlab.engine.rendering.TexturedQuadRenderer

/**
 * On-screen proof of the rendering foundation: a SurfaceView whose surface is rendered by [RenderThread]
 * (EGL + GLES shaders), drawing a procedurally generated texture on a rotating quad.
 */
class RenderDemoView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private var thread: RenderThread? = null
    private var running = false
    private var angle = 0f
    private var width = 1
    private var height = 1

    // Touched only on the render thread:
    private var renderer: TexturedQuadRenderer? = null
    private var texture: GlTexture? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val t = RenderThread("ml-render-demo")
        thread = t
        try {
            t.attachSurface(holder.surface)
            t.call { _ ->
                val r = TexturedQuadRenderer()
                renderer = r
                texture = r.uploadBitmap(checkerBitmap())
            }
            t.onRelease {
                renderer?.close()
                renderer = null
                texture = null
            }
            running = true
            Choreographer.getInstance().postFrameCallback(this)
        } catch (e: Exception) {
            MlLog.e("RenderDemoView", "GL setup failed", e)
            t.release()
            thread = null
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        thread?.let {
            try {
                it.detachSurface() // must happen before the Surface is invalidated
            } catch (e: Exception) {
                MlLog.w("RenderDemoView", "detach failed", e)
            }
            it.release()
        }
        thread = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val t = thread ?: return
        angle = (angle + 1.5f) % 360f
        val a = angle
        val w = width
        val h = height
        t.post { _ ->
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0.07f, 0.07f, 0.1f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val tex = texture
            if (tex != null) {
                val aspect = w.toFloat() / h.toFloat()
                // Keep the quad square on screen whatever the surface shape is.
                val sx = if (aspect >= 1f) 0.6f / aspect else 0.6f
                val sy = if (aspect >= 1f) 0.6f else 0.6f * aspect
                renderer?.draw(tex, scaleX = sx, scaleY = sy, rotationDegrees = a)
            }
            t.swap()
        }
        Choreographer.getInstance().postFrameCallback(this)
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
}
