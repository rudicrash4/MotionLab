package id.motionlab.engine.rendering

import android.graphics.Bitmap
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException

/**
 * Keeps uploaded textures keyed by name, bounded by [maxBytes] of GPU memory (RGBA8 estimate). Least recently used
 * textures are deleted when the budget is exceeded. Render thread only, context current.
 *
 * Do not keep a [GlTexture] across another [getOrUpload] call: it may have been evicted by then. Fetch it again
 * with the same key (a cache hit costs one hash lookup).
 */
class TextureCache(private val renderer: TexturedQuadRenderer, val maxBytes: Long = 32L * 1024 * 1024) : AutoCloseable {
    private var abandoned = false

    private val cache = LruByteCache<String, GlTexture>(
        maxBytes = maxBytes,
        sizeOf = { it.sizeBytes },
        onEvict = { _, texture -> if (!abandoned) renderer.deleteTexture(texture) },
    )

    /** How many bitmaps were actually uploaded (cache misses). */
    var uploads: Int = 0
        private set

    val bytes: Long get() = cache.bytes
    val count: Int get() = cache.size

    fun contains(key: String): Boolean = cache.contains(key)

    /**
     * Returns the cached texture, or calls [bitmap] to create one and uploads it. The bitmap is recycled after the
     * upload when [recycleBitmap] is true, so it does not stay in memory next to its GPU copy.
     */
    fun getOrUpload(key: String, recycleBitmap: Boolean = true, bitmap: () -> Bitmap): GlTexture {
        cache.get(key)?.let { return it }
        val bmp = bitmap()
        try {
            val size = bmp.width.toLong() * bmp.height * 4
            if (size > maxBytes) {
                throw MlException(
                    ErrorCode.INVALID_ARGUMENT,
                    "Texture '$key' needs $size bytes but the cache budget is $maxBytes",
                )
            }
            val texture = renderer.uploadBitmap(bmp)
            uploads++
            cache.put(key, texture)
            return texture
        } finally {
            if (recycleBitmap && !bmp.isRecycled) bmp.recycle()
        }
    }

    fun remove(key: String) {
        cache.remove(key)?.let { renderer.deleteTexture(it) }
    }

    /** After a context loss: drop all entries without touching GL. */
    fun abandon() {
        abandoned = true
        cache.clear()
    }

    /** Deletes every cached texture. */
    override fun close() {
        cache.clear()
    }
}
