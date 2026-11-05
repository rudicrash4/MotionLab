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
 * bitmap is not there yet it queues ONE background decode and answers null (the scene then draws the labelled tile for a
 * few frames). Video frames come from the platform frame extractor at [VIDEO_STEP_US] granularity (4 fps): this is a
 * still-frame preview, NOT real-time video playback.
 */
class PreviewMedia(private val session: EditorSession, private val bitmaps: BitmapProvider) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ml-preview-media").apply { isDaemon = true } }
    private val wanted = ConcurrentHashMap<String, Long>() // assetId -> bucket still wanted
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun frameFor(clip: MediaClip, kind: TrackKind, localTimeUs: Long): MediaFrame? {
        val id = clip.assetId ?: return null
        if (kind != TrackKind.IMAGE && kind != TrackKind.VIDEO) return null
        val asset = session.asset(id) ?: return null
        val bucket = if (kind == TrackKind.VIDEO) ((clip.sourceStartUs + localTimeUs) / VIDEO_STEP_US) * VIDEO_STEP_US else 0L
        val side = if (kind == TrackKind.VIDEO) 720 else 1536
        bitmaps.cached(id, bucket, side)?.let { return MediaFrame("media:$id:$bucket:$side", it) }
        val k = "$id|$bucket|$side"
        if (k in failed) return null
        wanted[id] = bucket
        if (pending.add(k)) {
            worker.execute {
                try {
                    // A newer frame of the same video was requested meanwhile: this one is not needed any more.
                    val bmp = bitmaps.load(asset, bucket, side) { wanted[id] != bucket && kind == TrackKind.VIDEO }
                    if (bmp == null && wanted[id] == bucket) failed.add(k)
                } finally {
                    pending.remove(k)
                }
            }
        }
        return null
    }

    fun forget(assetId: String) {
        bitmaps.drop(assetId)
        failed.removeAll { it.startsWith("$assetId|") }
    }

    fun close() {
        worker.shutdownNow()
    }

    companion object {
        const val VIDEO_STEP_US = 250_000L
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
