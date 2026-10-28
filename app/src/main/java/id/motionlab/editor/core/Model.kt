package id.motionlab.editor.core

import java.util.UUID

/** Jenis track / layer di timeline. */
enum class TrackType { VIDEO, AUDIO, IMAGE, TEXT, SHAPE }

enum class ShapeKind { RECT, ROUNDED, ELLIPSE, TRIANGLE, STAR, LINE }

enum class TransitionType { NONE, DISSOLVE, SLIDE_LEFT, SLIDE_RIGHT, ZOOM, GLITCH, WIPE }

/** Kurva animasi dari sebuah keyframe menuju keyframe berikutnya. */
enum class Interp { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD, CUSTOM }

data class Transition(
    val type: TransitionType = TransitionType.NONE,
    val durationMs: Long = 500L
)

/**
 * Satu keyframe. [timeMs] adalah waktu LOKAL terhadap awal klip (bukan waktu timeline),
 * sehingga memindahkan klip tidak merusak animasi.
 * Parameter bx1..by2 hanya dipakai jika [interp] == CUSTOM (cubic-bezier seperti CSS).
 */
data class Keyframe(
    val timeMs: Long,
    val value: Float,
    val interp: Interp = Interp.EASE_IN_OUT,
    val bx1: Float = 0.42f,
    val by1: Float = 0f,
    val bx2: Float = 0.58f,
    val by2: Float = 1f
)

data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val type: TrackType,
    val name: String,
    val uri: String = "",
    val startMs: Long,
    val durationMs: Long,
    /** Offset awal pada file sumber (untuk trimming video/audio). */
    val sourceInMs: Long = 0L,
    /** Durasi total file sumber. 0 = tidak terbatas (gambar, teks, bentuk). */
    val sourceDurationMs: Long = 0L,
    val volume: Float = 1f,
    /** Nilai dasar properti (lihat [Props]). Properti yang tidak ada memakai nilai default. */
    val props: Map<String, Float> = emptyMap(),
    val keyframes: Map<String, KeyframeTrack> = emptyMap(),
    val text: String = "",
    val textColor: Int = 0xFFFFFFFF.toInt(),
    /** Tinggi huruf sebagai pecahan dari tinggi kanvas. */
    val textSize: Float = 0.07f,
    /** Gaya teks. [fontWeight] 100..900; [fontUri] = path file font (TTF/OTF) yang disalin ke penyimpanan aplikasi. */
    val fontWeight: Int = 700,
    val italic: Boolean = false,
    val fontUri: String = "",
    val fontName: String = "",
    /** 0 = kiri, 1 = tengah, 2 = kanan. */
    val textAlign: Int = 1,
    /** Jarak huruf dalam em, pengali jarak baris. */
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
    /** Tebal outline sebagai pecahan ukuran huruf (0 = tanpa outline). */
    val strokeWidth: Float = 0f,
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Ukuran bayangan sebagai pecahan ukuran huruf (0 = tanpa bayangan). */
    val shadowSize: Float = 0.06f,
    val shadowColor: Int = 0x99000000.toInt(),
    /** Warna latar belakang teks (ARGB); 0 = tanpa latar. */
    val bgColor: Int = 0,
    /** Efek/warna yang dimatikan (kunci properti). Nilainya dipertahankan, tetapi tidak dirender. */
    val fxDisabled: Set<String> = emptySet(),
    /** Urutan warp (wave, swirl, displace) pada render. */
    val fxOrder: List<String> = listOf(Props.WAVE, Props.SWIRL, Props.DISPLACE),
    val shape: ShapeKind = ShapeKind.RECT,
    val fillColor: Int = 0xFF8B7CFF.toInt(),
    val transitionIn: Transition = Transition(),
    val transitionOut: Transition = Transition(),
    val lutUri: String = "",
    /** Fade audio (ms) di awal / akhir klip; berlaku juga untuk audio dari klip video. */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L
) {
    val endMs: Long get() = startMs + durationMs

    fun baseValue(key: String): Float = props[key] ?: Props.def(key)

    /** Nilai properti pada waktu lokal [localMs] (memperhitungkan keyframe). */
    fun valueAt(key: String, localMs: Long): Float {
        val kt = keyframes[key]
        val base = baseValue(key)
        return if (kt == null || kt.frames.isEmpty()) base else kt.evaluate(localMs, base)
    }

    /** Nilai efektif untuk render: efek yang dimatikan memakai nilai netral. */
    fun fxValue(key: String, localMs: Long): Float =
        if (fxDisabled.contains(key)) Props.def(key) else valueAt(key, localMs)

    /** Indeks urutan warp untuk shader: [0]=posisi ke-1 ... nilai 0=wave, 1=swirl, 2=displace. */
    fun warpOrder(): FloatArray {
        val names = listOf(Props.WAVE, Props.SWIRL, Props.DISPLACE)
        val list = ArrayList<String>()
        for (k in fxOrder) if (names.contains(k) && !list.contains(k)) list.add(k)
        for (k in names) if (!list.contains(k)) list.add(k)
        return FloatArray(3) { names.indexOf(list[it]).toFloat() }
    }

    fun hasKeyframes(key: String): Boolean = keyframes[key]?.frames?.isNotEmpty() == true

    /** Gain 0..1 dari fade in/out pada waktu lokal [localMs] (kurva linear). */
    fun fadeGain(localMs: Double): Float {
        var g = 1.0
        if (fadeInMs > 0L && localMs < fadeInMs) g = g * (localMs / fadeInMs).coerceIn(0.0, 1.0)
        val remain = durationMs - localMs
        if (fadeOutMs > 0L && remain < fadeOutMs) g = g * (remain / fadeOutMs).coerceIn(0.0, 1.0)
        return g.toFloat()
    }

    // ---- Speed Ramp: waktu sumber = sourceIn + integral kecepatan ----

    val speedMap: SpeedMap get() = SpeedMaps.of(this)

    val hasSpeedRamp: Boolean get() = hasKeyframes(Props.SPEED)

    /** Kecepatan (kelipatan) pada waktu lokal klip. */
    fun speedAt(localMs: Long): Float = speedMap.speedAt(localMs.toDouble())

    /** Posisi pada file sumber (ms, presisi desimal) untuk waktu lokal [localMs]. */
    fun sourceTimeAt(localMs: Double): Double = sourceInMs.toDouble() + speedMap.offset(localMs)

    fun sourceTimeAt(localMs: Long): Long = Math.round(sourceTimeAt(localMs.toDouble()))

    /** Posisi sumber saat klip berakhir. */
    val sourceEndMs: Long get() = sourceTimeAt(durationMs)
}

