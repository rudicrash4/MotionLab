package id.motionlab.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.ProjectInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Daftar proyek: buka, duplikat, hapus (dengan konfirmasi), proyek baru. */
@Composable
fun ProjectsDialog(vm: EditorViewModel, currentId: String, onClose: () -> Unit) {
    val list by vm.projects.collectAsState()
    var confirmDelete by remember { mutableStateOf<ProjectInfo?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Proyek") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("Belum ada proyek tersimpan. Proyek aktif disimpan otomatis setelah diberi klip.", fontSize = 12.sp)
                for (p in list) {
                    val active = p.id == currentId
                    Column(
                        Modifier.fillMaxWidth().clickable { if (!active) { vm.openProject(p.id); onClose() } }.padding(vertical = 6.dp)
                    ) {
                        Text((if (active) "● " else "") + p.name, fontSize = 14.sp, color = if (active) Palette.Accent else Palette.TextMain)
                        Text(
                            "${p.clipCount} klip · ${String.format(Locale.US, "%.1f", p.durationMs / 1000f)} dtk · " +
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.updatedAt)),
                            fontSize = 11.sp, color = Palette.TextMuted
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.duplicateProject(p.id) }) { Text("Duplikat", fontSize = 12.sp) }
                            TextButton(onClick = { confirmDelete = p }) { Text("Hapus", fontSize = 12.sp, color = Palette.Danger) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.newProject(); onClose() }) { Text("Proyek baru") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Tutup") } }
    )

    val target = confirmDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus proyek?") },
            text = { Text("\"${target.name}\" akan dihapus permanen dari aplikasi. File media aslinya tidak ikut terhapus.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProject(target.id); confirmDelete = null }) { Text("Hapus", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Batal") } }
        )
    }
}
