package id.motionlab.editor.ui.components

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.ShapeKind
import id.motionlab.editor.core.TrackType
import id.motionlab.editor.ui.Palette

enum class AddCategory(val label: String, val icon: ImageVector) {
    MEDIA("Media", Icons.Filled.Movie),
    TEXT("Teks", Icons.Filled.TextFields),
    SHAPE("Bentuk", Icons.Filled.Category),
    SOLID("Solid", Icons.Filled.FormatColorFill),
    EFFECT("Penyesuaian", Icons.Filled.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddObjectSheet(
    onDismiss: () -> Unit,
    onPickMedia: (TrackType) -> Unit,
    onAddText: () -> Unit,
    onAddShape: (ShapeKind, Int) -> Unit,
    onAddSolid: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCat by remember { mutableStateOf(AddCategory.MEDIA) }

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
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Tambah Objek",
                fontSize = 17.sp,
                color = Palette.TextMain,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Category tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (cat in AddCategory.values()) {
                    val active = cat == selectedCat
                    Row(
                        modifier = Modifier
                            .background(
                                if (active) Palette.AccentGreen.copy(alpha = 0.2f) else Palette.Panel2,
                                RoundedCornerShape(20.dp)
                            )
                            .border(
                                1.dp,
                                if (active) Palette.AccentGreen else Palette.Border,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedCat = cat }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            cat.icon,
                            contentDescription = cat.label,
                            tint = if (active) Palette.AccentGreen else Palette.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            cat.label,
                            color = if (active) Palette.AccentGreen else Palette.TextMain,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Category Content
            when (selectedCat) {
                AddCategory.MEDIA -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MediaOptionCard(
                            title = "Video",
                            icon = Icons.Filled.Videocam,
                            color = Palette.Video,
                            modifier = Modifier.weight(1f)
                        ) {
                            onDismiss()
                            onPickMedia(TrackType.VIDEO)
                        }

                        MediaOptionCard(
                            title = "Gambar",
                            icon = Icons.Filled.Image,
                            color = Palette.Image,
                            modifier = Modifier.weight(1f)
                        ) {
                            onDismiss()
                            onPickMedia(TrackType.IMAGE)
                        }

                        MediaOptionCard(
                            title = "Audio",
                            icon = Icons.Filled.Audiotrack,
                            color = Palette.Audio,
                            modifier = Modifier.weight(1f)
                        ) {
                            onDismiss()
                            onPickMedia(TrackType.AUDIO)
                        }
                    }
                }

                AddCategory.TEXT -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MediaOptionCard(
                            title = "Teks Baru",
                            icon = Icons.Filled.TextFields,
                            color = Palette.Text,
                            modifier = Modifier.weight(1f)
                        ) {
                            onDismiss()
                            onAddText()
                        }
                    }
                }

                AddCategory.SHAPE -> {
                    val shapes = listOf(
                        Pair("Persegi", ShapeKind.RECT),
                        Pair("Bulat", ShapeKind.ROUNDED),
                        Pair("Lingkaran", ShapeKind.ELLIPSE),
                        Pair("Segitiga", ShapeKind.TRIANGLE),
                        Pair("Bintang", ShapeKind.STAR)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for ((name, kind) in shapes) {
                            ShapeOptionChip(name = name, kind = kind) {
                                onDismiss()
                                onAddShape(kind, 0xFF00E5FF.toInt())
                            }
                        }
                    }
                }

                AddCategory.SOLID -> {
                    val colors = listOf(
                        0xFF000000.toInt(),
                        0xFFFFFFFF.toInt(),
                        0xFF00E5FF.toInt(),
                        0xFF00DF81.toInt(),
                        0xFFFF5252.toInt(),
                        0xFFFFD54F.toInt(),
                        0xFF9D4EDD.toInt(),
                        0xFFFF9F1C.toInt()
                    )
                    Column {
                        Text(
                            "Pilih warna latar belakang solid:",
                            fontSize = 12.sp,
                            color = Palette.TextMuted,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (c in colors) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(Color(c), CircleShape)
                                        .border(1.dp, Palette.Border, CircleShape)
                                        .clickable {
                                            onDismiss()
                                            onAddSolid(c)
                                        }
                                )
                            }
                        }
                    }
                }

                AddCategory.EFFECT -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MediaOptionCard(
                            title = "Null Objek",
                            icon = Icons.Filled.ViewInAr,
                            color = Palette.Accent,
                            modifier = Modifier.weight(1f)
                        ) {
                            onDismiss()
                            onAddShape(ShapeKind.RECT, 0x00000000)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaOptionCard(
    title: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .background(Palette.Panel2, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(color.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, color = Palette.TextMain, fontSize = 13.sp)
    }
}

@Composable
private fun ShapeOptionChip(
    name: String,
    kind: ShapeKind,
    onClick: () -> Unit
) {
    val icon = when (kind) {
        ShapeKind.RECT -> Icons.Filled.CropSquare
        ShapeKind.ROUNDED -> Icons.Filled.CropSquare
        ShapeKind.ELLIPSE -> Icons.Filled.RadioButtonUnchecked
        ShapeKind.TRIANGLE -> Icons.Filled.ChangeHistory
        ShapeKind.STAR -> Icons.Filled.StarBorder
        else -> Icons.Filled.CropSquare
    }
    Column(
        modifier = Modifier
            .background(Palette.Panel2, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = name, tint = Palette.Accent, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(name, color = Palette.TextMain, fontSize = 12.sp)
    }
}
