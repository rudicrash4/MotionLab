package id.motionlab.editor.render

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.SpeedLimits
import id.motionlab.editor.core.TrackType
import kotlin.math.abs

/**
 * Mengelola pemutar media untuk klip video & audio yang sedang / akan segera aktif.
 *
 *  - Video didecode oleh MediaCodec (hardware) lewat ExoPlayer langsung ke SurfaceTexture milik
 *    [GlCompositor], sehingga tidak ada salinan bitmap per frame (hemat memori & CPU).
 *  - Pemutar dibuat sedikit sebelum klip dimulai (pre-roll) dan dilepas begitu tidak diperlukan.
 *  - Jumlah decoder dibatasi karena perangkat hanya punya sedikit decoder hardware.
 *  - [sync] dipanggil setiap frame dari thread utama; ia menjaga posisi pemutar tetap selaras
 *    dengan [id.motionlab.editor.core.PlaybackClock] (koreksi drift).
 */
class VideoSourceManager(
    private val context: Context,
    private val renderer: PreviewRenderer,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val MAX_VIDEO = 3
        private const val MAX_AUDIO = 4
        private const val PREROLL_PLAYING_MS = 1500L
        private const val PREROLL_PAUSED_MS = 400L
        private const val DRIFT_PLAYING_MS = 300L
    }

    private class Source(val clipId: String, val isVideo: Boolean, val player: ExoPlayer) {
        var uri: String = ""
        var surfaceRequested = false
        var surfaceGen = -1
        var lastSeekAt = 0L
        var lastExpected = Long.MIN_VALUE
        var lastChangeAt = 0L
        var exactDone = true
    }

    private val sources = HashMap<String, Source>()

    private class Wanted(val clip: Clip, val muted: Boolean, val active: Boolean, val dist: Long)

    fun sync(project: Project, tMs: Long, playing: Boolean) {
        val lead = if (playing) PREROLL_PLAYING_MS else PREROLL_PAUSED_MS
        val videos = ArrayList<Wanted>()
        val audios = ArrayList<Wanted>()
        for (track in project.tracks) {
            if (track.type != TrackType.VIDEO && track.type != TrackType.AUDIO) continue
            val muted = !project.isTrackAudible(track)
            for (c in track.clips) {
                if (c.uri.isEmpty()) continue
                val active = tMs >= c.startMs && tMs < c.endMs
                val upcoming = tMs < c.startMs && c.startMs - tMs <= lead
                if (!active && !upcoming) continue
                // Video tersembunyi tidak perlu decoder sama sekali.
                if (track.type == TrackType.VIDEO && track.hidden) continue
                val w = Wanted(c, muted, active, if (active) 0L else c.startMs - tMs)
                if (track.type == TrackType.VIDEO) videos.add(w) else audios.add(w)
            }
        }
        videos.sortBy { it.dist }
        audios.sortBy { it.dist }
        val wanted = ArrayList<Wanted>()
        wanted.addAll(videos.take(MAX_VIDEO))
        wanted.addAll(audios.take(MAX_AUDIO))

        val wantedIds = HashSet<String>()
        for (w in wanted) wantedIds.add(w.clip.id)
        val drop = sources.keys.filter { !wantedIds.contains(it) }
        for (id in drop) releaseSource(id)

        val now = SystemClock.elapsedRealtime()
        for (w in wanted) {
            val src = sources[w.clip.id] ?: createSource(w.clip) ?: continue
            drive(src, w, tMs, playing, now)
        }
    }

    private fun createSource(clip: Clip): Source? {
        return try {
            val player = ExoPlayer.Builder(context).build()
            val src = Source(clip.id, clip.type == TrackType.VIDEO, player)
            src.uri = clip.uri
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val a = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        renderer.setVideoAspect(clip.id, a)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError("Gagal memutar \"" + clip.name + "\": " + (error.message ?: "kesalahan decoder"))
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.parse(clip.uri)))
            player.playWhenReady = false
            player.prepare()
            sources[clip.id] = src
            src
        } catch (e: Exception) {
            onError("Tidak bisa membuat pemutar: " + (e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun drive(src: Source, w: Wanted, tMs: Long, playing: Boolean, now: Long) {
        val clip = w.clip
        val player = src.player

        // 1) Surface video (diminta ulang jika EGL context dibuat ulang).
        if (src.isVideo && !src.surfaceRequested && src.surfaceGen != renderer.generation) {
            src.surfaceRequested = true
            if (src.surfaceGen >= 0) player.clearVideoSurface()
            renderer.requestVideoSurface(clip.id) { surface, gen ->
                src.surfaceRequested = false
                if (surface != null && sources[clip.id] === src) {
                    player.setVideoSurface(surface)
                    src.surfaceGen = gen
                }
            }
        }

        // 2) Posisi sumber yang diharapkan (integral kurva Speed Ramp), kecepatan & volume.
        val local = if (w.active) (tMs - clip.startMs).toDouble() else 0.0
        val expected = Math.round(clip.sourceTimeAt(local))
        val speedNow = clip.speedMap.speedAt(local)
        val pos = player.currentPosition
        var rate = speedNow
        if (w.active && playing) {
            // Koreksi drift halus: percepat / perlambat sedikit agar pemutar mengejar posisi yang diharapkan.
            val corr = ((expected - pos).toFloat() / 1000f).coerceIn(-0.08f, 0.08f)
            rate = speedNow * (1f + corr)
        }
        rate = rate.coerceIn(SpeedLimits.MIN, SpeedLimits.MAX)
        if (abs(player.playbackParameters.speed - rate) > 0.015f * rate) player.setPlaybackSpeed(rate)
        val vol = if (w.muted) 0f else (clip.volume * clip.fadeGain(local)).coerceIn(0f, 1f)
        if (abs(player.volume - vol) > 0.001f) player.volume = vol

        // 3) Sinkronisasi posisi.
        if (w.active) {
            if (player.playWhenReady != playing) player.playWhenReady = playing
            if (playing) {
                if (abs(pos - expected) > DRIFT_PLAYING_MS && now - src.lastSeekAt > 500L) {
                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    player.seekTo(expected)
                    src.lastSeekAt = now
                }
                src.exactDone = true
            } else {
                if (abs(expected - src.lastExpected) > 15L) {
                    src.lastChangeAt = now
                    src.exactDone = false
                    if (now - src.lastSeekAt >= 40L) {
                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC) // cepat saat scrubbing
                        player.seekTo(expected)
                        src.lastSeekAt = now
                    }
                } else if (!src.exactDone && now - src.lastChangeAt > 250L) {
                    player.setSeekParameters(SeekParameters.EXACT) // frame tepat setelah scrubbing berhenti
                    player.seekTo(expected)
                    src.lastSeekAt = now
                    src.exactDone = true
                }
            }
            src.lastExpected = expected
        } else {
            // Pre-roll: siapkan di titik awal, jangan diputar.
            if (player.playWhenReady) player.playWhenReady = false
            if (abs(pos - expected) > 60L && now - src.lastSeekAt > 300L) {
                player.setSeekParameters(SeekParameters.EXACT)
                player.seekTo(expected)
                src.lastSeekAt = now
            }
            src.lastExpected = Long.MIN_VALUE
        }
    }

    private fun releaseSource(id: String) {
        val src = sources.remove(id) ?: return
        try { src.player.release() } catch (_: Exception) { }
        if (src.isVideo) renderer.releaseVideoSurface(id)
    }

    /** Memaksa seek ulang pada sinkronisasi berikutnya (mis. setelah pengguna menggeser playhead). */
    fun invalidate() {
        for (s in sources.values) s.lastExpected = Long.MIN_VALUE
    }

    fun pauseAll() {
        for (s in sources.values) s.player.playWhenReady = false
    }

    fun release() {
        for (id in sources.keys.toList()) releaseSource(id)
    }
}
