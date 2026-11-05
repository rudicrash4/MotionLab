package id.motionlab.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES20
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import id.motionlab.core.common.MlLog
import id.motionlab.engine.rendering.ContextListener
import id.motionlab.engine.rendering.FrameLoop
import id.motionlab.engine.rendering.FrameRenderer
import id.motionlab.engine.rendering.RenderContext
import id.motionlab.engine.rendering.RenderThread
import id.motionlab.engine.rendering.TextureCache
import id.motionlab.engine.rendering.QuadBlend
import id.motionlab.engine.rendering.TexturedQuadRenderer
import id.motionlab.engine.text.FontManager
import id.motionlab.engine.text.TextAlign
import id.motionlab.engine.text.TextObject
import id.motionlab.engine.text.TextRenderer
import id.motionlab.engine.text.TypographyConfig
import id.motionlab.engine.timeline.BlendMode
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.MaskSpec
import id.motionlab.engine.timeline.blendMode
import id.motionlab.engine.timeline.mask
import id.motionlab.engine.timeline.MediaClip
import id.motionlab.engine.timeline.PreviewMath
import id.motionlab.engine.timeline.RenderItem
import id.motionlab.engine.timeline.TrackKind

/**
 * Draws the session's [id.motionlab.engine.timeline.SceneFrame] with the existing render stack. Render thread only.
 * The frame comes from `session.frame()`, i.e. the same [id.motionlab.engine.timeline.SceneEvaluator] the export uses.
 *
 * Content: there is no video/image decoder in the preview yet, so those clips are drawn as labelled tiles; text clips are
 * rendered with [TextRenderer]; shape clips use the payload ("shape" = rect|ellipse, "color" = #RRGGBB).
 */
