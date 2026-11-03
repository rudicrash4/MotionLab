package id.motionlab.engine.rendering

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render loop, surface recreation, EGL lifecycle, texture upload, resource cleanup and long-duration stability.
 * Presents to an ImageReader surface (a real window surface with a real consumer), so no Activity is needed.
 * None of these tests assert a frame rate: emulators run software GLES with unpredictable timing. Measured numbers
 * are written to logcat (tag MlRenderTest) for RENDERING_AUDIT.md.
 */
@RunWith(AndroidJUnit4::class)
class RenderingLifecycleInstrumentedTest {

    /** A surface plus a consumer that drains it; without a consumer the producer's swap would block forever. */
    private class Sink : AutoCloseable {
        private val consumerThread = HandlerThread("test-sink").apply { start() }
        private val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3)
        val received = AtomicInteger()
        val surface get() = reader.surface

        init {
            reader.setOnImageAvailableListener(
                { r ->
                    r.acquireLatestImage()?.close()
                    received.incrementAndGet()
                },
                Handler(consumerThread.looper),
            )
        }

        override fun close() {
            reader.close()
            consumerThread.quitSafely()
        }
    }

    /** Counts listener callbacks and owns a renderer + cache like a real scene does. */
    private class TestScene : ContextListener, FrameRenderer {
        val created = AtomicInteger()
        val lost = AtomicInteger()
        val frames = AtomicInteger()
        var renderer: TexturedQuadRenderer? = null
        var cache: TextureCache? = null

        override fun onContextCreated(ctx: RenderContext) {
            created.incrementAndGet()
            val r = TexturedQuadRenderer()
            renderer = r
            cache = TextureCache(r).also { it.getOrUpload("t") { bitmap(8, 8) } }
        }

        override fun onContextLost() {
            lost.incrementAndGet()
            renderer?.abandon()
            cache?.abandon()
            renderer = null
            cache = null
        }

        override fun renderFrame(ctx: RenderContext, frameTimeNs: Long, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val r = renderer ?: return
            val texture = cache?.getOrUpload("t") { bitmap(8, 8) } ?: return
            r.draw(texture, rotationDegrees = (frames.get() % 360).toFloat())
            frames.incrementAndGet()
        }
    }

    private companion object {
        const val TAG = "MlRenderTest"

        fun bitmap(w: Int, h: Int): Bitmap =
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

        fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
            val end = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < end) {
                if (condition()) return
                Thread.sleep(10)
            }
            fail("timed out after $timeoutMs ms waiting for: $what")
        }
    }

    // ---- render loop ----

    @Test
    fun callFromTheRenderThreadRunsInlineInsteadOfDeadlocking() {
        RenderThread("test-reentrant").use { thread ->
            val start = System.nanoTime()
            val value = thread.call { thread.call { 42 } }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(42, value)
            // The old implementation waited for its own 10 s timeout here.
            assertTrue("re-entrant call took $elapsedMs ms", elapsedMs < 2_000)
        }
    }

    @Test
    fun frameLoopRendersPresentsAndDoesNotSpin() {
        RenderThread("test-loop").use { thread ->
            Sink().use { sink ->
                val scene = TestScene()
                thread.setContextListener(scene)
                thread.attachSurface(sink.surface)
                val loop = FrameLoop(thread, 60f, scene)
                loop.setViewport(64, 64)
                loop.start()
                val windowMs = 2_000L
                Thread.sleep(windowMs)
                loop.stop()
                val d = loop.diagnostics()
                Log.i(TAG, "loop 2s: $d")
                assertTrue("no frame was presented", d.stats.totalFrames > 0)
                assertTrue("consumer received nothing", sink.received.get() > 0)
                assertEquals(0L, d.renderErrors)
                // Pacing guard: 60 fps cap means at most ~120 frames in 2 s (+ tolerance). A busy loop would produce thousands.
                assertTrue("loop ran too fast (${d.stats.totalFrames} frames in 2 s)", d.stats.totalFrames < 150)
            }
        }
    }

    @Test
    fun loopIdlesWithoutSurfaceAndStartsPresentingWhenOneArrives() {
        RenderThread("test-idle").use { thread ->
            val scene = TestScene()
            thread.setContextListener(scene)
            val loop = FrameLoop(thread, 60f, scene)
            loop.start()
            Thread.sleep(500)
            val idle = loop.diagnostics()
            assertEquals(0L, idle.stats.totalFrames)
            assertTrue("idle loop should tick at ~20 Hz, not spin (ticks=${idle.idleTicks})", idle.idleTicks in 1L..40L)
            Sink().use { sink ->
                thread.attachSurface(sink.surface)
                waitFor(3_000, "frames after attaching a surface") { sink.received.get() > 0 }
            }
            loop.stop()
        }
    }

    // ---- surface recreation ----

    @Test
    fun surfaceCanBeDestroyedAndRecreatedWithoutLosingTheContext() {
        RenderThread("test-surface").use { thread ->
            val scene = TestScene()
            thread.setContextListener(scene)
            val loop = FrameLoop(thread, 60f, scene)
            loop.setViewport(64, 64)
            repeat(5) { round ->
                Sink().use { sink ->
                    thread.attachSurface(sink.surface)
                    loop.start()
                    waitFor(5_000, "frames on surface #$round") { sink.received.get() >= 3 }
                    loop.stop()
                    thread.detachSurface()
                    assertFalse(thread.status().hasWindowSurface)
                }
            }
            val st = thread.status()
            assertEquals(5, st.surfaceAttachments)
            assertEquals("the context must survive surface recreation", 1, st.contextCreations)
            assertEquals(1, scene.created.get())
            assertEquals(0, scene.lost.get())
            assertEquals(ContextState.READY, st.state)
        }
    }

    @Test
    fun destroyingTheAndroidSurfaceUnderTheLoopIsSurvived() {
        RenderThread("test-surface-lost").use { thread ->
            val scene = TestScene()
            thread.setContextListener(scene)
            val loop = FrameLoop(thread, 60f, scene)
            loop.setViewport(64, 64)
            val sink = Sink()
            thread.attachSurface(sink.surface)
            loop.start()
            waitFor(5_000, "first frames") { sink.received.get() >= 2 }
            sink.close() // the consumer goes away while the loop is still running (no detachSurface on purpose)
            Thread.sleep(300)
            Sink().use { second ->
                thread.attachSurface(second.surface)
                waitFor(5_000, "frames on the replacement surface") { second.received.get() >= 2 }
            }
            loop.stop()
            assertEquals(ContextState.READY, thread.status().state)
            assertEquals(0L, loop.diagnostics().renderErrors)
        }
    }

    // ---- EGL lifecycle ----

    @Test
    fun contextLifecycleStatusAndRecoveryFromLoss() {
        RenderThread("test-egl").use { thread ->
            assertEquals(ContextState.NOT_CREATED, thread.status().state)
            val scene = TestScene()
            thread.setContextListener(scene) // creates the context
            var st = thread.status()
            assertEquals(ContextState.READY, st.state)
            assertTrue(st.glesVersion >= 2)
            assertNotNull(st.gpu)
            assertEquals(1, st.contextCreations)
            assertEquals(1, scene.created.get())

            Sink().use { sink ->
                thread.attachSurface(sink.surface)
                val loop = FrameLoop(thread, 60f, scene)
                loop.setViewport(64, 64)
                loop.start()
                waitFor(5_000, "frames before the loss") { sink.received.get() >= 2 }

                thread.simulateContextLoss()
                // The loop recreates the context by itself and re-wraps the same Android Surface.
                val before = sink.received.get()
                waitFor(5_000, "context recreated") { thread.status().contextCreations == 2 }
                waitFor(5_000, "frames after the loss") { sink.received.get() >= before + 2 }
                loop.stop()
                st = thread.status()
                assertEquals(ContextState.READY, st.state)
                assertEquals(1, st.contextLosses)
                assertTrue("window surface must be restored after the loss", st.hasWindowSurface)
                assertEquals(1, scene.lost.get())
                assertEquals(2, scene.created.get())
                assertEquals(0L, loop.diagnostics().renderErrors)
            }
        }
    }

    @Test
    fun releaseIsIdempotentAndRejectsFurtherWork() {
        val thread = RenderThread("test-release")
        thread.call { }
        assertEquals(ContextState.READY, thread.status().state)
        thread.release()
        thread.release()
        assertEquals(ContextState.RELEASED, thread.status().state)
        assertTrue(thread.isReleased)
        try {
            thread.call { }
            fail("expected MlException")
        } catch (e: MlException) {
            assertEquals(ErrorCode.GPU, e.code)
        }
        thread.post { fail("must not run after release") } // silently ignored
        assertFalse(thread.postRaw(Runnable { }))
    }

    // ---- texture upload and cache ----

    @Test
    fun textureUploadTracksBytesAndDeleteReleasesThem() {
        RenderThread("test-tex").use { thread ->
            thread.call {
                val r = TexturedQuadRenderer()
                val t = r.uploadBitmap(bitmap(16, 8))
                assertEquals(16L * 8 * 4, t.sizeBytes)
                assertEquals(1, r.liveTextureCount)
                assertEquals(512L, r.liveTextureBytes)
                r.deleteTexture(t)
                r.deleteTexture(t) // idempotent
                assertEquals(0, r.liveTextureCount)
                assertEquals(0L, r.liveTextureBytes)
                try {
                    r.draw(t)
                    fail("drawing a deleted texture must fail")
                } catch (e: MlException) {
                    assertEquals(ErrorCode.GPU, e.code)
                }
                r.close()
            }
        }
    }

    @Test
    fun textureCacheReusesEvictsAndRespectsItsBudget() {
        RenderThread("test-cache").use { thread ->
            thread.call {
                val r = TexturedQuadRenderer()
                // Budget: two 16x16 RGBA textures (1 KiB each).
                val cache = TextureCache(r, maxBytes = 2048)
                var decoded = 0
                fun get(key: String) = cache.getOrUpload(key) { decoded++; bitmap(16, 16) }

                get("a"); get("b")
                get("a") // hit: no decode, no upload
                assertEquals(2, decoded)
                assertEquals(2, cache.uploads)
                assertEquals(2, cache.count)

                get("c") // evicts b (least recently used)
                assertEquals(3, cache.uploads)
                assertEquals(2, cache.count)
                assertTrue(cache.contains("a") && cache.contains("c") && !cache.contains("b"))
                assertEquals("evicted texture must really be deleted on the GPU side", 2, r.liveTextureCount)
                assertEquals(2048L, cache.bytes)

                try {
                    cache.getOrUpload("huge") { bitmap(64, 64) } // 16 KiB > 2 KiB budget
                    fail("expected oversize failure")
                } catch (e: MlException) {
                    assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
                }
                assertEquals(2, r.liveTextureCount)

                cache.close()
                assertEquals(0, r.liveTextureCount)
                r.close()
            }
        }
    }

    // ---- resource cleanup ----

    @Test
    fun closingTheRendererFreesEveryTexture() {
        RenderThread("test-cleanup").use { thread ->
            val live = thread.call {
                val r = TexturedQuadRenderer()
                repeat(50) { r.uploadBitmap(bitmap(8, 8)) }
                assertEquals(50, r.liveTextureCount)
                r.close()
                r.close() // idempotent
                r.liveTextureCount
            }
            assertEquals(0, live)
        }
    }

    @Test
    fun releaseRunsCleanupCallbacksWithTheContextCurrent() {
        val ran = AtomicInteger()
        val thread = RenderThread("test-onrelease")
        thread.call { }
        thread.onRelease {
            // Would throw GL errors / crash without a current context.
            val r = TexturedQuadRenderer()
            r.uploadBitmap(bitmap(4, 4))
            r.close()
            ran.incrementAndGet()
        }
        thread.release()
        assertEquals(1, ran.get())
    }

    @Test
    fun repeatedCreateAndReleaseDoesNotLeakThreads() {
        val before = Thread.getAllStackTraces().keys.count { it.name.startsWith("leak-check") }
        repeat(25) { i ->
            RenderThread("leak-check-$i").use { it.call { } }
        }
        Thread.sleep(300) // quitSafely is asynchronous
        val after = Thread.getAllStackTraces().keys.count { it.name.startsWith("leak-check") && it.isAlive }
        assertEquals(0, before)
        assertEquals("render threads were not stopped", 0, after)
    }

    // ---- long-duration stability ----

    @Test
    fun fifteenSecondsOfRenderingWithSurfaceChurnAndContextLossStaysStable() {
        RenderThread("test-soak").use { thread ->
            val scene = TestScene()
            thread.setContextListener(scene)
            val loop = FrameLoop(thread, 60f, scene)
            loop.setViewport(64, 64)
            val runtime = Runtime.getRuntime()
            System.gc()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            var totalReceived = 0

            val start = System.nanoTime()
            var round = 0
            while ((System.nanoTime() - start) < 15_000_000_000L) {
                Sink().use { sink ->
                    thread.attachSurface(sink.surface)
                    loop.start()
                    Thread.sleep(1_500)
                    if (round == 3) thread.simulateContextLoss()
                    Thread.sleep(1_500)
                    loop.stop()
                    thread.detachSurface()
                    totalReceived += sink.received.get()
                }
                round++
            }
            val d = loop.diagnostics()
            val st = thread.status()
            System.gc()
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            Log.i(TAG, "soak: rounds=$round received=$totalReceived status=$st lastLoop=$d heapBefore=$heapBefore heapAfter=$heapAfter")

            assertTrue("nothing was presented", totalReceived > 0)
            assertEquals(0L, d.renderErrors)
            assertEquals(ContextState.READY, st.state)
            assertNull(st.lastError)
            assertEquals(scene.created.get(), st.contextCreations)
            assertEquals(1, st.contextLosses)
            // Exactly one cached texture alive in the final context: nothing accumulated over the churn.
            val liveTextures = thread.call { scene.renderer?.liveTextureCount }
            assertEquals(1, liveTextures)
            // Generous bound: catches per-frame leaks (hundreds of MB), not normal GC noise.
            assertTrue("heap grew by ${heapAfter - heapBefore} bytes", heapAfter - heapBefore < 64L * 1024 * 1024)
        }
    }
}
