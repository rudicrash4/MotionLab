package id.motionlab.engine.rendering

import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What a render pass can use. Valid only on the render thread, inside the callback passed to
 * [RenderThread.call] / [RenderThread.post] / [ContextListener] or while a [FrameLoop] frame runs.
 * Do not keep it across frames: after a context loss it refers to the dead context ([RenderThread.currentContext]
 * returns the new one).
 */
class RenderContext internal constructor(
    val egl: EglCore,
    val capabilities: GpuCapabilities,
    private val owner: RenderThread,
) {
    /** Presents the frame. See [SwapResult]; a lost surface or context is handled by the [RenderThread] already. */
    fun swap(): SwapResult = owner.swapOnRenderThread()

    /** 1 = every vsync, 2 = every second vsync. Re-applied automatically whenever a new window surface is attached. */
    fun setSwapInterval(interval: Int): Boolean = owner.applySwapInterval(interval)
}

/** Called on the render thread. */
interface ContextListener {
    /**
     * A GL context exists and is current: the first one, and every one created after a context loss.
     * Create all GL objects (programs, textures, ...) here.
     */
    fun onContextCreated(ctx: RenderContext)

    /**
     * The context was lost. Every GL object is gone: forget them WITHOUT calling GL (see
     * [TexturedQuadRenderer.abandon], [TextureCache.abandon]). [onContextCreated] follows when rendering continues.
     */
    fun onContextLost()
}

enum class ContextState { NOT_CREATED, READY, LOST, RELEASED }

/** Thread-safe snapshot of the EGL lifecycle, for diagnostics and tests. */
data class RenderStatus(
    val state: ContextState,
    val glesVersion: Int,
    val hasWindowSurface: Boolean,
    val contextCreations: Int,
    val contextLosses: Int,
    val surfaceAttachments: Int,
    val surfaceLosses: Int,
    val gpu: GpuCapabilities?,
    val lastError: String?,
)

/**
 * Dedicated thread that owns the EGL context. Surface lifecycle:
 *  - [attachSurface] when an Android Surface becomes available (SurfaceHolder.surfaceCreated / TextureView)
 *  - [detachSurface] when it is destroyed (always call this before the Surface goes away). The context, and every GL
 *    object living in it, survives: attaching a new surface afterwards is cheap.
 *  - [release] when finished: runs [onRelease] callbacks, destroys the surfaces and the context.
 *
 * Context loss (EGL_CONTEXT_LOST on swap): the EGL objects are torn down without GL calls, [ContextListener.onContextLost]
 * is told, and the next call / frame creates a fresh context, re-wraps the still-valid Android Surface and calls
 * [ContextListener.onContextCreated].
 *
 * All calls are re-entrant: [call] from the render thread itself runs the block inline instead of waiting for itself
 * (the old implementation deadlocked and only escaped through a 10 s timeout).
 */
class RenderThread(name: String = "ml-render") : AutoCloseable {
    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // The fields below are only touched on the render thread.
    private var egl: EglCore? = null
    private var ctx: RenderContext? = null
    private var windowSurface: EGLSurface? = null
    private var nativeSurface: Any? = null
    private var pbufferSurface: EGLSurface? = null
    private var capabilities: GpuCapabilities? = null
    private var listener: ContextListener? = null
    private val releaseCallbacks = ArrayList<() -> Unit>()
    private var desiredSwapInterval = 1
    private var contextCreations = 0
    private var contextLosses = 0
    private var surfaceAttachments = 0
    private var surfaceLosses = 0
    private var lastError: String? = null

    @Volatile
    private var released = false

    @Volatile
    private var status = RenderStatus(ContextState.NOT_CREATED, 0, false, 0, 0, 0, 0, null, null)

    val isReleased: Boolean get() = released

    fun status(): RenderStatus = status

    private fun onRenderThread(): Boolean = Looper.myLooper() === thread.looper