internal class EditorScene(
    private val session: EditorSession,
    private val settings: PreviewSettings,
    private val measuredSizes: java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>,
    private val media: PreviewMedia? = null,
    fonts: FontManager = FontManager(),
) : ContextListener, FrameRenderer {
    private var renderer: TexturedQuadRenderer? = null
    private var cache: TextureCache? = null
    private val sizes = HashMap<String, Pair<Int, Int>>()
    private val matrix = FloatArray(16)
    private val IDENTITY = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    private val text = TextRenderer(fonts)

    @Volatile
    var drawnItems = 0
        private set

    override fun onContextCreated(ctx: RenderContext) {
        val r = TexturedQuadRenderer()
        renderer = r
        cache = TextureCache(r)
        sizes.clear()
    }

    override fun onContextLost() {
        renderer?.abandon()
        cache?.abandon()
        renderer = null
        cache = null
        sizes.clear()
    }

    fun close() {
        cache?.close()
        renderer?.close()
        cache = null
        renderer = null
    }

    override fun renderFrame(ctx: RenderContext, frameTimeNs: Long, width: Int, height: Int) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.05f, 0.05f, 0.07f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val r = renderer ?: return
        val c = cache ?: return
        val cw = session.canvasWidth
        val ch = session.canvasHeight
        val box = PreviewMath.letterbox(width, height, cw, ch)
        GLES20.glViewport(box[0], box[1], box[2], box[3])
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(box[0], box[1], box[2], box[3])
        val bg = settings.background
        when (bg) {
            PreviewBackground.WHITE -> GLES20.glClearColor(1f, 1f, 1f, 1f)
            PreviewBackground.GRAY -> GLES20.glClearColor(0.3f, 0.3f, 0.32f, 1f)
            else -> GLES20.glClearColor(0f, 0f, 0f, 1f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (bg == PreviewBackground.CHECKER) {
            try {
                val tex = c.getOrUpload("checker:$cw:$ch") { checkerBitmap(cw, ch) }
                r.drawMatrix(tex, IDENTITY, 1f)
            } catch (e: Exception) {
                MlLog.w("EditorScene", "checkerboard not drawn: ${e.message}")
            }
        }

        val frame = session.frame()
        var n = 0
        for (item in frame.items) {
            if (!item.trackKind.hasVisuals) continue
            try {
                // Real pixels for image/video clips when they are already decoded; otherwise the labelled tile.
                val real = media?.frameFor(item.clip, item.trackKind, item.localTimeUs)
                val mask = item.clip.mask?.takeIf { it.isActive }
                val baseKey = real?.key ?: keyOf(item.clip, item.trackKind)
                val key = if (mask != null) "$baseKey|m${item.clip.payload.filterKeys { it.startsWith(MaskSpec.KEY) }.hashCode()}" else baseKey
                val tex = c.getOrUpload(key, recycleBitmap = real == null || mask != null) {
                    val b = real?.bitmap ?: bitmapFor(item, baseKey)
                    if (mask != null) MaskBitmap.apply(b, mask).also { if (real == null && it !== b) b.recycle() } else b
                }
                val (w, h) = sizes[baseKey] ?: PreviewMath.contentSize(item.clip, cw)
                PreviewMath.quadMatrix(item.world, w.toDouble(), h.toDouble(), cw, ch, matrix)
                r.drawMatrix(tex, matrix, item.opacity.toFloat(), quadBlendOf(item.clip.blendMode))
                n++
            } catch (e: Exception) {
                MlLog.w("EditorScene", "clip ${item.clip.id} not drawn: ${e.message}")
            }
        }
        drawnItems = n
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** Only modes the renderer can draw exactly are mapped; Darken / Lighten / Overlay fall back to Normal (see BlendMode). */
    private fun quadBlendOf(mode: BlendMode): QuadBlend = when (mode) {
        BlendMode.MULTIPLY -> QuadBlend.MULTIPLY
        BlendMode.SCREEN -> QuadBlend.SCREEN
        BlendMode.ADD -> QuadBlend.ADD
        else -> QuadBlend.NORMAL
    }

    /** 16 cells across, square cells, two greys: the usual transparency indicator. */
    private fun checkerBitmap(canvasW: Int, canvasH: Int): Bitmap {
        val w = 512
        val h = (w.toDouble() * canvasH / canvasW).toInt().coerceAtLeast(16)
        val cell = w / 16
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val a = Paint().apply { color = 0xFF3A3A3E.toInt() }
        val b = Paint().apply { color = 0xFF55555A.toInt() }
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), if (((x / cell) + (y / cell)) % 2 == 0) a else b)
                x += cell
            }
            y += cell
        }
        return bmp
    }

    private fun keyOf(clip: MediaClip, kind: TrackKind) = "${kind.name}:${clip.id}:${clip.payload.hashCode()}:${clip.assetId}"

    private fun bitmapFor(item: RenderItem, key: String): Bitmap {
        val clip = item.clip
        val payload = clip.payload
        val bmp = when (item.trackKind) {
            TrackKind.TEXT -> {
                val color = PreviewMath.parseColor(payload["color"], Color.WHITE)
                val size = payload["size"]?.toFloatOrNull()?.coerceIn(8f, 600f) ?: 96f
                text.render(
                    TextObject(
                        clip.id,
                        payload["text"]?.takeIf { it.isNotEmpty() } ?: "Text",
                        TypographyConfig(fontFamily = payload["font"]?.takeIf { it.isNotBlank() } ?: "sans-serif", sizePx = size, colorArgb = color, bold = payload["bold"] == "true", align = TextAlign.CENTER),
                        maxWidthPx = payload["w"]?.toIntOrNull(),
                    ),
                )
            }
            TrackKind.SHAPE -> shapeBitmap(item)
            else -> labelBitmap(item)
        }
        // Text is sized by its own layout (canvas px); shapes/tiles by the content box, whatever the bitmap resolution.
        if (item.trackKind == TrackKind.TEXT) {
            sizes[key] = bmp.width to bmp.height
            measuredSizes[item.clip.id] = bmp.width to bmp.height
        }
        return bmp
    }

    private fun tileSize(item: RenderItem): Pair<Int, Int> {
        val (w, h) = PreviewMath.contentSize(item.clip, session.canvasWidth)
        // Bitmap resolution is capped; the quad is sized by the content box, so this only sets sharpness.
        val scale = minOf(1.0, 512.0 / w)
        return (w * scale).toInt().coerceAtLeast(2) to (h * scale).toInt().coerceAtLeast(2)
    }

    private fun shapeBitmap(item: RenderItem): Bitmap {
        val (bw, bh) = tileSize(item)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PreviewMath.parseColor(item.clip.payload["color"], 0xFF4F8CFF.toInt()) }
        val rect = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
        val canvas = Canvas(bmp)
        if (item.clip.payload["shape"] == "ellipse") canvas.drawOval(rect, paint) else canvas.drawRect(rect, paint)
        return bmp
    }

    private fun labelBitmap(item: RenderItem): Bitmap {
        val (bw, bh) = tileSize(item)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val base = when (item.trackKind) {
            TrackKind.VIDEO -> 0xFF2E6F9E
            TrackKind.IMAGE -> 0xFF7A4FA0
            else -> 0xFF3F7D58
        }.toInt()
        canvas.drawColor(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = bh * 0.14f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${item.trackKind.label}: ${item.clip.assetId ?: item.clip.id}", bw / 2f, bh / 2f, paint)
        return bmp
    }
}

