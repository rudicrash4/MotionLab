package id.motionlab.editor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ControlPointDuplicate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.TimelineController
import id.motionlab.editor.ui.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerActionsSheet(
    project: Project,
    selectedClipId: String?,
    controller: TimelineController,
    playheadMs: Long,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasSelection = selectedClipId != null
    val selectedTrack = project.tracks.firstOrNull { it.clips.any { c -> c.id == selectedClipId } }
    val isLocked = selectedTrack?.locked == true
    val canPaste = controller.canPasteClip()
    val canPasteKf = controller.canPasteKeyframes()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.Panel,
        contentColor = Palette.TextMain
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Tindakan Layer",
                fontSize = 16.sp,
                color = Palette.TextMain,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Select all / multi
            ActionItem(
                icon = Icons.Filled.SelectAll,
                label = "Pilih Semua Layer",
                enabled = project.tracks.isNotEmpty(),
                onClick = {
                    if (project.tracks.isNotEmpty()) {
                        controller.select(project.tracks.firstOrNull()?.clips?.firstOrNull()?.id)
                    }
                    onDismiss()
                }
            )

            ActionItem(
                icon = Icons.Filled.Diamond,
                label = "Tampilkan Semua Keyframe",
                enabled = hasSelection,
                onClick = {
                    onDismiss()
                }
            )

            HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 6.dp))

            // Copy & Paste layer
            ActionItem(
                icon = Icons.Filled.ContentCopy,
                label = "Salin Layer",
                enabled = hasSelection,
                onClick = {
                    selectedClipId?.let { controller.copyClip(it) }
                    onDismiss()
                }
            )

            ActionItem(
                icon = Icons.Filled.ContentPaste,
                label = "Tempel Layer",
                enabled = canPaste,
                onClick = {
                    controller.pasteClip(playheadMs)
                    onDismiss()
                }
            )

            ActionItem(
                icon = Icons.Filled.ControlPointDuplicate,
                label = "Duplikat Layer",
                enabled = hasSelection,
                onClick = {
                    selectedClipId?.let { controller.duplicateClip(it) }
                    onDismiss()
                }
            )

            HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 6.dp))

            // Keyframe clipboard
            ActionItem(
                icon = Icons.Filled.Diamond,
                label = "Salin Keyframe",
                enabled = hasSelection,
                onClick = {
                    selectedClipId?.let { controller.copyKeyframes(it) }
                    onDismiss()
                }
            )

            ActionItem(
                icon = Icons.Filled.ContentPaste,
                label = "Tempel Keyframe",
                enabled = hasSelection && canPasteKf,
                onClick = {
                    selectedClipId?.let { controller.pasteKeyframes(it) }
                    onDismiss()
                }
            )

            HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 6.dp))

            // Lock / Unlock
            ActionItem(
                icon = if (isLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                label = if (isLocked) "Buka Kunci Layer" else "Kunci Layer",
                enabled = selectedTrack != null,
                onClick = {
                    selectedTrack?.let { controller.toggleTrackLocked(it.id) }
                    onDismiss()
                }
            )

            // Grouping placeholder / feature
            ActionItem(
                icon = Icons.Filled.Folder,
                label = "Kelompokkan Layer (Group)",
                enabled = hasSelection,
                onClick = {
                    onDismiss()
                }
            )

            // Delete
            ActionItem(
                icon = Icons.Filled.Delete,
                label = "Hapus Layer Terpilih",
                enabled = hasSelection,
                tint = if (hasSelection) Palette.Danger else Palette.Panel3,
                onClick = {
                    selectedClipId?.let { controller.deleteClip(it) }
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = Palette.TextMain,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else Palette.TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (enabled) tint else Palette.TextMuted.copy(alpha = 0.4f)
        )
    }
}