    /**
     * Runs [block] on the render thread with the context current; blocks the caller and returns the result.
     * Called from the render thread itself it runs inline.
     */
    fun <T> call(timeoutMs: Long = 10_000, block: (RenderContext) -> T): T {
        if (released) throw MlException(ErrorCode.GPU, "RenderThread is released")
        if (onRenderThread()) {
            return try {
                block(ensureContext())
            } catch (e: MlException) {
                throw e
            } catch (e: Exception) {
                throw MlException(ErrorCode.GPU, "Render call failed: ${e.message}", e)
            }
        }
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

    /**
     * Posts a reusable [Runnable] (no per-frame allocation, used by [FrameLoop]). Inside it call [currentContext].
     * Returns false if the thread is released.
     */
    fun postRaw(task: Runnable): Boolean = !released && handler.post(task)

    fun postRawDelayed(task: Runnable, delayMs: Long): Boolean = !released && handler.postDelayed(task, delayMs)

    fun removeRaw(task: Runnable) = handler.removeCallbacks(task)

    /** The current context, creating it if needed. Render thread only. */
    fun currentContext(): RenderContext {
        check(onRenderThread()) { "currentContext() must be called on the render thread" }
        return ensureContext()
    }

    /** Registers cleanup run on the render thread, with the context current, during [release]. Dropped on context loss. */
    fun onRelease(callback: () -> Unit) {
        handler.post { releaseCallbacks += callback }
    }

    /**
     * Installs the listener and immediately calls [ContextListener.onContextCreated] with the current context.
     * An exception from that first call is rethrown to the caller.
     */
    fun setContextListener(newListener: ContextListener?) {
        call { c ->
            listener = newListener
            newListener?.onContextCreated(c)
        }
    }

    fun attachSurface(surface: Any) {
        call(SURFACE_TIMEOUT_MS) { c ->
            val core = c.egl
            windowSurface?.let { core.destroySurface(it) }
            windowSurface = null
            nativeSurface = surface
            try {
                openWindowSurface(core, surface)
            } catch (e: Exception) {
                nativeSurface = null
                core.makeCurrent(pbuffer(core))
                throw e
            } finally {
                publish()
            }
        }
    }

    /** Must be called before the Android Surface goes away. Keeps the context and its GL objects. */
    fun detachSurface() {
        if (released) return
        call(SURFACE_TIMEOUT_MS) { c -> closeWindowSurface(c.egl) }
    }

    /**
     * Test hook: runs the real context-loss recovery path (tear down EGL without GL calls, notify the listener,
     * recreate on the next call). It does not reset the GPU; a genuine driver reset cannot be provoked from app code.
     */
    fun simulateContextLoss() {
        call { handleContextLost() }
    }

    // ---- everything below runs on the render thread ----

    internal fun swapOnRenderThread(): SwapResult {
        val core = egl ?: return SwapResult.NO_SURFACE
        val surface = windowSurface ?: return SwapResult.NO_SURFACE
        val result = core.swapBuffers(surface)
        when (result) {
            SwapResult.OK, SwapResult.NO_SURFACE -> Unit
            SwapResult.SURFACE_LOST -> {
                surfaceLosses++
                MlLog.w("RenderThread", "window surface lost; waiting for a new one")
                closeWindowSurface(core)
            }
            SwapResult.CONTEXT_LOST -> {
                MlLog.w("RenderThread", "EGL context lost; recreating on the next frame")
                handleContextLost()
            }
        }
        return result
    }

    internal fun applySwapInterval(interval: Int): Boolean {
        desiredSwapInterval = interval.coerceAtLeast(1)
        val core = egl ?: return true
        return if (windowSurface != null) core.setSwapInterval(desiredSwapInterval) else true
    }

    private fun ensureContext(): RenderContext {
        if (released) throw MlException(ErrorCode.GPU, "RenderThread is released")
        val existing = ctx
        if (existing != null) {
            val core = egl!!
            core.makeCurrent(windowSurface ?: pbuffer(core)) // no-op when already current
            return existing
        }
        return createContext()
    }

    private fun createContext(): RenderContext {
        val core = EglCore()
        egl = core
        try {
            core.makeCurrent(pbuffer(core))
            val caps = GpuCapabilities.query()
            capabilities = caps
            val context = RenderContext(core, caps, this)
            ctx = context
            contextCreations++
            MlLog.i("RenderThread", "GL ${caps.version} on ${caps.renderer} (ES${core.glesVersion}), context #$contextCreations")
            nativeSurface?.let { native ->
                try {
                    openWindowSurface(core, native)
                } catch (e: MlException) {
                    MlLog.w("RenderThread", "could not re-create the window surface", e)
                    nativeSurface = null
                    core.makeCurrent(pbuffer(core))
                }
            }
            publish()
            listener?.let { l ->
                try {
                    l.onContextCreated(context)
                } catch (e: Exception) {
                    lastError = "onContextCreated failed: ${e.message}"
                    MlLog.e("RenderThread", "context listener failed", e)
                    publish()
                }
            }
            return context
        } catch (e: Exception) {
            lastError = "context creation failed: ${e.message}"
            destroyEgl()
            publish()
            throw e as? MlException ?: MlException(ErrorCode.GPU, "Context creation failed: ${e.message}", e)
        }
    }

    private fun openWindowSurface(core: EglCore, native: Any) {
        val s = core.createWindowSurface(native)
        try {
            core.makeCurrent(s)
        } catch (e: Exception) {
            core.destroySurface(s)
            throw e
        }
        windowSurface = s
        surfaceAttachments++
        core.setSwapInterval(desiredSwapInterval)
    }

    private fun closeWindowSurface(core: EglCore) {
        windowSurface?.let { core.destroySurface(it) }
        windowSurface = null
        nativeSurface = null
        core.makeCurrent(pbuffer(core))
        publish()
    }

    /** Context is gone: tell the owner of GL objects, then drop every EGL object. No GL calls. */
    private fun handleContextLost() {
        contextLosses++
        releaseCallbacks.clear() // they reference objects of the dead context; re-register in onContextCreated
        try {
            listener?.onContextLost()
        } catch (e: Exception) {
            MlLog.e("RenderThread", "onContextLost failed", e)
        }
        // nativeSurface is kept on purpose: the Android Surface itself is fine and is re-wrapped in the new context.
        destroyEgl()
        publish()
    }

    private fun destroyEgl() {
        egl?.let { core ->
            try {
                windowSurface?.let { core.destroySurface(it) }
                pbufferSurface?.let { core.destroySurface(it) }
            } finally {
                core.release()
            }
        }
        egl = null
        ctx = null
        windowSurface = null
        pbufferSurface = null
        capabilities = null
    }

    /** A 1x1 pbuffer keeps a context current when no window surface exists (needed for offscreen work). */
    private fun pbuffer(core: EglCore): EGLSurface =
        pbufferSurface ?: core.createPbufferSurface(1, 1).also { pbufferSurface = it }

    private fun publish() {
        val core = egl
        val state = when {
            released -> ContextState.RELEASED
            core != null -> ContextState.READY
            contextCreations == 0 -> ContextState.NOT_CREATED
            else -> ContextState.LOST
        }
        status = RenderStatus(
            state = state,
            glesVersion = core?.glesVersion ?: 0,
            hasWindowSurface = windowSurface != null,
            contextCreations = contextCreations,
            contextLosses = contextLosses,
            surfaceAttachments = surfaceAttachments,
            surfaceLosses = surfaceLosses,
            gpu = capabilities,
            lastError = lastError,
        )
    }

    override fun close() = release()

    fun release() {
        if (released) return
        released = true
        if (onRenderThread()) {
            releaseOnThread()
        } else {
            val latch = CountDownLatch(1)
            val posted = handler.post {
                try {
                    releaseOnThread()
                } finally {
                    latch.countDown()
                }
            }
            if (posted) latch.await(5, TimeUnit.SECONDS)
        }
        thread.quitSafely()
    }

    private fun releaseOnThread() {
        try {
            val core = egl
            if (core != null) {
                try {
                    core.makeCurrent(pbuffer(core))
                } catch (e: Exception) {
                    MlLog.w("RenderThread", "makeCurrent during release failed", e)
                }
                for (cb in releaseCallbacks.asReversed()) {
                    try {
                        cb()
                    } catch (e: Exception) {
                        MlLog.e("RenderThread", "release callback failed", e)
                    }
                }
            }
            releaseCallbacks.clear()
            listener = null
            nativeSurface = null
            destroyEgl()
        } finally {
            publish()
        }
    }

    private companion object {
        /** Surface changes must not block the UI thread for long; one vsync-paced frame is at most ~33 ms. */
        const val SURFACE_TIMEOUT_MS = 3_000L
    }
}
