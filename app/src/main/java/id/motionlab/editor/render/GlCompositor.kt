package id.motionlab.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.LayerGeometry
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.Props
import id.motionlab.editor.core.ShapeKind
import id.motionlab.editor.core.TrackType
import id.motionlab.editor.core.TransitionMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Compositor OpenGL ES 2.0: menggambar semua layer sebuah [Project] pada waktu tertentu ke framebuffer
 * yang sedang aktif. Dipakai oleh preview (GLSurfaceView) dan dirancang agar bisa dipakai ulang oleh
 * mesin ekspor (EGL offscreen + MediaCodec input surface) tanpa perubahan.
 *
 * SEMUA fungsi bertanda "GL thread" harus dipanggil dari thread yang memiliki EGL context.
 */
class GlCompositor(
    private val context: Context,
    private val onNeedRender: () -> Unit
) {
    companion object {
        private const val TAG = "GlCompositor"
        /** Tinggi referensi (px) untuk merender bitmap teks. Ukuran teks = pecahan tinggi kanvas. */
        private const val REF_H = 1080f
        private const val SHAPE_PX = 512
        private const val SHAPE_BASE = 0.4f   // ukuran dasar bentuk (pecahan tinggi kanvas)
        private const val MAX_IMAGE_DIM = 2048
        private const val SWEEP_EVERY_FRAMES = 240
    }

    private class Prog(val id: Int) {
        private val cache = HashMap<String, Int>()
        fun u(name: String): Int = cache.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
        val aPos: Int = GLES20.glGetAttribLocation(id, "aPos")
    }

    private class VideoTex(val texId: Int, val st: SurfaceTexture, val surface: Surface, val available: AtomicBoolean) {
        val matrix = FloatArray(16)
        var hasFrame = false
    }

    private class BmpTex(val id: Int, val w: Int, val h: Int)

    // ---- state (thread GL, kecuali yang concurrent) ----
    private var progTex: Prog? = null
    private var progOes: Prog? = null
    private var dummyLut = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
        position(0)
    }
    private val videos = HashMap<String, VideoTex>()
    private val bitmaps = HashMap<String, BmpTex>()
    private val videoAspects = ConcurrentHashMap<String, Float>()
    private var frameCounter = 0

    // ---- decode gambar di background ----
    private val loader = Executors.newSingleThreadExecutor()
    private val ready = ConcurrentHashMap<String, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    @Volatile var lastError: String = ""
        private set

    /** Mode ekspor: gambar & LUT didecode sinkron (frame tidak boleh dirender tanpa gambarnya). */
    @Volatile var blockingLoads: Boolean = false

    fun setVideoAspect(clipId: String, aspect: Float) {
        if (aspect > 0f) videoAspects[clipId] = aspect
    }

    fun videoAspect(clipId: String): Float? = videoAspects[clipId]

    // =========================================================== init / release (GL thread)

    fun initGl() {
        // Context baru: semua objek GL lama sudah tidak valid.
        for (v in videos.values) {
            try { v.surface.release() } catch (_: Exception) { }
            try { v.st.release() } catch (_: Exception) { }
        }
        videos.clear()
        bitmaps.clear()
        ready.clear()
        loading.clear()
        try {
            progTex = Prog(link(ShaderSources.VERTEX, ShaderSources.fragment(false)))
            progOes = Prog(link(ShaderSources.VERTEX, ShaderSources.fragment(true)))
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            dummyLut = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLut)
            setupTexParams(GLES20.GL_TEXTURE_2D)
            val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            px.put(byteArrayOf(0, 0, 0, 0)); px.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            lastError = ""
        } catch (e: Exception) {
            lastError = "Shader gagal: " + (e.message ?: e.javaClass.simpleName)
            Log.e(TAG, lastError, e)
            progTex = null
            progOes = null
        }
    }

    fun shutdown() {
        loader.shutdownNow()
    }

    private fun setupTexParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException(log)
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException(log)
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    // =========================================================== video surface (GL thread)

    /** Membuat SurfaceTexture eksternal untuk klip video; Surface-nya diberikan ke pemutar media. */
    fun createVideoSurface(clipId: String): Surface {
        releaseVideoSurface(clipId)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        setupTexParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val st = SurfaceTexture(ids[0])
        val avail = AtomicBoolean(false)
        st.setOnFrameAvailableListener {
            avail.set(true)
            onNeedRender()
        }
        val surface = Surface(st)
        videos[clipId] = VideoTex(ids[0], st, surface, avail)
        return surface
    }

    /**
     * Mode ekspor (GL thread): menunggu frame baru dari decoder untuk [clipId] lalu memasangnya ke tekstur.
     * Mengembalikan false bila frame tidak datang dalam [timeoutMs].
     */
    fun consumeVideoFrame(clipId: String, timeoutMs: Long): Boolean {
        val v = videos[clipId] ?: return false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!v.available.getAndSet(false)) {
            if (System.nanoTime() > deadline) return false
            try { Thread.sleep(1) } catch (_: InterruptedException) { return false }
        }
        return try {
            v.st.updateTexImage()
            v.st.getTransformMatrix(v.matrix)
            v.hasFrame = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage gagal", e)
            false
        }
    }

    fun releaseVideoSurface(clipId: String) {
        val v = videos.remove(clipId) ?: return
        try { v.surface.release() } catch (_: Exception) { }
        try { v.st.release() } catch (_: Exception) { }
        GLES20.glDeleteTextures(1, intArrayOf(v.texId), 0)
    }

    // =========================================================== render (GL thread)

    fun renderFrame(project: Project, tMs: Long, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (progTex == null || progOes == null || w <= 0 || h <= 0) return

        pumpBitmaps()
        for (v in videos.values) {
            if (v.available.getAndSet(false)) {
                try {
                    v.st.updateTexImage()
                    v.st.getTransformMatrix(v.matrix)
                    v.hasFrame = true
                } catch (e: Exception) {
                    Log.w(TAG, "updateTexImage gagal", e)
                }
            }
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val aspect = w.toFloat() / h.toFloat()
        // Indeks 0 = paling depan, jadi gambar dari belakang (indeks terbesar) ke depan.
        for (ti in project.tracks.indices.reversed()) {
            val track = project.tracks[ti]
            if (track.hidden || track.type == TrackType.AUDIO) continue
            for (clip in track.clips) {
                if (tMs < clip.startMs || tMs >= clip.endMs) continue
                try {
                    drawClip(clip, tMs, aspect)
                } catch (e: Exception) {
                    Log.w(TAG, "drawClip gagal", e)
                }
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)

        frameCounter++
        if (frameCounter % SWEEP_EVERY_FRAMES == 0) sweep(project, aspect)
    }

    private fun drawClip(clip: Clip, tMs: Long, aspect: Float) {
        val local = tMs - clip.startMs
        val fx = TransitionMath.compute(clip, local)
        if (fx.alpha <= 0.002f) return

        var tex = 0
        var external = false
        var stMatrix: FloatArray? = null
        val wFrac: Float
        val hFrac: Float
        when (clip.type) {
            TrackType.VIDEO -> {
                val v = videos[clip.id] ?: return
                if (!v.hasFrame) return
                val a = videoAspects[clip.id] ?: return
                val fit = LayerGeometry.fit(a, aspect)
                wFrac = fit.first; hFrac = fit.second
                tex = v.texId; external = true; stMatrix = v.matrix
            }
            TrackType.IMAGE -> {
                val b = ensureImage(clip) ?: return
                val fit = LayerGeometry.fit(b.w.toFloat() / b.h.toFloat(), aspect)
                wFrac = fit.first; hFrac = fit.second
                tex = b.id
            }
            TrackType.TEXT -> {
                val b = ensureText(clip, aspect) ?: return
                wFrac = (b.w / REF_H) / aspect
                hFrac = b.h / REF_H
                tex = b.id
            }
            TrackType.SHAPE -> {
                val b = ensureShape(clip) ?: return
                wFrac = SHAPE_BASE / aspect
                hFrac = SHAPE_BASE
                tex = b.id
            }
            TrackType.AUDIO -> return
        }

        val prog = (if (external) progOes else progTex) ?: return
        GLES20.glUseProgram(prog.id)

        val x = clip.valueAt(Props.X, local) + fx.dx
        val y = clip.valueAt(Props.Y, local)
        val scale = clip.valueAt(Props.SCALE, local) * fx.scale
        val stretch = clip.valueAt(Props.STRETCH, local)
        val rot = (clip.valueAt(Props.ROTATION, local) * PI / 180.0).toFloat()
        val opacity = clip.valueAt(Props.OPACITY, local) * fx.alpha

        GLES20.glUniform2f(prog.u("uSize"), wFrac * scale * stretch, hFrac * scale)
        GLES20.glUniform2f(prog.u("uPos"), x, y)
        GLES20.glUniform1f(prog.u("uAspect"), aspect)
        GLES20.glUniform2f(prog.u("uRot"), cos(rot), sin(rot))

        GLES20.glUniform1f(prog.u("uOpacity"), opacity.coerceIn(0f, 1f))
        GLES20.glUniform1f(prog.u("uBrightness"), clip.fxValue(Props.BRIGHTNESS, local))
        GLES20.glUniform1f(prog.u("uContrast"), clip.fxValue(Props.CONTRAST, local))
        GLES20.glUniform1f(prog.u("uSaturation"), clip.fxValue(Props.SATURATION, local))
        GLES20.glUniform1f(prog.u("uTemperature"), clip.fxValue(Props.TEMPERATURE, local))
        GLES20.glUniform1f(prog.u("uVignette"), clip.fxValue(Props.VIGNETTE, local))
        GLES20.glUniform1f(prog.u("uWave"), clip.fxValue(Props.WAVE, local))
        GLES20.glUniform1f(prog.u("uSwirl"), clip.fxValue(Props.SWIRL, local))
        GLES20.glUniform1f(prog.u("uDisplace"), clip.fxValue(Props.DISPLACE, local))
        GLES20.glUniform1f(prog.u("uBlur"), clip.fxValue(Props.BLUR, local))
        GLES20.glUniform1f(prog.u("uGlow"), clip.fxValue(Props.GLOW, local))
        GLES20.glUniform1f(prog.u("uGlitch"), fx.glitch)
        GLES20.glUniform1f(prog.u("uWipe"), fx.wipe)
        GLES20.glUniform1f(prog.u("uTime"), (tMs % 100000L) / 1000f)
        val wo = clip.warpOrder()
        GLES20.glUniform3f(prog.u("uWarpOrder"), wo[0], wo[1], wo[2])

        var lutTex = dummyLut
        var lutMix = 0f
        if (clip.lutUri.isNotEmpty()) {
            val lt = ensureLut(clip.lutUri)
            if (lt != null) {
                lutTex = lt.id
                lutMix = clip.fxValue(Props.LUT, local)
            }
        }
        GLES20.glUniform1f(prog.u("uLutMix"), lutMix)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (external) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glUniformMatrix4fv(prog.u("uSt"), 1, false, stMatrix ?: IDENTITY, 0)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        }
        GLES20.glUniform1i(prog.u("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        GLES20.glUniform1i(prog.u("uLut"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(prog.aPos)
        GLES20.glVertexAttribPointer(prog.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(prog.aPos)
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    // =========================================================== bitmap textures

    /** Mengunggah bitmap yang sudah selesai didecode di thread background (dipanggil di awal frame). */
    private fun pumpBitmaps() {
        if (ready.isEmpty()) return
        val keys = ArrayList(ready.keys)
        var uploaded = 0
        for (k in keys) {
            if (uploaded >= 2) { onNeedRender(); break } // batasi unggahan per frame agar tidak patah
            val bmp = ready.remove(k) ?: continue
            loading.remove(k)
            val t = uploadBitmap(bmp)
            bmp.recycle()
            if (t != null) bitmaps[k] = t
            uploaded++
        }
    }

    private fun uploadBitmap(src: Bitmap): BmpTex? {
        var b: Bitmap = src
        if (b.config != Bitmap.Config.ARGB_8888 || b.rowBytes != b.width * 4) {
            b = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        setupTexParams(GLES20.GL_TEXTURE_2D)
        val buf = ByteBuffer.allocateDirect(b.width * b.height * 4).order(ByteOrder.nativeOrder())
        b.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, b.width, b.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val out = BmpTex(ids[0], b.width, b.height)
        if (b !== src) b.recycle()
        return out
    }

    private fun ensureImage(clip: Clip): BmpTex? {
        if (clip.uri.isEmpty()) return null
        val key = "img:" + clip.uri
        bitmaps[key]?.let { return it }
        if (blockingLoads) return loadNow(key, clip.uri, MAX_IMAGE_DIM, true)
        requestDecode(key, clip.uri, MAX_IMAGE_DIM, true)
        return null
    }

    private fun ensureLut(uri: String): BmpTex? {
        val key = "lut:$uri"
        bitmaps[key]?.let { return it }
        if (blockingLoads) return loadNow(key, uri, 512, false)
        requestDecode(key, uri, 512, false)
        return null
    }

    private fun loadNow(key: String, uri: String, maxDim: Int, applyExif: Boolean): BmpTex? {
        if (failed.contains(key)) return null
        val bmp = try { decode(uri, maxDim, applyExif) } catch (e: Throwable) { Log.w(TAG, "decode gagal", e); null }
        if (bmp == null) { failed.add(key); return null }
        val t = uploadBitmap(bmp)
        bmp.recycle()
        if (t != null) bitmaps[key] = t
        return t
    }

    /** Daftar klip gambar/LUT yang gagal dimuat (untuk peringatan ekspor). */
    fun failedAssets(): Set<String> = HashSet(failed)

    private fun requestDecode(key: String, uri: String, maxDim: Int, applyExif: Boolean) {
        if (failed.contains(key) || ready.containsKey(key)) return
        if (!loading.add(key)) return
        loader.execute {
            val bmp = try { decode(uri, maxDim, applyExif) } catch (e: Throwable) { Log.w(TAG, "decode gagal", e); null }
            if (bmp != null) {
                ready[key] = bmp
            } else {
                failed.add(key)
                loading.remove(key)
            }
            onNeedRender()
        }
    }

    private fun decode(uriString: String, maxDim: Int, applyExif: Boolean): Bitmap? {
        val uri = Uri.parse(uriString)
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (applyExif) {
            var deg = 0
            try {
                cr.openInputStream(uri)?.use {
                    val o = android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    deg = when (o) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (_: Exception) { }
            if (deg != 0) {
                val m = Matrix()
                m.postRotate(deg.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
        }
        return bmp
    }

    // ---- teks & bentuk (dirender dengan Canvas lalu diunggah sebagai tekstur) ----

    private fun textKey(clip: Clip, aspect: Float): String =
        "txt:${clip.id}:${clip.text.hashCode()}:${clip.textColor}:${clip.textSize}:${clip.fontWeight}:${clip.italic}:" +
            "${clip.fontUri}:${clip.textAlign}:${clip.letterSpacing}:${clip.lineSpacing}:${clip.strokeWidth}:${clip.strokeColor}:" +
            "${clip.shadowSize}:${clip.shadowColor}:${clip.bgColor}:${(aspect * 100).toInt()}"

    private fun shapeKey(clip: Clip): String = "shp:${clip.shape.name}:${clip.fillColor}"

    private fun ensureText(clip: Clip, aspect: Float): BmpTex? {
        val key = textKey(clip, aspect)
        bitmaps[key]?.let { return it }
        val bmp = makeTextBitmap(clip, aspect) ?: return null
        val t = uploadBitmap(bmp)
        bmp.recycle()
        if (t != null) bitmaps[key] = t
        return t
    }

    private val typefaces = HashMap<String, Typeface>()

    private fun resolveTypeface(clip: Clip): Typeface {
        val key = clip.fontUri + "|" + clip.fontWeight + "|" + clip.italic
        typefaces[key]?.let { return it }
        var base: Typeface = Typeface.DEFAULT
        if (clip.fontUri.isNotEmpty()) {
            try {
                val f = java.io.File(clip.fontUri)
                if (f.exists()) base = Typeface.createFromFile(f)
            } catch (e: Exception) {
                Log.w(TAG, "font gagal dimuat", e)
            }
        }
        val weight = clip.fontWeight.coerceIn(100, 900)
        val tf = if (android.os.Build.VERSION.SDK_INT >= 28) {
            Typeface.create(base, weight, clip.italic)
        } else {
            val style = when {
                weight >= 600 && clip.italic -> Typeface.BOLD_ITALIC
                weight >= 600 -> Typeface.BOLD
                clip.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(base, style)
        }
        typefaces[key] = tf
        return tf
    }

    private fun makeTextBitmap(clip: Clip, aspect: Float): Bitmap? {
        val text = if (clip.text.isEmpty()) " " else clip.text
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = clip.textColor
        tp.textSize = (clip.textSize * REF_H).coerceAtLeast(8f)
        tp.typeface = resolveTypeface(clip)
        tp.letterSpacing = clip.letterSpacing
        if (clip.shadowSize > 0.001f) {
            tp.setShadowLayer(tp.textSize * clip.shadowSize, 0f, tp.textSize * clip.shadowSize * 0.5f, clip.shadowColor)
        }
        val strokePx = clip.strokeWidth * tp.textSize
        val maxW = (REF_H * aspect * 0.9f).toInt().coerceAtLeast(64)
        val desired = Math.ceil(StaticLayout.getDesiredWidth(text, tp).toDouble()).toInt()
        val width = desired.coerceIn(8, maxW)
        val align = when (clip.textAlign) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        fun build(paint: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(align)
                .setLineSpacing(0f, clip.lineSpacing.coerceIn(0.5f, 3f))
                .build()
        val layout = build(tp)
        val shadowPad = tp.textSize * clip.shadowSize * 1.6f
        val pad = Math.ceil((tp.textSize * 0.15f + strokePx + shadowPad).toDouble()).toInt()
        val bw = (width + pad * 2).coerceAtMost(4096)
        val bh = (layout.height + pad * 2).coerceIn(8, 4096)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (Color.alpha(clip.bgColor) > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.color = clip.bgColor
            canvas.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), tp.textSize * 0.25f, tp.textSize * 0.25f, bg)
        }
        canvas.translate(pad.toFloat(), pad.toFloat())
        if (strokePx > 0.1f) {
            val sp = TextPaint(tp)
            sp.clearShadowLayer()
            sp.style = Paint.Style.STROKE
            sp.strokeWidth = strokePx * 2f
            sp.strokeJoin = Paint.Join.ROUND
            sp.color = clip.strokeColor
            build(sp).draw(canvas)
        }
        layout.draw(canvas)
        return bmp
    }

    private fun ensureShape(clip: Clip): BmpTex? {
        val key = shapeKey(clip)
        bitmaps[key]?.let { return it }
        val bmp = makeShapeBitmap(clip.shape, clip.fillColor)
        val t = uploadBitmap(bmp)
        bmp.recycle()
        if (t != null) bitmaps[key] = t
        return t
    }

    private fun makeShapeBitmap(kind: ShapeKind, color: Int): Bitmap {
        val s = SHAPE_PX
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        p.style = Paint.Style.FILL
        val m = s * 0.04f
        val r = RectF(m, m, s - m, s - m)
        when (kind) {
            ShapeKind.RECT -> c.drawRect(r, p)
            ShapeKind.ROUNDED -> c.drawRoundRect(r, s * 0.14f, s * 0.14f, p)
            ShapeKind.ELLIPSE -> c.drawOval(r, p)
            ShapeKind.TRIANGLE -> {
                val path = Path()
                path.moveTo(s / 2f, m)
                path.lineTo(s - m, s - m)
                path.lineTo(m, s - m)
                path.close()
                c.drawPath(path, p)
            }
            ShapeKind.STAR -> {
                val path = Path()
                val cx = s / 2f
                val cy = s / 2f
                val outer = s / 2f - m
                val inner = outer * 0.42f
                for (i in 0 until 10) {
                    val ang = -PI / 2.0 + i * PI / 5.0
                    val rad = if (i % 2 == 0) outer else inner
                    val px = (cx + rad * cos(ang)).toFloat()
                    val py = (cy + rad * sin(ang)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, p)
            }
            ShapeKind.LINE -> c.drawRect(RectF(m, s * 0.47f, s - m, s * 0.53f), p)
        }
        return bmp
    }

    // =========================================================== pembersihan memori

    /** Menghapus tekstur yang tidak lagi dipakai oleh klip mana pun (cegah kebocoran memori GPU). */
    private fun sweep(project: Project, aspect: Float) {
        val valid = HashSet<String>()
        for (t in project.tracks) for (c in t.clips) {
            when (c.type) {
                TrackType.IMAGE -> valid.add("img:" + c.uri)
                TrackType.TEXT -> valid.add(textKey(c, aspect))
                TrackType.SHAPE -> valid.add(shapeKey(c))
                else -> Unit
            }
            if (c.lutUri.isNotEmpty()) valid.add("lut:" + c.lutUri)
        }
        val it = bitmaps.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!valid.contains(e.key)) {
                GLES20.glDeleteTextures(1, intArrayOf(e.value.id), 0)
                it.remove()
            }
        }
        val liveVideoClips = HashSet<String>()
        for (t in project.tracks) for (c in t.clips) if (c.type == TrackType.VIDEO) liveVideoClips.add(c.id)
        videoAspects.keys.retainAll(liveVideoClips)
    }
}
