package id.motionlab.nativecore

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import java.nio.ByteBuffer

/** Thrown by native code (via JNI ThrowNew) for invalid arguments, allocation failures, and FFmpeg errors. */
class NativeException(message: String) : RuntimeException(message)

/**
 * JNI entry points. Everything throws [NativeException] on failure instead of returning magic values.
 * Call [load] once at startup; it never throws, so a device without the library degrades gracefully.
 */
object NativeCore {
    const val EXPECTED_API_VERSION = 1

    @Volatile
    private var loadError: Throwable? = null

    @Volatile
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    @Synchronized
    fun load(): Result<Int> {
        if (loaded) return Result.success(nativeInit())
        return try {
            System.loadLibrary("motionlab_native")
            val version = nativeInit()
            if (version != EXPECTED_API_VERSION) {
                throw MlException(ErrorCode.NATIVE, "Native API version $version, expected $EXPECTED_API_VERSION")
            }
            loaded = true
            loadError = null
            MlLog.i("NativeCore", "loaded ${nativeVersion()} (ffmpeg=${ffmpegAvailable()})")
            Result.success(version)
        } catch (e: UnsatisfiedLinkError) {
            loadError = e
            MlLog.e("NativeCore", "native library missing", e)
            Result.failure(e)
        } catch (e: Exception) {
            loadError = e
            MlLog.e("NativeCore", "native init failed", e)
            Result.failure(e)
        }
    }

    fun lastLoadError(): Throwable? = loadError

    private fun requireLoaded() {
        if (!loaded) throw MlException(ErrorCode.NATIVE, "Native library is not loaded", loadError)
    }

    fun version(): String {
        requireLoaded()
        return nativeVersion()
    }

    fun liveAllocationCount(): Int {
        requireLoaded()
        return nativeLiveAllocationCount()
    }

    fun liveBytes(): Long {
        requireLoaded()
        return nativeLiveBytes()
    }

    fun ffmpegAvailable(): Boolean = loaded && nativeFfmpegAvailable()

    fun ffmpegVersion(): String {
        requireLoaded()
        return nativeFfmpegVersion()
    }

    /** Raw probe text; see FfmpegProbeParser for the format. Throws [NativeException] if FFmpeg cannot open [path]. */
    fun ffmpegProbe(path: String): String {
        requireLoaded()
        return nativeFfmpegProbe(path)
    }

    internal fun allocate(size: Long): Long {
        requireLoaded()
        return nativeAllocate(size)
    }

    internal fun free(handle: Long) = nativeFree(handle)
    internal fun fill(handle: Long, value: Int) = nativeFill(handle, value)
    internal fun sum(handle: Long): Long = nativeSum(handle)
    internal fun asByteBuffer(handle: Long): ByteBuffer = nativeAsByteBuffer(handle)

    @JvmStatic private external fun nativeInit(): Int
    @JvmStatic private external fun nativeVersion(): String
    @JvmStatic private external fun nativeAllocate(size: Long): Long
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeFill(handle: Long, value: Int)
    @JvmStatic private external fun nativeSum(handle: Long): Long
    @JvmStatic private external fun nativeAsByteBuffer(handle: Long): ByteBuffer
    @JvmStatic private external fun nativeLiveAllocationCount(): Int
    @JvmStatic private external fun nativeLiveBytes(): Long
    @JvmStatic private external fun nativeFfmpegAvailable(): Boolean
    @JvmStatic private external fun nativeFfmpegVersion(): String
    @JvmStatic private external fun nativeFfmpegProbe(path: String): String
}

/**
 * Natively allocated, zero-initialised memory with an explicit lifetime. Always use `use { }` or call [close];
 * [NativeCore.liveAllocationCount] lets tests assert nothing leaked.
 */
class NativeBuffer(val size: Long) : AutoCloseable {
    @Volatile
    private var handle: Long = NativeCore.allocate(size)

    val isClosed: Boolean get() = handle == 0L

    private fun requireOpen(): Long {
        val h = handle
        if (h == 0L) throw IllegalStateException("NativeBuffer is closed")
        return h
    }

    fun fill(value: Int) = NativeCore.fill(requireOpen(), value)

    fun sum(): Long = NativeCore.sum(requireOpen())

    /** Direct view of the native memory. Invalid after [close]; do not touch it afterwards. */
    fun asByteBuffer(): ByteBuffer = NativeCore.asByteBuffer(requireOpen())

    @Synchronized
    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        NativeCore.free(h)
    }
}
