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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import id.motionlab.editor.ui.Palette

data class AspectRatioOption(val label: String, val w: Int, val h: Int, val desc: String)

val ASPECT_RATIOS = listOf(
    AspectRatioOption("16:9", 16, 9, "Landscape (YouTube)"),
    AspectRatioOption("9:16", 9, 16, "Portrait (TikTok / Reels)"),
    AspectRatioOption("1:1", 1, 1, "Persegi (Instagram)"),
    AspectRatioOption("4:5", 4, 5, "Feed (Instagram)"),
    AspectRatioOption("4:3", 4, 3, "Standar"),
    AspectRatioOption("21:9", 21, 9, "Sinematik")
)

@Composable
fun NewProjectDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, aspectW: Int, aspectH: Int, shortSide: Int, fps: Int, bgColor: Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedAspect by remember { mutableStateOf(ASPECT_RATIOS[1]) } // Default 9:16 for mobile video
    var selectedRes by remember { mutableIntStateOf(1080) }
    var selectedFps by remember { mutableIntStateOf(30) }
    var selectedBg by remember { mutableIntStateOf(0xFF000000.toInt()) }

    val resolutions = listOf(Pair("720p HD", 720), Pair("1080p FHD", 1080), Pair("1440p 2K", 1440), Pair("2160p 4K", 2160))
    val fpsOptions = listOf(24, 25, 30, 50, 60)
    val bgColors = listOf(
        Pair("Hitam", 0xFF000000.toInt()),
        Pair("Abu-abu", 0xFF181A20.toInt()),
        Pair("Putih", 0xFFFFFFFF.toInt()),
        Pair("Transparan", 0x00000000)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Buat Proyek Baru",
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
                // Project Name
                Text("Nama Proyek", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Aspect Ratio
                Text("Rasio Aspek", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (asp in ASPECT_RATIOS) {
                        val active = asp == selectedAspect
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
                            // Mini rectangle preview
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
                Text("Resolusi", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
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

                // Frame Rate
                Text("Kecepatan Bingkai (FPS)", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
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

                Spacer(modifier = Modifier.height(14.dp))

                // Background Color
                Text("Warna Latar Belakang", fontSize = 12.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for ((label, color) in bgColors) {
                        val active = color == selectedBg
                        Column(
                            modifier = Modifier.clickable { selectedBg = color },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(color), CircleShape)
                                    .border(if (active) 2.5.dp else 1.dp, if (active) Palette.AccentGreen else Palette.Border, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(label, fontSize = 10.sp, color = if (active) Palette.AccentGreen else Palette.TextMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.ifBlank { "Proyek Baru" },
                        selectedAspect.w,
                        selectedAspect.h,
                        selectedRes,
                        selectedFps,
                        selectedBg
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.AccentGreen)
            ) {
                Text("Buat Proyek", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = Palette.TextMuted)
            }
        }
    )
}
