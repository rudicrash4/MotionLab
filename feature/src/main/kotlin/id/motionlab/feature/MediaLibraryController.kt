package id.motionlab.feature

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.media.AssetImporter
import id.motionlab.engine.media.BitmapProvider
import id.motionlab.engine.media.ImportOutcome
import id.motionlab.engine.media.MediaAccess
import id.motionlab.engine.media.WaveformProvider
import id.motionlab.engine.text.FontManager
import id.motionlab.engine.timeline.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LibraryCategory(val label: String, val kinds: Set<String>) {
    IMAGES("Images", setOf("IMAGE")),
    VIDEOS("Videos", setOf("VIDEO")),
    AUDIO("Audio", setOf("AUDIO")),
    FONTS("Fonts", setOf("FONT")),
}

enum class LibrarySort(val label: String) { NEWEST("Newest"), NAME("Name"), DURATION("Duration"), SIZE("Size") }

/**
 * The editor's media library. All file work (import, access checks, thumbnails) runs on [Dispatchers.IO]; the state the UI
 * reads is plain Compose state. Asset references live in the [EditorSession] (so they are saved with the project and
 * autosaved); this class never deletes a user's file.
 */
class MediaLibraryController(
    context: Context,
    val session: EditorSession,
    private val scope: CoroutineScope,
    val fonts: FontManager = FontManager(),
) {
    private val appContext = context.applicationContext
    private val importer = AssetImporter(appContext)
    val bitmaps = BitmapProvider(appContext)
    val waveforms = WaveformProvider(appContext)
    val preview = PreviewMedia(session, bitmaps)

    var assets: List<AssetRefDto> by mutableStateOf(session.assets)
        private set
    var missing: Set<String> by mutableStateOf(emptySet())
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var status: String? by mutableStateOf(null)
    var category: LibraryCategory by mutableStateOf(LibraryCategory.IMAGES)
    var query: String by mutableStateOf("")
    var sort: LibrarySort by mutableStateOf(LibrarySort.NEWEST)

    private var seenVersion = session.assetsVersion
    private var importJob: Job? = null
    private val listener: (id.motionlab.engine.timeline.EditorState) -> Unit = {
        if (session.assetsVersion != seenVersion) {
            seenVersion = session.assetsVersion
            assets = session.assets
        }
    }

    init {
        session.addListener(listener)
        registerFonts()
        refreshMissing()
    }

    fun dispose() {
        session.removeListener(listener)
        importJob?.cancel()
        preview.close()
        bitmaps.clear()
    }

    /** The assets the UI shows: filtered by category and search text, then sorted. */
    fun visible(): List<AssetRefDto> {
        val q = query.trim().lowercase()
        val list = assets.filter { it.kind in category.kinds && (q.isEmpty() || (it.displayName ?: "").lowercase().contains(q)) }
        return when (sort) {
            LibrarySort.NEWEST -> list.sortedByDescending { it.addedAtMs }
            LibrarySort.NAME -> list.sortedBy { (it.displayName ?: "").lowercase() }
            LibrarySort.DURATION -> list.sortedByDescending { it.durationUs }
            LibrarySort.SIZE -> list.sortedByDescending { it.sizeBytes }
        }
    }

    fun countIn(c: LibraryCategory): Int = assets.count { it.kind in c.kinds }

    /** Imports picked [uris]; failures are reported per file and never stop the other files. */
    fun import(uris: List<Uri>, expected: Set<ResourceKind>?) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        busy = true
        status = "Importing ${uris.size} file(s)…"
        importJob = scope.launch {
            var jobDone = false
            try {
                val outcomes = withContext(Dispatchers.IO) {
                    uris.map { u -> importer.import(u, expected) { !isActiveSafe() } }
                }
                val ok = outcomes.filterIsInstance<ImportOutcome.Imported>()
                val bad = outcomes.filterIsInstance<ImportOutcome.Failed>()
                if (ok.isNotEmpty()) {
                    session.putAssets(ok.map { it.asset })
                    registerFonts()
                    ok.firstOrNull()?.asset?.kind?.let { k -> LibraryCategory.entries.firstOrNull { k in it.kinds }?.let { category = it } }
                }
                status = buildString {
                    if (ok.isNotEmpty()) append("Imported ${ok.size}. ")
                    if (bad.isNotEmpty()) append("Failed: " + bad.joinToString("; ") { "${it.name} — ${it.reason}" })
                    ok.mapNotNull { it.note }.distinct().forEach { append(" ").append(it).append('.') }
                }.trim().ifEmpty { null }
                jobDone = true
            } finally {
                busy = false
                if (!jobDone && status?.startsWith("Importing") == true) status = "Import cancelled"
            }
            refreshMissing()
        }
    }

    private fun isActiveSafe(): Boolean = importJob?.isActive != false

    /** Re-checks (off the main thread) which referenced files can no longer be opened. */
    fun refreshMissing() {
        val snapshot = session.assets
        scope.launch {
            val gone = withContext(Dispatchers.IO) { snapshot.filter { !MediaAccess.isAccessible(appContext, it) }.map { it.id }.toSet() }
            missing = gone
        }
    }

    /** Removes only the library reference (refused while clips use it). The user's original file is untouched. */
    fun remove(asset: AssetRefDto) {
        try {
            session.removeAsset(asset.id)
            bitmaps.drop(asset.id)
            waveforms.drop(asset.id)
            preview.forget(asset.id)
            // Copies made by this app (picker imports, fonts) belong to the app: remove them with the reference.
            if (asset.uri.startsWith("file:")) Uri.parse(asset.uri).path?.let { p ->
                if (p.startsWith(appContext.filesDir.path)) File(p).delete()
            }
            status = "Removed '${asset.displayName}' from the project"
        } catch (e: MlException) {
            status = e.message
        }
    }

    fun addToTimeline(asset: AssetRefDto, atUs: Long) {
        try {
            session.addAssetClip(asset.id, atUs)
            status = "Added '${asset.displayName}' to the timeline"
        } catch (e: MlException) {
            status = e.message
        }
    }

    /** Makes every imported font available to text layers by its family name. Invalid files are skipped with a log entry. */
    fun registerFonts() {
        for (a in session.assets) {
            if (a.kind != "FONT") continue
            val family = a.fontFamily ?: continue
            if (family in fonts.registeredNames()) continue
            val path = Uri.parse(a.uri).path ?: continue
            when (val r = fonts.registerFontFile(family, File(path))) {
                is id.motionlab.core.common.MlResult.Failure -> MlLog.w("MediaLibrary", "font ${a.displayName} not usable: ${r.error.message}")
                else -> Unit
            }
        }
    }
}
