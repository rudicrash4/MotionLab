package id.motionlab.feature

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.MediaAccessState
import id.motionlab.core.resource.MediaPermissionPolicy
import id.motionlab.core.resource.MediaType
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.media.DeviceMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Pickers advertise broad MIME lists; the importer then inspects the real content, so a wrong label never decides anything. */
private val IMAGE_MIME = arrayOf("image/*")
private val VIDEO_MIME = arrayOf("video/*", "application/x-matroska", "application/octet-stream")
private val AUDIO_MIME = arrayOf("audio/*", "application/ogg", "application/x-flac", "application/octet-stream")

private fun LibraryCategory.icon() = when (this) {
    LibraryCategory.IMAGES -> MlIcons.Image
    LibraryCategory.VIDEOS -> MlIcons.Video
    LibraryCategory.AUDIO -> MlIcons.Audio
    LibraryCategory.FONTS -> MlIcons.Font
}

private fun LibraryCategory.mediaType(): MediaType? = when (this) {
    LibraryCategory.IMAGES -> MediaType.IMAGES
    LibraryCategory.VIDEOS -> MediaType.VIDEO
    LibraryCategory.AUDIO -> MediaType.AUDIO
    LibraryCategory.FONTS -> null
}

/**
 * Add Media. Flow: choose Image / Video / Audio (chips) -> pick a source (this project, or this device) -> tap an item ->
 * "Add to Timeline" (or the + on the row). The clip goes to the playhead on a suitable track (created when needed) and is
 * selected; the timeline scrolls to it.
 *
 * Sources: files already imported into the project, the device gallery (MediaStore; needs the media permission, requested
 * only when the user taps "Allow access", after an explanation), and the system Photo Picker / document picker, which work
 * WITHOUT any permission and stay available when permission is denied. Fonts only use the document picker.
 *
 * [accessOverride] lets tests show every permission state deterministically; production passes null.
 */
