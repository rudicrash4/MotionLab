package id.motionlab.feature

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** MotionLab visual identity: near-black and charcoal surfaces, one neon-green accent. */
object MlColors {
    val Black = Color(0xFF0A0A0B)
    val Charcoal = Color(0xFF141416)
    val Panel = Color(0xFF1C1D20)
    val PanelHigh = Color(0xFF26272B)
    val Outline = Color(0xFF3A3C41)
    val Accent = Color(0xFF39FF88)
    val AccentDim = Color(0xFF1F8A4B)
    val OnSurface = Color(0xFFE8E9EB)
    val Muted = Color(0xFF9A9CA3)
    val Danger = Color(0xFFFF5C5C)
    val Playhead = Color(0xFFFF4D4D)
    val Keyframe = Color(0xFFFFD54F)
}

private val scheme = darkColorScheme(
    primary = MlColors.Accent,
    onPrimary = Color.Black,
    primaryContainer = MlColors.AccentDim,
    onPrimaryContainer = Color.White,
    secondary = MlColors.Accent,
    background = MlColors.Black,
    onBackground = MlColors.OnSurface,
    surface = MlColors.Charcoal,
    onSurface = MlColors.OnSurface,
    surfaceVariant = MlColors.Panel,
    onSurfaceVariant = MlColors.Muted,
    outline = MlColors.Outline,
    outlineVariant = MlColors.PanelHigh,
    error = MlColors.Danger,
)

@Composable
fun MotionLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
