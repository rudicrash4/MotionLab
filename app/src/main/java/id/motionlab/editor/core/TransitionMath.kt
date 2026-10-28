package id.motionlab.editor.core

/** Parameter visual hasil evaluasi transisi masuk/keluar sebuah klip pada satu waktu. */
class TransitionFx {
    var alpha = 1f      // pengali opacity
    var dx = 0f         // offset posisi X (pecahan lebar kanvas)
    var scale = 1f      // pengali skala
    var glitch = 0f     // 0..1 intensitas glitch
    var wipe = 2f       // >1 = tanpa wipe; 0..1 = bagian kiri yang terlihat
}

/**
 * Transisi diterapkan sebagai animasi masuk/keluar pada awal/akhir klip (di atas layer di bawahnya).
 * Untuk crossfade antar klip, letakkan klip kedua di track berbeda yang tumpang-tindih,
 * lalu beri transisi masuk "Dissolve" pada klip atas.
 */
object TransitionMath {

    private fun smooth(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun compute(clip: Clip, localMs: Long): TransitionFx {
        val fx = TransitionFx()
        val tin = clip.transitionIn
        if (tin.type != TransitionType.NONE && tin.durationMs > 0L && localMs < tin.durationMs) {
            val p = smooth(localMs.toFloat() / tin.durationMs.toFloat())
            applyIn(fx, tin.type, p)
        }
        val tout = clip.transitionOut
        val remaining = clip.durationMs - localMs
        if (tout.type != TransitionType.NONE && tout.durationMs > 0L && remaining < tout.durationMs) {
            val p = smooth(remaining.toFloat() / tout.durationMs.toFloat())
            applyOut(fx, tout.type, p)
        }
        return fx
    }

    /** p: 0 (belum tampil) -> 1 (tampil penuh). */
    private fun applyIn(fx: TransitionFx, type: TransitionType, p: Float) {
        when (type) {
            TransitionType.DISSOLVE -> fx.alpha *= p
            TransitionType.SLIDE_LEFT -> fx.dx += (1f - p)
            TransitionType.SLIDE_RIGHT -> fx.dx -= (1f - p)
            TransitionType.ZOOM -> { fx.scale *= 1.6f - 0.6f * p; fx.alpha *= p }
            TransitionType.GLITCH -> { fx.glitch = maxOf(fx.glitch, 1f - p); fx.alpha *= 0.35f + 0.65f * p }
            TransitionType.WIPE -> fx.wipe = minOf(fx.wipe, p)
            TransitionType.NONE -> Unit
        }
    }

    /** p: 1 (tampil penuh) -> 0 (hilang). */
    private fun applyOut(fx: TransitionFx, type: TransitionType, p: Float) {
        when (type) {
            TransitionType.DISSOLVE -> fx.alpha *= p
            TransitionType.SLIDE_LEFT -> fx.dx -= (1f - p)
            TransitionType.SLIDE_RIGHT -> fx.dx += (1f - p)
            TransitionType.ZOOM -> { fx.scale *= 1f + 0.6f * (1f - p); fx.alpha *= p }
            TransitionType.GLITCH -> { fx.glitch = maxOf(fx.glitch, 1f - p); fx.alpha *= 0.35f + 0.65f * p }
            TransitionType.WIPE -> fx.wipe = minOf(fx.wipe, p)
            TransitionType.NONE -> Unit
        }
    }
}
