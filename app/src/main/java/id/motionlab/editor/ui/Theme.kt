package id.motionlab.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val AppBg = Color(0xFF0F1015)
    val Panel = Color(0xFF181A22)
    val Panel2 = Color(0xFF222632)
    val Panel3 = Color(0xFF2E3344)
    val Border = Color(0xFF2B3040)
    val Accent = Color(0xFF00E5FF)       // Cyan
    val AccentCyan = Color(0xFF00E5FF)
    val AccentGreen = Color(0xFF00DF81)  // Mint green
    val AccentSoft = Color(0xFF0D3846)
    val TextMain = Color(0xFFF3F4F8)
    val TextMuted = Color(0xFF9AA0A6)
    val Danger = Color(0xFFFF5252)
    val Gold = Color(0xFFFFD54F)        // Keyframe diamond gold

    val Video = Color(0xFF00B4D8)
    val Audio = Color(0xFF2EC4B6)
    val Image = Color(0xFF9D4EDD)
    val Text = Color(0xFFFF9F1C)
    val Shape = Color(0xFFFF5C5C)
}

@Composable
fun MotionLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.AppBg,
            surface = Palette.Panel,
            primary = Palette.AccentGreen,
            secondary = Palette.Accent,
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = Palette.TextMain,
            onSurface = Palette.TextMain
        ),
        content = content
    )
}