@Composable
fun MediaLibraryPanel(lib: MediaLibraryController, editor: EditorController, modifier: Modifier = Modifier, accessOverride: MediaAccessState? = null) {
    // Fonts are imported from the text configuration, never from here.
    val category = lib.category.takeIf { it != LibraryCategory.FONTS } ?: LibraryCategory.IMAGES
    val mediaType = category.mediaType()
    val access = rememberMediaAccess(mediaType)
    val accessState = accessOverride ?: access.state

    // Direct media layers: whatever is picked becomes a clip at the playhead (track created on demand), selected and revealed.
    val target = remember(lib, editor) { InsertTarget({ editor.playheadUs }, { editor.selectedTrackId() }, editor::revealInsertion) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        lib.import(uris, setOf(ResourceKind.IMAGE, ResourceKind.VIDEO), target)
    }
    // Document picker: also lists formats the Photo Picker hides (MKV, AVI, FLAC, OGG ...). The importer inspects the content.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        lib.import(uris, setOf(category.expectedKind()), target)
    }
    val launchPicker: () -> Unit = {
        when (category) {
            LibraryCategory.IMAGES -> photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            LibraryCategory.VIDEOS -> photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            LibraryCategory.AUDIO -> filePicker.launch(AUDIO_MIME)
            LibraryCategory.FONTS -> Unit
        }
    }
    val launchFiles: () -> Unit = {
        filePicker.launch(when (category) { LibraryCategory.IMAGES -> IMAGE_MIME; LibraryCategory.VIDEOS -> VIDEO_MIME; else -> AUDIO_MIME })
    }
    var previewing by remember { mutableStateOf<AssetRefDto?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val items = lib.visible()
    val onDevice = lib.source == LibrarySource.DEVICE

    val addAsset: (AssetRefDto) -> Unit = { a -> lib.addToTimeline(a, editor.playheadUs, editor.selectedTrackId())?.let(editor::revealInsertion) }
    val addDevice: (DeviceMedia) -> Unit = { d ->
        lib.addDeviceToTimeline(d, editor.playheadUs, editor.selectedTrackId()) { ins -> ins?.let(editor::revealInsertion) }
    }
    val listState = rememberLazyListState()
    val deviceReady = onDevice && (accessState == MediaAccessState.FULL || accessState == MediaAccessState.PARTIAL)
    if (deviceReady) {
        LaunchedEffect(lib.category, accessState) { lib.loadDevice(reset = true) }
        // Lazy paging: when the last loaded item scrolls into view, the next page of 60 is queried.
        val last by remember { androidx.compose.runtime.derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
        LaunchedEffect(last, lib.deviceItems.size) { if (last >= lib.deviceItems.size - 6) lib.loadDevice(reset = false) }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("media-library"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // One vertical scroller for the whole panel: controls, status and the list scroll together, nothing gets clipped
        // on a small screen, and only the "Add to Timeline" bar below stays pinned.
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true).testTag("media-scroll"), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "cats") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LibraryCategory.entries.filter { it != LibraryCategory.FONTS }.forEach { c ->
                        FilterChip(
                            selected = category == c, onClick = { lib.selectCategory(c) },
                            leadingIcon = { MlIcon(c.icon(), null, size = 16.dp) },
                            label = { Text("${c.label} ${lib.countIn(c)}", maxLines = 1) }, modifier = Modifier.testTag("media-cat-${c.name}"),
                        )
                    }
                }
            }
            item(key = "src") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = !onDevice, onClick = { lib.source = LibrarySource.PROJECT }, label = { Text("In project") }, modifier = Modifier.testTag("media-src-PROJECT"))
                    FilterChip(selected = onDevice, onClick = { lib.source = LibrarySource.DEVICE }, label = { Text("On this device") }, modifier = Modifier.testTag("media-src-DEVICE"))
                }
            }
            if (lib.missing.isNotEmpty()) item(key = "missing") {
                Row(Modifier.fillMaxWidth().background(MlColors.PanelHigh).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MlIcon(MlIcons.Warning, "Warning", tint = MlColors.Danger)
                    Text(
                        "${lib.missing.size} media file(s) can no longer be opened (moved, deleted or access lost). Their clips stay in the project; import the file again to replace it.",
                        Modifier.weight(1f).padding(horizontal = 6.dp), color = MlColors.Danger, style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { lib.refreshMissing(); lib.preview.failedAssets.forEach(lib.preview::retry) }, modifier = Modifier.testTag("media-retry")) { Text("Retry") }
                }
            }
            lib.status?.let { st -> item(key = "status") { Text(st, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("media-status")) } }

            if (!onDevice) {
                item(key = "import") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = launchPicker, enabled = !lib.busy, modifier = Modifier.testTag(if (category == LibraryCategory.AUDIO) "import-audio" else "import-photos")) {
                                MlIcon(MlIcons.AddMedia, null, size = 18.dp, tint = androidx.compose.ui.graphics.Color.Black)
                                Text("  Import ${category.label.lowercase()}", maxLines = 1)
                            }
                            OutlinedButton(onClick = launchFiles, enabled = !lib.busy, modifier = Modifier.testTag("import-files")) {
                                MlIcon(MlIcons.Folder, null, size = 18.dp)
                                Text("  Browse files", maxLines = 1)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = lib.query, onValueChange = { lib.query = it }, singleLine = true, placeholder = { Text("Search") },
                                leadingIcon = { MlIcon(MlIcons.Search, null) },
                                modifier = Modifier.weight(1f).testTag("media-search"),
                            )
                            Box {
                                MlIconButton(MlIcons.Handle, "Sort: ${lib.sort.label}", "media-sort") { sortMenu = true }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    LibrarySort.entries.forEach { s ->
                                        DropdownMenuItem(text = { Text(s.label) }, modifier = Modifier.testTag("media-sort-${s.name}"), onClick = { lib.sort = s; sortMenu = false })
                                    }
                                }
                            }
                        }
                    }
                }
                if (items.isEmpty()) item(key = "empty") {
                    Text(
                        if (lib.query.isBlank()) "No ${category.label.lowercase()} in this project yet. Import some: it is added to the timeline straight away." else "Nothing matches '${lib.query}'.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("media-empty"),
                    )
                }
                items(items, key = { it.id }) { asset ->
                    AssetRow(lib, asset, asset.id in lib.missing, selected = lib.selectedAssetId == asset.id, onSelect = { lib.selectedAssetId = asset.id }, onAdd = { addAsset(asset) }, onPreview = { previewing = asset })
                }
            } else {
                deviceItems(lib, mediaType!!, accessState, access, launchPicker, addDevice)
            }
        }

        // Add to Timeline: pinned, always reachable.
        val selAsset = lib.assets.firstOrNull { it.id == lib.selectedAssetId && it.kind in category.kinds }
        val selDevice = lib.selectedDevice?.takeIf { onDevice && it.type == mediaType }
        val name = if (onDevice) selDevice?.name else selAsset?.displayName
        Row(Modifier.fillMaxWidth().background(MlColors.PanelHigh).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name ?: "Tap an item to select it", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (name == null) MlColors.Muted else MlColors.OnSurface)
            Button(
                onClick = { if (onDevice) selDevice?.let(addDevice) else selAsset?.let(addAsset) },
                enabled = (if (onDevice) selDevice != null else selAsset != null && selAsset.id !in lib.missing) && !lib.busy,
                modifier = Modifier.testTag("media-add-selected"),
            ) {
                MlIcon(MlIcons.Add, null, size = 18.dp, tint = androidx.compose.ui.graphics.Color.Black)
                Text(" Add to Timeline", maxLines = 1)
            }
        }
    }
    previewing?.let { MediaPreviewDialog(lib, it) { previewing = null } }
}

