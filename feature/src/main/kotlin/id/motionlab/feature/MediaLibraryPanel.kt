package id.motionlab.feature

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.timeline.Timecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val FONT_MIME = arrayOf(
    "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream",
)

/**
 * Import + browse the project's media. Import uses the system Photo Picker (photos and videos) and the document picker
 * (audio, fonts): the user hands over exactly the files they choose, so the app needs NO storage permission and shows no
 * permission dialog. Each picker is opened only when the user taps its button.
 */
@Composable
fun MediaLibraryPanel(lib: MediaLibraryController, editor: EditorController, modifier: Modifier = Modifier) {
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        lib.import(uris, setOf(ResourceKind.IMAGE, ResourceKind.VIDEO))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        lib.import(uris, setOf(ResourceKind.AUDIO))
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        lib.import(uris, setOf(ResourceKind.FONT))
    }
    var previewing by remember { mutableStateOf<AssetRefDto?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val items = lib.visible()

    Column(modifier.fillMaxWidth().padding(8.dp).testTag("media-library"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                enabled = !lib.busy, modifier = Modifier.testTag("import-photos"),
            ) { Text("Photos/Videos") }
            OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, enabled = !lib.busy, modifier = Modifier.testTag("import-audio")) { Text("Audio") }
            OutlinedButton(onClick = { fontPicker.launch(FONT_MIME) }, enabled = !lib.busy, modifier = Modifier.testTag("import-fonts")) { Text("Fonts") }
        }
        Text(
            "You choose the files in the system picker; MotionLab can only see what you pick. Originals are never changed or deleted.",
            style = MaterialTheme.typography.labelSmall, color = MlColors.Muted,
        )
        if (lib.missing.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MlColors.PanelHigh).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${lib.missing.size} media file(s) can no longer be opened (moved, deleted or access lost). Their clips stay in the project; import the file again to replace it.",
                    Modifier.weight(1f), color = MlColors.Danger, style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { lib.refreshMissing() }, modifier = Modifier.testTag("media-retry")) { Text("Retry") }
            }
        }
        lib.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("media-status")) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryCategory.entries.forEach { c ->
                FilterChip(
                    selected = lib.category == c, onClick = { lib.category = c },
                    label = { Text("${c.label} ${lib.countIn(c)}") }, modifier = Modifier.testTag("media-cat-${c.name}"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = lib.query, onValueChange = { lib.query = it }, singleLine = true, label = { Text("Search") },
                modifier = Modifier.weight(1f).testTag("media-search"),
            )
            Box {
                OutlinedButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("media-sort")) { Text(lib.sort.label + " ▾") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, modifier = Modifier.testTag("media-sort-${s.name}"), onClick = { lib.sort = s; sortMenu = false })
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                if (lib.query.isBlank()) "No ${lib.category.label.lowercase()} yet. Use the buttons above to import." else "Nothing matches '${lib.query}'.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("media-empty"),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.id }) { asset ->
                AssetRow(lib, editor, asset, asset.id in lib.missing, onPreview = { previewing = asset })
            }
        }
    }
    previewing?.let { MediaPreviewDialog(lib, it) { previewing = null } }
}

@Composable
private fun AssetRow(lib: MediaLibraryController, editor: EditorController, asset: AssetRefDto, missing: Boolean, onPreview: () -> Unit) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, asset.id, missing) {
        value = if (missing || (asset.kind != "IMAGE" && asset.kind != "VIDEO")) null
        else withContext(Dispatchers.IO) { lib.bitmaps.load(asset, 0, 160) { !isActive } }?.asImageBitmap()
    }
    Row(
        Modifier.fillMaxWidth().background(MlColors.PanelHigh).padding(6.dp).testTag("media-row-${asset.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(56.dp).background(MlColors.Black), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t, asset.displayName, Modifier.size(56.dp), contentScale = ContentScale.Fit)
            else Text(when (asset.kind) { "AUDIO" -> "♪"; "FONT" -> "Aa"; else -> "…" })
        }
        Column(Modifier.weight(1f)) {
            Text(asset.displayName ?: asset.id, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(describe(asset), style = MaterialTheme.typography.labelSmall, color = MlColors.Muted, maxLines = 2)
            if (missing) Text("Missing — cannot be opened", color = MlColors.Danger, style = MaterialTheme.typography.labelSmall)
        }
        Column {
            Row {
                if (asset.kind != "FONT") {
                    TextButton(onClick = onPreview, enabled = !missing, modifier = Modifier.testTag("media-preview-${asset.id}")) { Text("Preview") }
                    TextButton(
                        onClick = { editor.run { lib.addToTimeline(asset, editor.playheadUs) } },
                        enabled = !missing, modifier = Modifier.testTag("media-add-${asset.id}"),
                    ) { Text("+ Timeline", color = MlColors.Accent) }
                }
                TextButton(onClick = { lib.remove(asset) }, modifier = Modifier.testTag("media-remove-${asset.id}")) { Text("Remove") }
            }
        }
    }
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
