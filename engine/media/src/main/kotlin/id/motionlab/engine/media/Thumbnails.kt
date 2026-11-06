package id.motionlab.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.BoundedCache
import id.motionlab.core.resource.ExifOrientation
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

    fun drop(assetId: String) {
        cache.removeWhere { it.startsWith("$assetId|") }
        releaseRetriever()
    }

    fun clear() {
        cache.clear()
        releaseRetriever()
    }

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

    /** EXIF orientation of an image stream, 1 (normal) when unknown. android.media.ExifInterface(InputStream) needs API 24. */
    internal fun exifOrientation(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 1
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            } ?: 1
        } catch (e: Exception) {
            1
        }
    }

    private fun decodeImage(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val o = exifOrientation(uri)
        val deg = ExifOrientation.degrees(o)
        val mirror = ExifOrientation.mirrored(o)
        if (deg == 0 && !mirror) return raw
        val m = Matrix()
        if (mirror) m.postScale(-1f, 1f)
        if (deg != 0) m.postRotate(deg.toFloat())
        val fixed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (fixed !== raw) raw.recycle()
        return fixed
    }

    // One retriever is kept for the most recently used video: opening a file for every frame costs more than the decode itself.
    private val retrieverLock = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: String? = null
    private var vidW = 0
    private var vidH = 0

    private fun releaseRetriever() = synchronized(retrieverLock) {
        try { retriever?.release() } catch (_: Exception) { }
        retriever = null
        retrieverUri = null
    }

    private fun decodeFrame(uri: Uri, timeUs: Long, maxSide: Int): Bitmap? = synchronized(retrieverLock) {
        try {
            var r = retriever
            if (r == null || retrieverUri != uri.toString()) {
                releaseRetriever()
                r = MediaMetadataRetriever()
                if (uri.scheme == "file" || uri.scheme == null) r.setDataSource(uri.path ?: uri.toString()) else r.setDataSource(context, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val swap = rot == 90 || rot == 270
                vidW = if (swap) h else w // display orientation
                vidH = if (swap) w else h
                retriever = r
                retrieverUri = uri.toString()
            }
            if (vidW <= 0 || vidH <= 0) return null
            val scale = maxSide.toFloat() / max(vidW, vidH)
            val tw = (vidW * scale).toInt().coerceAtLeast(2)
            val th = (vidH * scale).toInt().coerceAtLeast(2)
            // OPTION_CLOSEST returns the frame AT the requested time. OPTION_CLOSEST_SYNC would return the nearest key frame,
            // which can be seconds away, so the picture would not change while the playhead moves.
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
            } else {
                r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: return null
            // Whatever the platform did with rotation + scaling, the result must keep the DISPLAY aspect ratio (no distortion).
            val expected = vidW.toFloat() / vidH
            val actual = frame.width.toFloat() / frame.height
            val needsFix = kotlin.math.abs(actual - expected) / expected > 0.03f || frame.width > tw * 2
            if (!needsFix) frame else Bitmap.createScaledBitmap(frame, tw, th, true).also { if (it !== frame) frame.recycle() }
        } catch (e: Exception) {
            releaseRetriever() // a broken file must not poison the next request
            throw e
        }
    }
}