private fun LibraryCategory.expectedKind(): ResourceKind = when (this) {
    LibraryCategory.IMAGES -> ResourceKind.IMAGE
    LibraryCategory.VIDEOS -> ResourceKind.VIDEO
    LibraryCategory.AUDIO -> ResourceKind.AUDIO
    LibraryCategory.FONTS -> ResourceKind.FONT
}

/** Rows of the device gallery, emitted into the panel's single scroller. */
private fun androidx.compose.foundation.lazy.LazyListScope.deviceItems(
    lib: MediaLibraryController,
    type: MediaType,
    state: MediaAccessState,
    access: MediaAccessUi,
    launchPicker: () -> Unit,
    addDevice: (DeviceMedia) -> Unit,
) {
    when (state) {
        MediaAccessState.NOT_ASKED, MediaAccessState.DENIED -> item(key = "perm") {
            Column(Modifier.testTag("device-browser"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(MediaPermissionPolicy.rationale(type), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("perm-rationale"))
                if (state == MediaAccessState.DENIED) Text("Permission was not granted.", color = MlColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("perm-denied"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = access.request, modifier = Modifier.testTag("perm-allow")) { Text(if (state == MediaAccessState.DENIED) "Try again" else "Allow access") }
                    OutlinedButton(onClick = launchPicker, modifier = Modifier.testTag("perm-fallback-picker")) { Text("Use system picker") }
                }
            }
        }
        MediaAccessState.PERMANENTLY_DENIED -> item(key = "perm") {
            Column(Modifier.testTag("device-browser"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(MediaPermissionPolicy.settingsExplanation(type), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("perm-settings-text"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = access.openSettings, modifier = Modifier.testTag("perm-settings")) { Text("Open Settings") }
                    OutlinedButton(onClick = launchPicker, modifier = Modifier.testTag("perm-fallback-picker")) { Text("Use system picker") }
                }
            }
        }
        MediaAccessState.FULL, MediaAccessState.PARTIAL -> {
            if (state == MediaAccessState.PARTIAL) item(key = "partial") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(MediaPermissionPolicy.partialExplanation(), style = MaterialTheme.typography.bodySmall, color = MlColors.Muted, modifier = Modifier.testTag("perm-partial"))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = access.request, modifier = Modifier.testTag("perm-allow")) { Text("Allow all") }
                        OutlinedButton(onClick = launchPicker, modifier = Modifier.testTag("perm-fallback-picker")) { Text("Use system picker") }
                    }
                }
            }
            if (lib.deviceItems.isEmpty() && !lib.deviceLoading) item(key = "dev-empty") {
                Text("No ${lib.category.label.lowercase()} found on this device.", modifier = Modifier.testTag("device-empty"))
            }
            items(lib.deviceItems, key = { it.uri }) { item ->
                DeviceRow(lib, item, selected = lib.selectedDevice?.uri == item.uri, onSelect = { lib.selectedDevice = item }, onAdd = { addDevice(item) })
            }
            if (lib.deviceLoading) item(key = "dev-loading") { Text("Loading…", Modifier.padding(6.dp), color = MlColors.Muted) }
        }
    }
}

@Composable
private fun Thumb(bitmap: androidx.compose.ui.graphics.ImageBitmap?, fallback: androidx.compose.ui.graphics.vector.ImageVector, description: String?) {
    Box(Modifier.size(52.dp).background(MlColors.Black), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, description, Modifier.size(52.dp), contentScale = ContentScale.Crop)
        else MlIcon(fallback, null, tint = MlColors.Muted, size = 24.dp)
    }
}

private fun kindIcon(kind: String) = when (kind) { "VIDEO" -> MlIcons.Video; "AUDIO" -> MlIcons.Audio; "FONT" -> MlIcons.Font; else -> MlIcons.Image }