data class Track(
    val id: String = UUID.randomUUID().toString(),
    val type: TrackType,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    /** Solo: bila ada track solo, hanya track solo yang terdengar. */
    val solo: Boolean = false
)

/**
 * Proyek. Urutan [tracks]: indeks 0 = lapisan paling ATAS (paling depan) dan baris paling atas di UI.
 */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val name: String = "Proyek 001",
    val aspectW: Int = 9,
    val aspectH: Int = 16,
    /** Sisi pendek resolusi ekspor (720 / 1080 / 2160). */
    val shortSide: Int = 1080,
    val fps: Int = 30,
    val tracks: List<Track> = emptyList()
) {
    /** Apakah track ini terdengar (mute/solo/hidden video diperhitungkan). */
    fun isTrackAudible(t: Track): Boolean {
        if (t.muted) return false
        if (t.type == TrackType.VIDEO && t.hidden) return false
        if (tracks.any { it.solo } && !t.solo) return false
        return true
    }

    val canvasAspect: Float get() = aspectW.toFloat() / aspectH.toFloat()

    val durationMs: Long
        get() {
            var m = 0L
            for (t in tracks) for (c in t.clips) if (c.endMs > m) m = c.endMs
            return m
        }

    fun findClip(id: String?): Clip? {
        if (id == null) return null
        for (t in tracks) for (c in t.clips) if (c.id == id) return c
        return null
    }

    fun trackIndexOfClip(id: String?): Int {
        if (id == null) return -1
        for ((i, t) in tracks.withIndex()) if (t.clips.any { it.id == id }) return i
        return -1
    }

    /** Ukuran ekspor (lebar, tinggi) genap, sesuai rasio kanvas. */
    fun exportSize(): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (aspectW >= aspectH) {
            h = shortSide
            w = Math.round(shortSide.toFloat() * aspectW / aspectH)
        } else {
            w = shortSide
            h = Math.round(shortSide.toFloat() * aspectH / aspectW)
        }
        return Pair(w and 1.inv(), h and 1.inv())
    }
}

/** Geometri layer: ukuran "fit" (pecahan lebar & tinggi kanvas) untuk media dengan rasio tertentu. */
object LayerGeometry {
    /** Mengembalikan (lebar, tinggi) sebagai pecahan dari lebar & tinggi kanvas, scale=1 -> pas di dalam kanvas. */
    fun fit(mediaAspect: Float, canvasAspect: Float): Pair<Float, Float> {
        return if (mediaAspect > canvasAspect) {
            Pair(1f, canvasAspect / mediaAspect)
        } else {
            Pair(mediaAspect / canvasAspect, 1f)
        }
    }
}
