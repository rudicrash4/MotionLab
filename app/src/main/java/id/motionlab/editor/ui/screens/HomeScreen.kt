package id.motionlab.editor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.ProjectInfo
import id.motionlab.editor.ui.EditorViewModel
import id.motionlab.editor.ui.Palette
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: EditorViewModel,
    onOpenEditor: () -> Unit
) {
    val projects by vm.projects.collectAsState()
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectInfo?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshProjects()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.AppBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---- Top Header Branding ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Palette.AccentGreen, Palette.Accent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = "MotionLab",
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "MOTIONLAB",
                    color = Palette.TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Video & Motion Graphics Editor",
                    color = Palette.TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        // ---- "New Project" Hero Banner ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { showNewProjectDialog = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Palette.Panel)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Palette.Border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Palette.AccentGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Proyek Baru",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Buat Proyek Baru",
                        color = Palette.TextMain,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pilih rasio aspek, resolusi, dan mulai animasi",
                        color = Palette.TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ---- Recent Projects Section ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Proyek Terbaru",
                color = Palette.TextMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${projects.size} proyek",
                color = Palette.TextMuted,
                fontSize = 12.sp
            )
        }

        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = Palette.Panel3,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Belum Ada Proyek",
                        color = Palette.TextMain,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tekan \"Buat Proyek Baru\" untuk memulai kreasi",
                        color = Palette.TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects, key = { it.id }) { item ->
                    ProjectCard(
                        info = item,
                        onOpen = {
                            vm.openProject(item.id)
                            onOpenEditor()
                        },
                        onDuplicate = { vm.duplicateProject(item.id) },
                        onRename = { renameTarget = item },
                        onDelete = { deleteTarget = item }
                    )
                }
            }
        }
    }

    // ---- Dialog Buat Proyek Baru ----
    if (showNewProjectDialog) {
        NewProjectDialog(
            initialName = "Proyek ${projects.size + 1}",
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, w, h, shortSide, fps, bgColor ->
                vm.createCustomProject(name, w, h, shortSide, fps, bgColor)
                showNewProjectDialog = false
                onOpenEditor()
            }
        )
    }

    // ---- Dialog Ubah Nama ----
    val ren = renameTarget
    if (ren != null) {
        var newName by remember { mutableStateOf(ren.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Ubah Nama Proyek") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        vm.renameProject(ren.id, newName.trim())
                    }
                    renameTarget = null
                }) {
                    Text("Simpan", color = Palette.AccentGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Batal")
                }
            }
        )
    }

    // ---- Dialog Konfirmasi Hapus ----
    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus Proyek?") },
            text = {
                Text("\"${del.name}\" akan dihapus permanen dari aplikasi MotionLab. File video atau gambar asli di penyimpanan perangkat tidak akan terhapus.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(del.id)
                    deleteTarget = null
                }) {
                    Text("Hapus", color = Palette.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    info: ProjectInfo,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Panel)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preview aspect box thumbnail
            val aspW = info.aspectW.coerceAtLeast(1)
            val aspH = info.aspectH.coerceAtLeast(1)
            val boxW = if (aspW >= aspH) 54.dp else (54f * aspW / aspH).dp.coerceAtLeast(32.dp)
            val boxH = if (aspH >= aspW) 54.dp else (54f * aspH / aspW).dp.coerceAtLeast(32.dp)

            Box(
                modifier = Modifier
                    .size(54.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(boxW, boxH)
                        .background(Palette.Panel2, RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${info.aspectW}:${info.aspectH}",
                        fontSize = 10.sp,
                        color = Palette.Accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.name,
                    color = Palette.TextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Badge clip count
                    Box(
                        modifier = Modifier
                            .background(Palette.Panel3, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${info.clipCount} layer", fontSize = 10.sp, color = Palette.TextMain)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Badge duration
                    val sec = String.format(Locale.US, "%.1f dtk", info.durationMs / 1000f)
                    Text(sec, fontSize = 11.sp, color = Palette.TextMuted)
                }
                Spacer(modifier = Modifier.height(2.dp))
                val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.updatedAt))
                Text(dateStr, fontSize = 10.sp, color = Palette.TextMuted)
            }

            // Quick actions: Duplicate, Rename, Delete
            IconButton(onClick = onDuplicate, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplikat", tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Ubah nama", tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Hapus", tint = Palette.Danger, modifier = Modifier.size(18.dp))
            }
        }
    }
}