@Composable
private fun DeviceRow(lib: MediaLibraryController, item: DeviceMedia, selected: Boolean, onSelect: () -> Unit, onAdd: () -> Unit) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.uri) { value = lib.deviceThumbnail(item)?.asImageBitmap() }
    val key = item.uri.substringAfterLast('/')
    val inProject = lib.assetFor(item) != null
    Row(
        Modifier.fillMaxWidth().background(if (selected) MlColors.AccentDim.copy(alpha = 0.35f) else MlColors.PanelHigh).clickable(onClick = onSelect).padding(4.dp).testTag("device-row-$key"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Thumb(thumb, when (item.type) { MediaType.VIDEO -> MlIcons.Video; MediaType.AUDIO -> MlIcons.Audio; else -> MlIcons.Image }, item.name)
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(describeDevice(item) + if (inProject) " · in project" else "", style = MaterialTheme.typography.labelSmall, color = MlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MlIconButton(MlIcons.Add, "Add ${item.name} to timeline", "device-add-$key", tint = MlColors.Accent, onClick = onAdd)
    }
}

@Composable
private fun AssetRow(lib: MediaLibraryController, asset: AssetRefDto, missing: Boolean, selected: Boolean, onSelect: () -> Unit, onAdd: () -> Unit, onPreview: () -> Unit) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, asset.id, missing) {
        value = if (missing || (asset.kind != "IMAGE" && asset.kind != "VIDEO")) null
        else withContext(Dispatchers.IO) { lib.bitmaps.load(asset, 0, 160) { !isActive } }?.asImageBitmap()
    }
    Row(
        Modifier.fillMaxWidth().background(if (selected) MlColors.AccentDim.copy(alpha = 0.35f) else MlColors.PanelHigh).clickable(onClick = onSelect).padding(4.dp).testTag("media-row-${asset.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Thumb(thumb, kindIcon(asset.kind), asset.displayName)
        Column(Modifier.weight(1f)) {
            Text(asset.displayName ?: asset.id, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(describe(asset), style = MaterialTheme.typography.labelSmall, color = MlColors.Muted, maxLines = 2)
            if (missing) Text("Missing: cannot be opened", color = MlColors.Danger, style = MaterialTheme.typography.labelSmall)
        }
        if (asset.kind != "FONT") {
            MlIconButton(MlIcons.Play, "Preview ${asset.displayName}", "media-preview-${asset.id}", enabled = !missing, onClick = onPreview)
            MlIconButton(MlIcons.Add, "Add ${asset.displayName} to timeline", "media-add-${asset.id}", enabled = !missing, tint = MlColors.Accent, onClick = onAdd)
        }
        MlIconButton(MlIcons.Delete, "Remove ${asset.displayName} from project", "media-remove-${asset.id}") { lib.remove(asset) }
    }
}

internal fun describeDevice(d: DeviceMedia): String {
    val parts = ArrayList<String>()
    if (d.type != MediaType.AUDIO && d.width > 0) parts += "${d.width}×${d.height}"
    if (d.type != MediaType.IMAGES && d.durationMs > 0) parts += formatDuration(d.durationMs * 1000L)
    if (d.sizeBytes > 0) parts += formatSize(d.sizeBytes)
    return parts.joinToString(" · ")
}

internal fun describe(a: AssetRefDto): String {
    val parts = ArrayList<String>()
    when (a.kind) {
        "IMAGE" -> if (a.width > 0) parts += "${a.width}×${a.height}"
        "VIDEO" -> {
            if (a.width > 0) parts += "${a.width}×${a.height}"
            parts += formatDuration(a.durationUs)
            if (!a.hasAudio) parts += "no audio"
        }
        "AUDIO" -> {
            parts += formatDuration(a.durationUs)
            if (a.sampleRate > 0) parts += "%.1f kHz".format(java.util.Locale.US, a.sampleRate / 1000.0)
            if (a.channels > 0) parts += if (a.channels == 1) "mono" else if (a.channels == 2) "stereo" else "${a.channels} ch"
        }
        "FONT" -> parts += a.fontFamily ?: "font"
    }
    if (a.sizeBytes > 0) parts += formatSize(a.sizeBytes)
    return parts.joinToString(" · ")
}

internal fun formatDuration(us: Long): String {
    val total = (us / 1_000_000L).coerceAtLeast(0)
    return "%d:%02d".format(java.util.Locale.US, total / 60, total % 60)
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%d KB".format(java.util.Locale.US, bytes / 1024)
    else -> "$bytes B"
}
