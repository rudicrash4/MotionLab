package id.motionlab.engine.rendering

import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What a render pass can use. Valid only inside the callback passed to [RenderThread.post]/[RenderThread.call]. */
class RenderContext internal constructor(val egl: EglCore, val capabilities: GpuCapabilities)

/**
 * Dedicated thread that owns the EGL context. Surface lifecycle:
 *  - [attachSurface] when an Android Surface becomes available (SurfaceHolder.surfaceCreated / TextureView)
 *  - [detachSurface] when it is destroyed (always call this before the Surface goes away)
 *  - [release] when finished: runs [onRelease] callbacks, destroys the surfaces and the context.
 */
class RenderThread(name: String = "ml-render") : AutoCloseable {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    // The fields below are only touched on the render thread.
    private var egl: EglCore? = null
    private var windowSurface: EGLSurface? = null
    private var pbufferSurface: EGLSurface? = null
    private var capabilities: GpuCapabilities? = null
    private val releaseCallbacks = ArrayList<() -> Unit>()

    @Volatile
    private var released = false

    /** Runs [block] on the render thread with the context current; blocks the caller and returns the result. */
    fun <T> call(timeoutMs: Long = 10_000, block: (RenderContext) -> T): T {
        if (released) throw MlException(ErrorCode.GPU, "RenderThread is released")
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        val posted = handler.post {
            result.set(runCatching { block(ensureContext()) })
            latch.countDown()
        }
        if (!posted) throw MlException(ErrorCode.GPU, "RenderThread is not running")
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw MlException(ErrorCode.GPU, "Render call timed out")
        return result.get().getOrElse { e ->
            throw (e as? MlException ?: MlException(ErrorCode.GPU, "Render call failed: ${e.message}", e))
        }
    }

    /** Fire-and-forget version; failures are logged. */
    fun post(block: (RenderContext) -> Unit) {
        if (released) return
        handler.post {
            try {
                block(ensureContext())
            } catch (e: Exception) {
                MlLog.e("RenderThread", "render task failed", e)
            }
        }
    }

    /** Registers cleanup run on the render thread, with the context current, during [release]. */
    fun onRelease(callback: () -> Unit) {
        handler.post { releaseCallbacks += callback }
    }

    fun attachSurface(nativeSurface: Any) {
        call { ctx ->
            windowSurface?.let { ctx.egl.destroySurface(it) }
            windowSurface = ctx.egl.createWindowSurface(nativeSurface).also { ctx.egl.makeCurrent(it) }
        }
    }

    fun detachSurface() {
        if (released) return
        call { ctx ->
            windowSurface?.let { ctx.egl.destroySurface(it) }
            windowSurface = null
            ctx.egl.makeCurrent(pbuffer(ctx.egl))
        }
    }

    /** Presents the current frame to the attached surface. Returns false if no surface is attached or it was lost. */
    fun swap(): Boolean = call { ctx ->
        val s = windowSurface ?: return@call false
        ctx.egl.swapBuffers(s)
    }

    private fun ensureContext(): RenderContext {
        val core = egl ?: EglCore().also {
            egl = it
            it.makeCurrent(pbuffer(it))
            capabilities = GpuCapabilities.query()
            MlLog.i("RenderThread", "GL ${capabilities?.version} on ${capabilities?.renderer} (ES${it.glesVersion})")
        }
        val surface = windowSurface
        if (surface != null) core.makeCurrent(surface) else core.makeCurrent(pbuffer(core))
        return RenderContext(core, capabilities!!)
    }

    /** A 1x1 pbuffer keeps a context current when no window surface exists (needed for offscreen work). */
    private fun pbuffer(core: EglCore): EGLSurface =
        pbufferSurface ?: core.createPbufferSurface(1, 1).also { pbufferSurface = it }

    override fun close() = release()

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                egl?.let { core ->
                    pbufferSurface?.let { core.makeCurrent(it) }
                    for (cb in releaseCallbacks.asReversed()) {
                        try {
                            cb()
                        } catch (e: Exception) {
                            MlLog.e("RenderThread", "release callback failed", e)
                        }
                    }
                    windowSurface?.let { core.destroySurface(it) }
                    pbufferSurface?.let { core.destroySurface(it) }
                    core.release()
                }
            } finally {
                egl = null
                windowSurface = null
                pbufferSurface = null
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}
