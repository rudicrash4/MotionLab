package id.motionlab.nativecore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device/emulator because it loads the real libmotionlab_native.so. */
@RunWith(AndroidJUnit4::class)
class NativeCoreInstrumentedTest {
    @Before
    fun load() {
        val result = NativeCore.load()
        assertTrue("native library failed to load: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun jniInitializationReportsVersion() {
        assertEquals(NativeCore.EXPECTED_API_VERSION, NativeCore.load().getOrThrow())
        assertTrue(NativeCore.version().startsWith("motionlab-native"))
    }

    @Test
    fun bufferLifecycleDoesNotLeak() {
        val before = NativeCore.liveAllocationCount()
        val bytesBefore = NativeCore.liveBytes()
        NativeBuffer(4096).use { buf ->
            assertEquals(before + 1, NativeCore.liveAllocationCount())
            assertEquals(bytesBefore + 4096, NativeCore.liveBytes())
            buf.fill(2)
            assertEquals(2L * 4096, buf.sum())
            val view = buf.asByteBuffer()
            assertEquals(4096, view.capacity())
            assertEquals(2, view.get(100).toInt())
        }
        assertEquals(before, NativeCore.liveAllocationCount())
        assertEquals(bytesBefore, NativeCore.liveBytes())
    }

    @Test
    fun closeIsIdempotentAndUseAfterCloseFails() {
        val buf = NativeBuffer(16)
        buf.close()
        buf.close()
        assertTrue(buf.isClosed)
        try {
            buf.fill(1)
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun invalidAllocationSizeThrowsNativeException() {
        for (size in listOf(0L, -5L, Long.MAX_VALUE)) {
            try {
                NativeBuffer(size).close()
                fail("size $size should be rejected")
            } catch (e: NativeException) {
                assertTrue(e.message!!.contains("Invalid allocation size"))
            }
        }
    }

    @Test
    fun ffmpegProbeFailsCleanlyWhenUnavailableOrFileMissing() {
        if (NativeCore.ffmpegAvailable()) {
            try {
                NativeCore.ffmpegProbe("/definitely/not/a/file.mp4")
                fail()
            } catch (e: NativeException) {
                assertFalse(e.message.isNullOrBlank())
            }
        } else {
            try {
                NativeCore.ffmpegProbe("/anything")
                fail()
            } catch (e: NativeException) {
                assertTrue(e.message!!.contains("not available"))
            }
        }
    }
}
