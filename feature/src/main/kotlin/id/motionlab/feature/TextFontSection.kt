package id.motionlab.feature

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.motionlab.core.project.AssetRefDto

/** MIME types document providers use for TTF / OTF; the importer then validates the real file structure. */
internal val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream",
)

/**
 * Font family selection for a text layer: the current family, a family menu, "Import Font" (TTF / OTF, validated before
 * anything is registered), the list of imported fonts with a live preview in each font, and removal.
 *
 * Imported fonts are FONT assets of the project: they are saved with it and re-registered on load, so they are available
 * after closing and reopening. [apply] sets (or with null clears) the family of the selected text clip.
 */
@Composable
fun TextFontSection(lib: MediaLibraryController, current: String?, previewText: String, apply: (String?) -> Unit) {
    val fonts = lib.assets.filter { it.kind == "FONT" && it.fontFamily != null }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // The freshly imported font is applied straight away: import -> use.
        lib.import(uris, setOf(id.motionlab.core.resource.ResourceKind.FONT), null) { families -> families.firstOrNull()?.let(apply) }
    }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("font-section"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Font family", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MenuButton(current ?: "System default", "font-button") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("System default") }, modifier = Modifier.testTag("font-default"), onClick = { menu = false; apply(null) })
                    fonts.mapNotNull { it.fontFamily }.distinct().forEach { f ->
                        DropdownMenuItem(text = { Text(f, fontFamily = lib.composeFamily(f)) }, modifier = Modifier.testTag("font-$f"), onClick = { menu = false; apply(f) })
                    }
                }
            }
            Button(onClick = { picker.launch(FONT_MIME_TYPES) }, enabled = !lib.busy, modifier = Modifier.testTag("import-font")) {
                MlIcon(MlIcons.AddMedia, null, size = 18.dp, tint = Color.Black)
                Text("  Import Font", maxLines = 1)
            }
        }
        lib.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("font-status")) }
        Text("Imported fonts (TTF, OTF)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        if (fonts.isEmpty()) {
            Text("No imported fonts yet. Import a .ttf or .otf file; it is checked before it is added.", style = MaterialTheme.typography.bodySmall, color = MlColors.Muted, modifier = Modifier.testTag("font-empty"))
        }
        fonts.forEach { f -> FontRow(lib, f, previewText, selected = f.fontFamily == current, onUse = { apply(f.fontFamily) }) }
    }
}

@Composable
private fun FontRow(lib: MediaLibraryController, font: AssetRefDto, previewText: String, selected: Boolean, onUse: () -> Unit) {
    val family = font.fontFamily ?: return
    Row(
        Modifier.fillMaxWidth().background(if (selected) MlColors.AccentDim.copy(alpha = 0.35f) else MlColors.PanelHigh).clickable(onClick = onUse).padding(6.dp).testTag("font-row-$family"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(family, style = MaterialTheme.typography.labelMedium, color = MlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(previewText.ifBlank { "Aa Bb Cc 123" }.take(40), fontFamily = lib.composeFamily(family), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("font-preview-$family"))
        }
        MlIconButton(MlIcons.Delete, "Remove font $family from project", "font-remove-$family") { lib.remove(font) }
    }
}
