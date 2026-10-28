package id.motionlab.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val AppBg = Color(0xFF0B0D12)
    val Panel = Color(0xFF151923)
    val Panel2 = Color(0xFF202634)
    val Panel3 = Color(0xFF2B3244)
    val Accent = Color(0xFF8B7CFF)
    val AccentSoft = Color(0xFF3A3470)
    val TextMain = Color(0xFFF2F3FA)
    val TextMuted = Color(0xFF9BA3B7)
    val Danger = Color(0xFFFF6B6B)

    val Video = Color(0xFF3C78D8)
    val Audio = Color(0xFF218C78)
    val Image = Color(0xFF9A743D)
    val Text = Color(0xFF7857C8)
    val Shape = Color(0xFFC2527A)
}

@Composable
fun MotionLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.AppBg,
            surface = Palette.Panel,
            primary = Palette.Accent,
            onPrimary = Color.White,
            onBackground = Palette.TextMain,
            onSurface = Palette.TextMain
        ),
        content = content
    )
}
