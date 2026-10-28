package id.motionlab.editor.core

/**
 * Konversi antara koordinat frame video (0..1, kiri-atas = 0,0) dan koordinat kanvas
 * (pecahan lebar/tinggi kanvas dari titik tengah) dengan memperhitungkan posisi, skala & stretch
 * layer video pada waktu tertentu. Rotasi layer video diabaikan pada versi ini.
 */
object TrackMapping {

    fun videoToCanvas(
        video: Clip, localMs: Long, videoAspect: Float, canvasAspect: Float, nx: Float, ny: Float
    ): Pair<Float, Float> {
        val fit = LayerGeometry.fit(videoAspect, canvasAspect)
        val s = video.valueAt(Props.SCALE, localMs)
        val st = video.valueAt(Props.STRETCH, localMs)
        val px = video.valueAt(Props.X, localMs)
        val py = video.valueAt(Props.Y, localMs)
        val cx = px + (nx - 0.5f) * fit.first * s * st
        val cy = py + (ny - 0.5f) * fit.second * s
        return Pair(cx, cy)
    }

    fun canvasToVideo(
        video: Clip, localMs: Long, videoAspect: Float, canvasAspect: Float, cx: Float, cy: Float
    ): Pair<Float, Float> {
        val fit = LayerGeometry.fit(videoAspect, canvasAspect)
        val s = video.valueAt(Props.SCALE, localMs)
        val st = video.valueAt(Props.STRETCH, localMs)
        val px = video.valueAt(Props.X, localMs)
        val py = video.valueAt(Props.Y, localMs)
        val w = (fit.first * s * st).coerceAtLeast(0.0001f)
        val h = (fit.second * s).coerceAtLeast(0.0001f)
        return Pair((cx - px) / w + 0.5f, (cy - py) / h + 0.5f)
    }

    /** Klip video paling depan yang aktif (dan terlihat) pada waktu [tMs]. */
    fun videoClipAt(project: Project, tMs: Long): Clip? {
        for (t in project.tracks) {
            if (t.type != TrackType.VIDEO || t.hidden) continue
            for (c in t.clips) if (tMs >= c.startMs && tMs < c.endMs && c.uri.isNotEmpty()) return c
        }
        return null
    }
}
