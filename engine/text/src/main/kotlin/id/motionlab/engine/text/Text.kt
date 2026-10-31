package id.motionlab.engine.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlResult
import java.io.File

enum class TextAlign { LEFT, CENTER, RIGHT }

data class TypographyConfig(
    /** A system family ("sans-serif", "serif", "monospace"...) or the name of a font registered in [FontManager]. */
    val fontFamily: String = "sans-serif",
    val sizePx: Float = 48f,
    val colorArgb: Int = Color.WHITE,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacingEm: Float = 0f,
    val lineSpacingMultiplier: Float = 1f,
    val align: TextAlign = TextAlign.CENTER,
) {
    fun validate(): List<String> = buildList {
        if (sizePx !in 1f..2048f) add("sizePx must be within 1..2048")
        if (letterSpacingEm !in -1f..5f) add("letterSpacingEm must be within -1..5")
        if (lineSpacingMultiplier !in 0.1f..5f) add("lineSpacingMultiplier must be within 0.1..5")
        if (fontFamily.isBlank()) add("fontFamily must not be blank")
    }
}

data class TextObject(
    val id: String,
    val text: String,
    val typography: TypographyConfig = TypographyConfig(),
    /** Wrap width in pixels; null = no wrapping (width follows the longest line). */
    val maxWidthPx: Int? = null,
)

/** Resolves typefaces: system families plus fonts the user imported (TTF/OTF files). */
class FontManager {
    private val custom = LinkedHashMap<String, Typeface>()

    /** Loads a font file and registers it under [name]. Fails (instead of crashing) on unreadable/invalid fonts. */
    @Synchronized
    fun registerFontFile(name: String, file: File): MlResult<Unit> {
        if (name.isBlank()) return MlResult.Failure(MlException(ErrorCode.INVALID_ARGUMENT, "font name is blank"))
        if (!file.isFile) return MlResult.Failure(MlException(ErrorCode.NOT_FOUND, "Font file not found: ${file.path}"))
        return try {
            custom[name] = Typeface.createFromFile(file)
            MlResult.Success(Unit)
        } catch (e: Exception) {
            MlResult.Failure(MlException(ErrorCode.DECODE, "Cannot load font ${file.name}: ${e.message}", e))
        }
    }

    @Synchronized
    fun registeredNames(): List<String> = custom.keys.toList()

    @Synchronized
    fun resolve(family: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val base = custom[family] ?: Typeface.create(family, Typeface.NORMAL)
        return Typeface.create(base, style)
    }
}

/** Draws a [TextObject] into a transparent ARGB bitmap (premultiplied) that the compositor uploads as a texture. */
class TextRenderer(private val fonts: FontManager) {
    fun render(obj: TextObject): Bitmap {
        val problems = obj.typography.validate()
        if (problems.isNotEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, problems.joinToString("; "))
        if (obj.text.isEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, "Text must not be empty")

        val t = obj.typography
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.resolve(t.fontFamily, t.bold, t.italic)
            textSize = t.sizePx
            color = t.colorArgb
            letterSpacing = t.letterSpacingEm
        }
        val widest = obj.text.split('\n').maxOf { Layout.getDesiredWidth(it, paint) }
        val width = (obj.maxWidthPx ?: kotlin.math.ceil(widest.toDouble()).toInt()).coerceAtLeast(1)
        val alignment = when (t.align) {
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(obj.text, 0, obj.text.length, paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, t.lineSpacingMultiplier)
            .setIncludePad(false)
            .build()

        val height = layout.height.coerceAtLeast(1)
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Rendered text would be ${width}x$height (max $MAX_DIMENSION)")
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        return bitmap
    }

    companion object {
        const val MAX_DIMENSION = 4096
    }
}
