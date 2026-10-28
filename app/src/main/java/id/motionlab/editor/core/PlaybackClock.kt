package id.motionlab.editor.core

/**
 * Jam pemutaran tunggal yang dipakai bersama oleh UI (thread utama), renderer OpenGL (thread GL)
 * dan pemutar media. Berbasis System.nanoTime sehingga tidak bergantung pada frame UI:
 * thread GL cukup memanggil [positionMs] pada setiap frame.
 */
class PlaybackClock {
    private var basePos = 0L
    private var baseNanos = 0L
    private var isPlaying = false

    @Synchronized
    fun positionMs(): Long =
        if (isPlaying) basePos + (System.nanoTime() - baseNanos) / 1_000_000L else basePos

    @Synchronized
    fun playing(): Boolean = isPlaying

    @Synchronized
    fun seek(ms: Long) {
        basePos = if (ms < 0L) 0L else ms
        baseNanos = System.nanoTime()
    }

    @Synchronized
    fun play() {
        if (!isPlaying) {
            baseNanos = System.nanoTime()
            isPlaying = true
        }
    }

    @Synchronized
    fun pause() {
        if (isPlaying) {
            basePos += (System.nanoTime() - baseNanos) / 1_000_000L
            isPlaying = false
        }
    }
}
