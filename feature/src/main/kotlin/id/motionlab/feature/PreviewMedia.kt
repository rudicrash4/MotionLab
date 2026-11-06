package id.motionlab.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import id.motionlab.engine.media.BitmapProvider
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.MaskRasterizer
import id.motionlab.engine.timeline.MaskSpec
import id.motionlab.engine.timeline.MediaClip
import id.motionlab.engine.timeline.TrackKind
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MediaFrame(val key: String, val bitmap: Bitmap)

/**
 * What the render thread asks for image / video clips. NEVER blocks: it answers from the in-memory cache, and when the
 * bitmap is not there yet it queues ONE background decode. While a new frame is being decoded the PREVIOUS frame of the
 * same asset stays on screen (no flicker, no placeholder drawing); before the very first frame there is simply nothing to
 * draw. Video frames come from the platform frame extractor at [VIDEO_STEP_US] granularity and are the exact frame at that
 * time: this follows the playhead as a decoded still image, it is NOT a real-time video player (see docs/PHASE3_1.md).
 * [version] increases whenever a new bitmap becomes available, so a paused preview can redraw.
 */
class PreviewMedia(private val session: EditorSession, private val bitmaps: BitmapProvider) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ml-preview-media").apply { isDaemon = true } }
    private val wanted = ConcurrentHashMap<String, Long>() // assetId -> bucket still wanted
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val lastShown = ConcurrentHashMap<String, MediaFrame>() // assetId -> newest frame handed out

    @Volatile
    var version: Int = 0
        private set

    /** Asset ids whose media could not be decoded at all (shown as a message by the UI, never as fake pixels). */
    val failedAssets: Set<String> get() = failed.mapTo(HashSet()) { it.substringBefore('|') }

    fun frameFor(clip: MediaClip, kind: TrackKind, localTimeUs: Long): MediaFrame? {
        val id = clip.assetId ?: return null
        if (kind != TrackKind.IMAGE && kind != TrackKind.VIDEO) return null
        val asset = session.asset(id) ?: return null
        val bucket = if (kind == TrackKind.VIDEO) bucketFor(clip.sourceStartUs + localTimeUs) else 0L
        val side = if (kind == TrackKind.VIDEO) 720 else 1536
        bitmaps.cached(id, bucket, side)?.let { bmp -> return MediaFrame("media:$id:$bucket:$side", bmp).also { lastShown[id] = it } }
        val k = "$id|$bucket|$side"
        if (k !in failed) {
            wanted[id] = bucket
            if (pending.add(k)) {
                worker.execute {
                    try {
                        // A newer frame of the same video was requested meanwhile: this one is not needed any more.
                        val bmp = bitmaps.load(asset, bucket, side) { wanted[id] != bucket && kind == TrackKind.VIDEO }
                        if (bmp != null) version++ else if (wanted[id] == bucket) failed.add(k)
                    } finally {
                        pending.remove(k)
                    }
                }
            }
        }
        return lastShown[id]?.takeIf { bitmaps.cached(id, bucketOfKey(it.key), sideOfKey(it.key)) != null }
    }

    private fun bucketOfKey(key: String): Long = key.split(':').getOrNull(2)?.toLongOrNull() ?: 0L
    private fun sideOfKey(key: String): Int = key.split(':').getOrNull(3)?.toIntOrNull() ?: 0

    /** Retry decoding of an asset that failed before (e.g. after the permission came back). */
    fun retry(assetId: String) {
        failed.removeAll { it.startsWith("$assetId|") }
    }

    fun forget(assetId: String) {
        bitmaps.drop(assetId)
        lastShown.remove(assetId)
        failed.removeAll { it.startsWith("$assetId|") }
    }

    fun close() {
        worker.shutdownNow()
    }

    companion object {
        const val VIDEO_STEP_US = 250_000L

        /** The decode bucket (frame time rounded down to the step) a source time belongs to. */
        fun bucketFor(sourceTimeUs: Long): Long = (sourceTimeUs.coerceAtLeast(0) / VIDEO_STEP_US) * VIDEO_STEP_US
    }
}

/** Applies a [MaskSpec] to a copy of [src] with a DST_IN pass (correct for premultiplied bitmaps). */
internal object MaskBitmap {
    fun apply(src: Bitmap, spec: MaskSpec): Bitmap {
        val w = src.width
        val h = src.height
        val mw = min(w, 512)
        val mh = max(1, (mw.toLong() * h / w).toInt())
        val alpha = MaskRasterizer.alpha(spec, mw, mh)
        val maskBmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        maskBmp.copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        Canvas(out).drawBitmap(maskBmp, null, Rect(0, 0, w, h), paint)
        maskBmp.recycle()
        return out
    }
}