/**
 * SurfaceView that renders an [EditorSession] on the shared render stack (RenderThread + FrameLoop). Same lifecycle
 * rules as [RenderDemoView]: thread and GL objects live until detach; surface changes only re-attach; the loop stops
 * while the window is invisible.
 */
class EditorPreviewView(
    context: Context,
    private val session: EditorSession,
    private val settings: PreviewSettings = PreviewSettings(),
    private val measuredSizes: java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>> = java.util.concurrent.ConcurrentHashMap(),
    private val media: PreviewMedia? = null,
    private val fonts: FontManager = FontManager(),
) : SurfaceView(context), SurfaceHolder.Callback {
    private var thread: RenderThread? = null
    private var loop: FrameLoop? = null
    private var scene: EditorScene? = null
    private var surfaceReady = false
    private var visible = true

    init {
        holder.addCallback(this)
        contentDescription = "Preview"
    }

    val fpsText: String
        get() = loop?.diagnostics()?.stats?.let { "%.0f fps".format(it.fps) } ?: "-"

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        try {
            val t = thread ?: createPipeline()
            t.attachSurface(holder.surface)
            updateLoop()
        } catch (e: Exception) {
            MlLog.e("EditorPreviewView", "GL setup failed", e)
            teardown()
        }
    }

    private fun createPipeline(): RenderThread {
        val t = RenderThread("ml-render-editor")
        val s = EditorScene(session, settings, measuredSizes, media, fonts)
        try {
            t.setContextListener(s)
        } catch (e: Exception) {
            t.release()
            throw e
        }
        thread = t
        scene = s
        loop = FrameLoop(t, display?.refreshRate ?: 60f, s)
        return t
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        loop?.setViewport(w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        loop?.stop()
        try {
            thread?.detachSurface()
        } catch (e: Exception) {
            MlLog.w("EditorPreviewView", "detach failed", e)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visible = visibility == View.VISIBLE
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        teardown()
    }

    private fun updateLoop() {
        val l = loop ?: return
        if (surfaceReady && visible) l.start() else l.stop()
    }

    private fun teardown() {
        loop?.stop()
        loop = null
        val t = thread
        val s = scene
        thread = null
        scene = null
        if (t != null) {
            try {
                if (s != null && !t.isReleased) t.call { s.close() }
            } catch (e: Exception) {
                MlLog.w("EditorPreviewView", "scene cleanup failed", e)
            }
            t.release()
        }
    }
}
