package id.motionlab.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors

/**
 * Thumbnail frame video untuk filmstrip di timeline. Decode berjalan di SATU thread terpisah
 * (retriever dipakai ulang per file) sehingga tidak membebani thread UI maupun decoder preview.
 * Memori dibatasi oleh LruCache.
 */
class ThumbnailStore(private val context: Context, private val scope: CoroutineScope) {
    private val cache = LruCache<String, ImageBitmap>(48)
    private val pending = HashSet<String>()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val retrievers = HashMap<String, MediaMetadataRetriever>() // hanya diakses di thread `dispatcher`

    /** Dibaca di Canvas agar digambar ulang saat thumbnail baru siap. */
    val version = mutableIntStateOf(0)

    /** Mengembalikan thumbnail pada waktu sumber [srcMs] (dibulatkan ke detik) atau null dan memulai pemuatan. */
    fun get(uri: String, srcMs: Long): ImageBitmap? {
        val bucket = (srcMs.coerceAtLeast(0L) / 1000L) * 1000L
        val key = "$uri@$bucket"
        cache.get(key)?.let { return it }
        if (pending.size < 24 && pending.add(key)) {
            scope.launch {
                val bmp = withContext(dispatcher) { load(uri, bucket) }
                pending.remove(key)
                if (bmp != null) {
                    cache.put(key, bmp)
                    version.intValue = version.intValue + 1
                }
            }
        }
        return null
    }

    private fun load(uri: String, ms: Long): ImageBitmap? {
        return try {
            var r = retrievers[uri]
            if (r == null) {
                r = MediaMetadataRetriever()
                r.setDataSource(context, Uri.parse(uri))
                retrievers[uri] = r
            }
            val frame = r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val h = 96
            val w = (frame.width.toFloat() / frame.height.toFloat() * h).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
            if (scaled !== frame) frame.recycle()
            scaled.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    // ---- waveform asli (puncak amplitudo per 10 ms dari audio sumber) ----
    private val waveDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val peakCache = HashMap<String, FloatArray>()
    private val peakPending = HashSet<String>()
    private val peakFailed = HashSet<String>()

    /** Puncak amplitudo (0..1, 1 nilai per 10 ms waktu sumber) atau null selagi dihitung / bila gagal. */
    fun peaks(uri: String): FloatArray? {
        peakCache[uri]?.let { return it }
        if (peakFailed.contains(uri) || !peakPending.add(uri)) return null
        scope.launch {
            val p = withContext(waveDispatcher) {
                try { id.motionlab.editor.export.AudioDecoder.peaks(context, uri) { false } } catch (e: Throwable) { null }
            }
            peakPending.remove(uri)
            if (p != null) peakCache[uri] = p else peakFailed.add(uri)
            version.intValue = version.intValue + 1
        }
        return null
    }

    fun release() {
        dispatcher.close()
        waveDispatcher.close()
    }
}
