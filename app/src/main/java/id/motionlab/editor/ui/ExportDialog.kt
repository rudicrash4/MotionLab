package id.motionlab.editor.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.Project
import id.motionlab.editor.export.ExportCaps
import id.motionlab.editor.export.ExportResult
import id.motionlab.editor.export.ExportSettings
import java.util.Locale

@Composable
fun ExportDialog(vm: EditorViewModel, project: Project, ui: ExportUi, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val running = ui.phase == ExportPhase.RUNNING
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    when (ui.phase) {
        ExportPhase.IDLE -> SettingsStage(vm, project, onClose)
        ExportPhase.RUNNING -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Mengekspor…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { ui.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(ui.progress * 100).toInt()}% · ${ui.message}\n\nJangan tutup aplikasi sampai selesai. Layar dijaga tetap menyala.",
                        fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelExport() }) { Text("Batalkan") } }
        )
        ExportPhase.DONE -> {
            val r = ui.result
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Ekspor selesai") },
                text = { if (r != null) ResultInfo(r) },
                confirmButton = {
                    Row {
                        if (r?.uri != null) {
                            TextButton(onClick = { openVideo(context, r) }) { Text("Buka") }
                            TextButton(onClick = { shareVideo(context, r) }) { Text("Bagikan") }
                        }
                        TextButton(onClick = { vm.dismissExport(); onClose() }) { Text("Tutup") }
                    }
                }
            )
        }
        ExportPhase.FAILED -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Ekspor gagal") },
            text = { Text(ui.message, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.dismissExport(); onClose() }) { Text("Tutup") } }
        )
        ExportPhase.CANCELLED -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Ekspor dibatalkan") },
            text = { Text(ui.message, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.dismissExport(); onClose() }) { Text("Tutup") } }
        )
    }
}

@Composable
private fun ResultInfo(r: ExportResult) {
    val sb = StringBuilder()
    sb.append("File: ").append(r.displayPath).append('\n')
    sb.append("${r.width}×${r.height} · ").append(String.format(Locale.US, "%.1f", r.durationMs / 1000f)).append(" dtk · H.264")
    sb.append(if (r.hasAudio) " + AAC" else " (tanpa audio)")
    if (r.warnings.isNotEmpty()) {
        sb.append("\n\nPeringatan:")
        for (w in r.warnings) sb.append("\n• ").append(w)
    }
    Text(sb.toString(), fontSize = 13.sp)
}

@Composable
private fun SettingsStage(vm: EditorViewModel, project: Project, onClose: () -> Unit) {
    val sides = remember(project.aspectW, project.aspectH) { ExportCaps.supportedShortSides(project) }
    var side by remember { mutableIntStateOf(if (sides.contains(1080)) 1080 else sides.firstOrNull() ?: 0) }
    val fpsList = remember(side, project.aspectW, project.aspectH) { if (side > 0) ExportCaps.supportedFps(project, side) else emptyList() }
    var fps by remember { mutableIntStateOf(if (fpsList.contains(30)) 30 else fpsList.firstOrNull() ?: 30) }
    var quality by remember { mutableIntStateOf(1) }
    val fpsOk = if (fpsList.contains(fps)) fps else (fpsList.firstOrNull() ?: 30)
    val (w, h) = if (side > 0) ExportCaps.sizeFor(project, side) else Pair(0, 0)
    val bitrate = if (side > 0) ExportCaps.bitrateFor(w, h, fpsOk, quality) else 0

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ekspor video MP4") },
        text = {
            Column {
                if (sides.isEmpty()) {
                    Text("Encoder H.264 perangkat ini tidak mendukung resolusi proyek. Ekspor tidak tersedia.", fontSize = 13.sp)
                } else {
                    Text("Resolusi (hanya yang didukung perangkat)", fontSize = 11.sp, color = Palette.TextMuted)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in sides) Chip(label(s), s == side) { side = s }
                    }
                    Text("Frame rate", fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in fpsList) Chip("$f fps", f == fpsOk) { fps = f }
                    }
                    Text("Kualitas (bitrate)", fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Rendah", quality == 0) { quality = 0 }
                        Chip("Sedang", quality == 1) { quality = 1 }
                        Chip("Tinggi", quality == 2) { quality = 2 }
                    }
                    Text(
                        "\n${w}×${h} · $fpsOk fps · ${String.format(Locale.US, "%.1f", bitrate / 1_000_000f)} Mbps · H.264 + AAC 192 kbps\n" +
                            "Durasi ${String.format(Locale.US, "%.1f", project.durationMs / 1000f)} dtk · disimpan ke Movies/MotionLab.",
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = sides.isNotEmpty() && project.durationMs > 0L,
                onClick = { vm.startExport(ExportSettings(side, fpsOk, quality)) }
            ) { Text(if (project.durationMs > 0L) "Mulai ekspor" else "Proyek kosong") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Batal") } }
    )
}

private fun label(shortSide: Int): String = when (shortSide) {
    720 -> "720p"
    1080 -> "1080p"
    1440 -> "1440p (2K)"
    2160 -> "2160p (4K)"
    else -> "${shortSide}p"
}

private fun openVideo(context: android.content.Context, r: ExportResult) {
    val uri = r.uri ?: return
    try {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    } catch (_: Exception) { }
}

private fun shareVideo(context: android.content.Context, r: ExportResult) {
    val uri = r.uri ?: return
    try {
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Bagikan video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (_: Exception) { }
}
