package id.motionlab.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.BoundedCache
import kotlin.math.max

/**
 * Loads small, memory-bounded bitmaps for assets: image previews and video frames. Results are cached in an LRU limited
 * by BYTES, so scrolling a big library cannot exhaust memory, and identical requests never decode twice. Blocking;
 * callers run it on a background dispatcher and pass [cancelled] so abandoned requests stop early.
 * Never call from the render thread or the main thread.
 */
class BitmapProvider(private val context: Context, maxBytes: Long = 32L * 1024 * 1024) {
    private val cache = BoundedCache<String, Bitmap>(maxBytes, { it.allocationByteCount.toLong() })

    /** Cache lookup only: safe on any thread, never decodes. */
    fun cached(assetId: String, bucketUs: Long, maxSide: Int): Bitmap? = cache[key(assetId, bucketUs, maxSide)]

    fun drop(assetId: String) = cache.removeWhere { it.startsWith("$assetId|") }

    fun clear() = cache.clear()

    private fun key(assetId: String, bucketUs: Long, maxSide: Int) = "$assetId|$bucketUs|$maxSide"

    /**
     * Image: decoded with a sample size so no side is much larger than [maxSide] (the full-size bitmap is never loaded).
     * Video: the frame at [timeUs] (rounded by the caller to a bucket so neighbouring requests share one decode).
     * Returns null when the media cannot be decoded.
     */
    fun load(asset: AssetRefDto, timeUs: Long, maxSide: Int, cancelled: () -> Boolean = { false }): Bitmap? {
        val k = key(asset.id, timeUs, maxSide)
        cache[k]?.let { return it }
        if (cancelled()) return null
        val bmp = try {
            when (asset.kind) {
                "IMAGE" -> decodeImage(Uri.parse(asset.uri), maxSide)
                "VIDEO" -> decodeFrame(Uri.parse(asset.uri), timeUs, maxSide)
                else -> null
            }
        } catch (e: Exception) {
            MlLog.w("BitmapProvider", "decode failed for ${asset.displayName}: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            cache.clear()
            MlLog.w("BitmapProvider", "out of memory decoding ${asset.displayName}; cache cleared")
            null
        }
        if (bmp != null && !cancelled()) cache.put(k, bmp)
        return bmp
    }

    private fun decodeImage(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeFrame(uri: Uri, timeUs: Long, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" || uri.scheme == null) r.setDataSource(uri.path ?: uri.toString()) else r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) return null
            val scale = maxSide.toFloat() / max(w, h)
            val tw = (w * scale).toInt().coerceAtLeast(2)
            val th = (h * scale).toInt().coerceAtLeast(2)
            return if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
            } else {
                val full = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                if (full.width <= tw) full else Bitmap.createScaledBitmap(full, tw, th, true).also { if (it !== full) full.recycle() }
            }
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }
}
