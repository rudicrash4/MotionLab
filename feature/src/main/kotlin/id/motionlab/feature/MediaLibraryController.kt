package id.motionlab.feature

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.BoundedCache
import id.motionlab.core.resource.MediaType
import id.motionlab.core.resource.MediaUriPolicy
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.media.AssetImporter
import id.motionlab.engine.media.BitmapProvider
import id.motionlab.engine.media.ImportOutcome
import id.motionlab.engine.media.DeviceMedia
import id.motionlab.engine.media.MediaAccess
import id.motionlab.engine.media.MediaStoreBrowser
import id.motionlab.engine.media.WaveformProvider
import id.motionlab.engine.text.FontManager
import id.motionlab.engine.timeline.AssetInsertion
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

/** Where the media list comes from: assets already in the project, or the device gallery (needs permission or the pickers). */
enum class LibrarySource { PROJECT, DEVICE }

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

    // ---- Add Media flow state ----
    var source: LibrarySource by mutableStateOf(LibrarySource.PROJECT)
    /** The project asset tapped in the list (the "Add to Timeline" button acts on it). */
    var selectedAssetId: String? by mutableStateOf(null)
    /** The device item tapped in the browser. */
    var selectedDevice: DeviceMedia? by mutableStateOf(null)
    /** Incremented by the "Add media" button; the panel container opens the Media tab when it changes. */
    var openRequest: Int by mutableStateOf(0)
        private set
    /** What the last insertion did (also kept for tests); null before the first one. */
    var lastInsertion: AssetInsertion? by mutableStateOf(null)
        private set

    private val browser = MediaStoreBrowser(appContext)
    var deviceItems: List<DeviceMedia> by mutableStateOf(emptyList())
        private set
    var deviceLoading: Boolean by mutableStateOf(false)
        private set
    var deviceEnd: Boolean by mutableStateOf(false)
        private set
    private var deviceType: MediaType? = null
    private var deviceJob: Job? = null
    private var loadGen = 0
    private val deviceThumbs = BoundedCache<String, android.graphics.Bitmap>(6L * 1024 * 1024, { it.byteCount.toLong() })

    fun requestOpen(kind: LibraryCategory? = null) {
        if (kind != null) { category = kind; selectedAssetId = null; selectedDevice = null }
        openRequest++
    }

    /** Switches category; clears the selection so "Add to Timeline" never adds something from another category. */
    fun selectCategory(c: LibraryCategory) {
        if (c == category) return
        category = c
        selectedAssetId = null
        selectedDevice = null
        deviceItems = emptyList(); deviceEnd = false; deviceType = null
    }

    private fun typeOf(c: LibraryCategory): MediaType? = when (c) {
        LibraryCategory.IMAGES -> MediaType.IMAGES
        LibraryCategory.VIDEOS -> MediaType.VIDEO
        LibraryCategory.AUDIO -> MediaType.AUDIO
        LibraryCategory.FONTS -> null
    }

    /** Loads the next page of the device gallery for the current category ([reset] starts over). Runs on IO; one request at a time. */
    fun loadDevice(reset: Boolean) {
        val type = typeOf(category) ?: return
        if (reset || deviceType != type) {
            deviceJob?.cancel(); loadGen++
            deviceItems = emptyList(); deviceEnd = false; deviceType = type; deviceLoading = false
        }
        if (deviceLoading || deviceEnd) return
        val gen = loadGen
        val offset = deviceItems.size
        deviceLoading = true
        deviceJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { browser.page(type, offset, DEVICE_PAGE) }
                if (gen == loadGen) {
                    // Never list the same file twice (paging overlap) ...
                    val known = deviceItems.mapTo(HashSet()) { it.uri }
                    deviceItems = deviceItems + page.filter { it.uri !in known }
                    if (page.size < DEVICE_PAGE) deviceEnd = true
                }
            } finally {
                if (gen == loadGen) deviceLoading = false
            }
        }
    }

    /** ... and a device file already imported into the project is shown as "in project" and reuses that asset. */
    fun assetFor(item: DeviceMedia): AssetRefDto? = assets.firstOrNull {
        MediaUriPolicy.sameMedia(it.uri, it.displayName, it.sizeBytes, item.uri, item.name, item.sizeBytes)
    }

    /** Small thumbnail from the system, cached (6 MB) and produced off the main thread. */
    suspend fun deviceThumbnail(item: DeviceMedia): android.graphics.Bitmap? {
        deviceThumbs[item.uri]?.let { return it }
        val bmp = withContext(Dispatchers.IO) { browser.thumbnail(item, 192) } ?: return null
        deviceThumbs.put(item.uri, bmp)
        return bmp
    }

    /**
     * Adds a device file at [atUs]: reuses the project asset when the file was imported before, otherwise references it
     * (gallery items are not copied) and then inserts it. [onDone] receives the result on the main thread.
     */
    fun addDeviceToTimeline(item: DeviceMedia, atUs: Long, preferredTrackId: String?, onDone: (AssetInsertion?) -> Unit) {
        assetFor(item)?.let { onDone(addToTimeline(it, atUs, preferredTrackId)); return }
        busy = true
        status = "Adding '${item.name}'…"
        scope.launch {
            try {
                val expected = when (item.type) { MediaType.IMAGES -> ResourceKind.IMAGE; MediaType.VIDEO -> ResourceKind.VIDEO; MediaType.AUDIO -> ResourceKind.AUDIO }
                val outcome = withContext(Dispatchers.IO) { importer.import(Uri.parse(item.uri), setOf(expected)) }
                when (outcome) {
                    is ImportOutcome.Imported -> {
                        session.putAssets(listOf(outcome.asset))
                        onDone(addToTimeline(outcome.asset, atUs, preferredTrackId))
                    }
                    is ImportOutcome.Failed -> { status = "Could not add '${outcome.name}': ${outcome.reason}"; onDone(null) }
                }
            } finally {
                busy = false
            }
        }
    }

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
        deviceJob?.cancel()
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
                    ok.firstOrNull()?.asset?.let { selectedAssetId = it.id; source = LibrarySource.PROJECT }
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

    /**
     * Inserts [asset] at [atUs] (the playhead) and selects the new clip. Returns what happened, or null on failure
     * (the reason is in [status]). If [preferredTrackId] is locked the clip goes to another track and [status] says so.
     */
    fun addToTimeline(asset: AssetRefDto, atUs: Long, preferredTrackId: String? = null): AssetInsertion? = try {
        val ins = session.insertAsset(asset.id, atUs, preferredTrackId)
        lastInsertion = ins
        selectedAssetId = asset.id
        status = "Added '${asset.displayName}' to the timeline" + (ins.note?.let { ". $it" } ?: "")
        ins
    } catch (e: MlException) {
        status = e.message
        null
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

private const val DEVICE_PAGE = 60
