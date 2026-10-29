package id.motionlab.editor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.Project
import id.motionlab.editor.ui.Palette

@Composable
fun ProjectSettingsDialog(
    project: Project,
    onDismiss: () -> Unit,
    onSave: (name: String, aspectW: Int, aspectH: Int, shortSide: Int, fps: Int) -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var selectedAspect by remember {
        mutableStateOf(
            ASPECT_RATIOS.firstOrNull { it.w == project.aspectW && it.h == project.aspectH }
                ?: AspectRatioOption("${project.aspectW}:${project.aspectH}", project.aspectW, project.aspectH, "Kustom")
        )
    }
    var selectedRes by remember { mutableIntStateOf(project.shortSide) }
    var selectedFps by remember { mutableIntStateOf(project.fps) }

    val resolutions = listOf(Pair("720p HD", 720), Pair("1080p FHD", 1080), Pair("1440p 2K", 1440), Pair("2160p 4K", 2160))
    val fpsOptions = listOf(24, 25, 30, 50, 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Pengaturan Proyek",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.TextMain
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Name
                Text("Nama Proyek", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Aspect Ratio (Preserving object positions and scales)
                Text("Rasio Aspek Kanvas", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 2.dp))
                Text(
                    "Mengubah rasio kanvas mempertahankan posisi dan ukuran setiap objek.",
                    fontSize = 11.sp,
                    color = Palette.AccentGreen,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (asp in ASPECT_RATIOS) {
                        val active = asp.w == selectedAspect.w && asp.h == selectedAspect.h
                        Column(
                            modifier = Modifier
                                .background(if (active) Palette.Panel2 else Palette.AppBg, RoundedCornerShape(8.dp))
                                .border(
                                    1.dp,
                                    if (active) Palette.AccentGreen else Palette.Border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedAspect = asp }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val boxW = if (asp.w >= asp.h) 24.dp else (24f * asp.w / asp.h).dp
                            val boxH = if (asp.h >= asp.w) 24.dp else (24f * asp.h / asp.w).dp
                            Box(
                                modifier = Modifier
                                    .size(boxW, boxH)
                                    .background(if (active) Palette.AccentGreen else Palette.TextMuted, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(asp.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) Palette.AccentGreen else Palette.TextMain)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Resolution
                Text("Resolusi Ekspor", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((label, res) in resolutions) {
                        val active = res == selectedRes
                        Box(
                            modifier = Modifier
                                .background(if (active) Palette.Accent.copy(alpha = 0.2f) else Palette.Panel2, RoundedCornerShape(16.dp))
                                .border(1.dp, if (active) Palette.Accent else Palette.Border, RoundedCornerShape(16.dp))
                                .clickable { selectedRes = res }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(label, fontSize = 12.sp, color = if (active) Palette.Accent else Palette.TextMain)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // FPS
                Text("Frame Rate (FPS)", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (fps in fpsOptions) {
                        val active = fps == selectedFps
                        Box(
                            modifier = Modifier
                                .background(if (active) Palette.AccentGreen.copy(alpha = 0.2f) else Palette.Panel2, RoundedCornerShape(16.dp))
                                .border(1.dp, if (active) Palette.AccentGreen else Palette.Border, RoundedCornerShape(16.dp))
                                .clickable { selectedFps = fps }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("$fps FPS", fontSize = 12.sp, color = if (active) Palette.AccentGreen else Palette.TextMain)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name.ifBlank { project.name }, selectedAspect.w, selectedAspect.h, selectedRes, selectedFps)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.AccentGreen)
            ) {
                Text("Simpan Perubahan", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = Palette.TextMuted)
            }
        }
    )
}
