package id.motionlab.editor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.ui.Palette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Precision Interactive Ruler Slider with scale markings, major numbers,
 * active indicator pointer, direct numeric input dialog, and diamond keyframe integration.
 */
@Composable
fun RulerSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    step: Float = 0.01f,
    displayMultiplier: Float = 1f,
    unit: String = "",
    decimals: Int = 1,
    majorInterval: Float = 1f,
    minorInterval: Float = 0.2f,
    keyframeActive: Boolean = false,
    hasKeyframes: Boolean = false,
    onKeyframeClick: (() -> Unit)? = null
) {
    var showEditDialog by remember { mutableStateOf(false) }

    val displayVal = value * displayMultiplier
    val formattedText = if (decimals == 0) {
        "${displayVal.roundToInt()}$unit"
    } else {
        String.format(Locale.US, "%.${decimals}f%s", displayVal, unit)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // ---- Top row: Label, Value readout badge, Keyframe diamond ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Palette.TextMain,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            // Direct numeric input box
            Box(
                modifier = Modifier
                    .background(Palette.Panel2, RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { showEditDialog = true }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formattedText,
                    color = Palette.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (onKeyframeClick != null) {
                Spacer(modifier = Modifier.width(8.dp))
                DiamondKeyframeButton(
                    active = keyframeActive,
                    animated = hasKeyframes,
                    onClick = onKeyframeClick
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Bottom row: The interactive Canvas Ruler ----
        val textPaint = remember {
            android.graphics.Paint().apply {
                color = android.graphics.Color.argb(160, 154, 160, 166)
                textSize = 22f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(min, max, step) {
                    detectDragGestures { _, dragAmount ->
                        val pxWidth = size.width.toFloat()
                        if (pxWidth > 0f) {
                            // Sensitivity: full ruler width corresponds to approximately 4 major intervals
                            val span = (majorInterval * 4f).coerceAtLeast(max - min)
                            val deltaVal = -(dragAmount.x / pxWidth) * span
                            val newVal = (value + deltaVal).coerceIn(min, max)
                            val rounded = (newVal / step).roundToInt() * step
                            onValueChange(rounded.coerceIn(min, max))
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f

            // Baseline for ticks
            val baseLineY = h - 14f

            // Background subtle ruler groove
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, baseLineY),
                end = Offset(w, baseLineY),
                strokeWidth = 1f
            )

            // Calculate visible range of ruler ticks
            val pxPerUnit = (w / (majorInterval * 4f).coerceAtLeast(max - min))
            val curVal = value

            // Find start and end value for ticks to render
            val valLeft = curVal - (centerX / pxPerUnit)
            val valRight = curVal + ((w - centerX) / pxPerUnit)

            val safeMinor = if (minorInterval > 0f) minorInterval else 0.1f
            val startTick = (Math.floor((valLeft / safeMinor).toDouble()) * safeMinor).toFloat()
            val endTick = (Math.ceil((valRight / safeMinor).toDouble()) * safeMinor).toFloat()

            var tickVal = startTick
            var count = 0
            while (tickVal <= endTick && count < 200) {
                if (tickVal >= min - 0.001f && tickVal <= max + 0.001f) {
                    val x = centerX + (tickVal - curVal) * pxPerUnit
                    if (x in 0f..w) {
                        // Check if it's a major tick
                        val isMajor = abs(tickVal / majorInterval - (tickVal / majorInterval).roundToInt()) < 0.01f
                        val isMedium = !isMajor && abs((tickVal / (safeMinor * 5f)) - (tickVal / (safeMinor * 5f)).roundToInt()) < 0.01f

                        val tickH = when {
                            isMajor -> 18f
                            isMedium -> 12f
                            else -> 7f
                        }

                        val tickColor = when {
                            isMajor -> Color(0xDDFFFFFF)
                            isMedium -> Color(0x99FFFFFF)
                            else -> Color(0x44FFFFFF)
                        }

                        val strokeW = if (isMajor) 1.5f else 1f

                        drawLine(
                            color = tickColor,
                            start = Offset(x, baseLineY - tickH),
                            end = Offset(x, baseLineY),
                            strokeWidth = strokeW
                        )

                        if (isMajor) {
                            val disp = tickVal * displayMultiplier
                            val numStr = if (decimals == 0 || abs(disp - disp.roundToInt()) < 0.01f) {
                                "${disp.roundToInt()}"
                            } else {
                                String.format(Locale.US, "%.1f", disp)
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                numStr,
                                x,
                                h - 1f,
                                textPaint
                            )
                        }
                    }
                }
                tickVal += safeMinor
                count++
            }

            // ---- Center Active Indicator (Pointer / Needle) ----
            val needleColor = Palette.Accent
            // Vertical needle line
            drawLine(
                color = needleColor,
                start = Offset(centerX, 2f),
                end = Offset(centerX, baseLineY + 2f),
                strokeWidth = 2.5f
            )

            // Top Triangle Indicator ▼
            val topTri = Path().apply {
                moveTo(centerX - 5f, 0f)
                lineTo(centerX + 5f, 0f)
                lineTo(centerX, 6f)
                close()
            }
            drawPath(topTri, needleColor)

            // Bottom Pointer Triangle ▲
            val botTri = Path().apply {
                moveTo(centerX, baseLineY)
                lineTo(centerX - 5f, baseLineY + 6f)
                lineTo(centerX + 5f, baseLineY + 6f)
                close()
            }
            drawPath(botTri, needleColor)
        }
    }

    // Direct numeric input dialog
    if (showEditDialog) {
        var draftText by remember {
            mutableStateOf(
                if (decimals == 0) "${(value * displayMultiplier).roundToInt()}"
                else String.format(Locale.US, "%.${decimals}f", value * displayMultiplier)
            )
        }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Atur $label") },
            text = {
                Column {
                    Text(
                        "Rentang: ${(min * displayMultiplier).roundToInt()} s/d ${(max * displayMultiplier).roundToInt()} $unit",
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        suffix = { if (unit.isNotEmpty()) Text(unit) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = draftText.trim().replace(',', '.').toFloatOrNull()
                    if (parsed != null) {
                        val actualVal = (parsed / displayMultiplier).coerceIn(min, max)
                        onValueChange(actualVal)
                    }
                    showEditDialog = false
                }) {
                    Text("Terapkan", color = Palette.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

/**
 * Diamond-shaped keyframe button:
 * - Active: Filled diamond (keyframe exists at current playhead)
 * - Animated: Outlined diamond with gold highlight (property has keyframes elsewhere)
 * - Inactive: Outlined muted diamond (no keyframes)
 */
@Composable
fun DiamondKeyframeButton(
    active: Boolean,
    animated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.width / 2f - 1.5f
            val p = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r, c.y)
                lineTo(c.x, c.y + r)
                lineTo(c.x - r, c.y)
                close()
            }
            val gold = Color(0xFFFFD54F)
            val muted = Palette.TextMuted
            val col = if (animated || active) gold else muted

            if (active) {
                drawPath(p, col)
                drawPath(p, Color(0xFF1E1700), style = Stroke(1.5f))
            } else {
                drawPath(p, col, style = Stroke(2f))
            }
        }
    }
}
