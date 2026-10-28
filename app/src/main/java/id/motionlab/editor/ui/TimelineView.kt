package id.motionlab.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.Props
import id.motionlab.editor.core.Track
import id.motionlab.editor.core.TrackType
import id.motionlab.editor.core.TimelineController
import id.motionlab.editor.core.TransitionType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private class Metrics(
    val rulerH: Float,
    val trackH: Float,
    val headerW: Float,
    val handleW: Float,
    val pad: Float
)

private fun Density.metrics() = Metrics(26.dp.toPx(), 46.dp.toPx(), 52.dp.toPx(), 22.dp.toPx(), 3.dp.toPx())

private sealed class Hit {
    object Ruler : Hit()
    object Empty : Hit()
    class Header(val trackIdx: Int) : Hit()
    class Body(val clipId: String) : Hit()
    class Handle(val clipId: String, val left: Boolean) : Hit()
}

private fun hitTest(
    p: Project, selId: String?, x: Float, y: Float, m: Metrics,
    scrollY: Float, originX: Float, playhead: Long, pxPerMs: Float
): Hit {
    if (y < m.rulerH) return Hit.Ruler
    val ti = floor(((y - m.rulerH + scrollY) / m.trackH).toDouble()).toInt()
    if (ti < 0 || ti >= p.tracks.size) return Hit.Empty
    if (x < m.headerW) return Hit.Header(ti)
    val track = p.tracks[ti]
    val xs = { t: Long -> originX + (t - playhead) * pxPerMs }
    val sel = track.clips.firstOrNull { it.id == selId }
    if (sel != null && !track.locked) {
        val xl = xs(sel.startMs)
        val xr = xs(sel.endMs)
        val hw = min(m.handleW, (xr - xl) / 3f)
        if (x >= xl - hw * 0.5f && x <= xl + hw) return Hit.Handle(sel.id, true)
        if (x >= xr - hw && x <= xr + hw * 0.5f) return Hit.Handle(sel.id, false)
    }
    for (c in track.clips) {
        if (x >= xs(c.startMs) && x <= xs(c.endMs)) return Hit.Body(c.id)
    }
    return Hit.Empty
}

/**
 * Loop drag generik. Mengembalikan true bila gestur "terlibat" (melewati slop atau pinch).
 * Dua jari = pinch-to-zoom horizontal (memanggil [onZoom] dengan faktor skala).
 */
private suspend fun AwaitPointerEventScope.dragLoop(
    slop: Float,
    onZoom: (Float) -> Unit,
    onDrag: (Offset, Offset) -> Unit
): Boolean {
    var total = 0f
    var engaged = slop <= 0f
    var touched = false
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) return touched
        if (pressed.size >= 2) {
            val a = pressed[0]
            val b = pressed[1]
            val d = max(abs(a.position.x - b.position.x), 1f)
            val pd = max(abs(a.previousPosition.x - b.previousPosition.x), 1f)
            onZoom(d / pd)
            touched = true
            for (c in event.changes) c.consume()
            continue
        }
        val ch = pressed[0]
        val delta = ch.position - ch.previousPosition
        if (!engaged) {
            total += abs(delta.x) + abs(delta.y)
            if (total > slop) {
                engaged = true
                touched = true
            }
        }
        if (engaged && (delta.x != 0f || delta.y != 0f)) {
            onDrag(delta, ch.position)
            ch.consume()
        }
    }
}

private fun formatTime(ms: Long, fine: Boolean): String {
    val total = ms / 1000L
    val m = total / 60L
    val s = total % 60L
    return if (fine) {
        val d = (ms % 1000L) / 100L
        "$m:${s.toString().padStart(2, '0')}.$d"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

private fun typeColor(t: TrackType): Color = when (t) {
    TrackType.VIDEO -> Palette.Video
    TrackType.AUDIO -> Palette.Audio
    TrackType.IMAGE -> Palette.Image
    TrackType.TEXT -> Palette.Text
    TrackType.SHAPE -> Palette.Shape
}

private fun typeGlyph(t: TrackType): String = when (t) {
    TrackType.VIDEO -> "VID"
    TrackType.AUDIO -> "AUD"
    TrackType.IMAGE -> "IMG"
    TrackType.TEXT -> "TXT"
    TrackType.SHAPE -> "SHP"
}

/**
 * Timeline multi-track berbasis Canvas. Playhead tetap di tengah; konten bergeser.
 * Gestur:
 *  - geser pada area kosong / penggaris : scrub waktu (horizontal) dan gulir track (vertikal)
 *  - dua jari : pinch-to-zoom
 *  - ketuk klip : pilih; tekan-tahan klip lalu geser : pindahkan (magnet ke tepi klip lain & playhead,
 *    bisa pindah ke track sejenis lain)
 *  - seret pegangan di tepi klip terpilih : trim
 *  - tekan-tahan header track lalu geser vertikal : ubah urutan layer; ketuk header : sembunyikan/bisukan
 */
@Composable
fun TimelineView(
    project: Project,
    selectedClipId: String?,
    playheadMs: Long,
    pxPerSec: Float,
    thumbs: ThumbnailStore,
    controller: TimelineController,
    onSeek: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    onHeaderTap: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val projectS by rememberUpdatedState(project)
    val selS by rememberUpdatedState(selectedClipId)
    val phS by rememberUpdatedState(playheadMs)
    val zoomS by rememberUpdatedState(pxPerSec)
    val onSeekS by rememberUpdatedState(onSeek)
    val onZoomS by rememberUpdatedState(onZoom)
    val onHeaderTapS by rememberUpdatedState(onHeaderTap)
    var scrollY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val labelPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 11.sp.toPx() }
        }
    }
    val mutedPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9BA3B7.toInt()
            textSize = with(density) { 10.sp.toPx() }
        }
    }
    // Berlangganan agar Canvas digambar ulang saat thumbnail baru siap.
    val thumbVersion = thumbs.version.intValue

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val m = metrics()
                    val w = size.width.toFloat()
                    val originX = m.headerW + (w - m.headerW) / 2f
                    val proj0 = projectS
                    val hit = hitTest(
                        proj0, selS, down.position.x, down.position.y, m,
                        scrollY, originX, phS, zoomS / 1000f
                    )
                    val slop = viewConfiguration.touchSlop
                    val snapPx = 8.dp.toPx()

                    fun ppm(): Float = zoomS / 1000f
                    fun tOf(x: Float): Long = phS + ((x - originX) / ppm()).toLong()
                    fun maxScroll(): Float {
                        val content = projectS.tracks.size * m.trackH + m.trackH
                        return max(0f, content - (size.height - m.rulerH))
                    }
                    fun trackAtY(y: Float): Int = floor(((y - m.rulerH + scrollY) / m.trackH).toDouble()).toInt()

                    // Pan: scrub horizontal / gulir vertikal, dengan penguncian sumbu.
                    var panTime = phS.toDouble()
                    var axis = 0
                    fun pan(d: Offset) {
                        if (axis == 0) axis = if (abs(d.x) >= abs(d.y)) 1 else 2
                        if (axis == 1) {
                            panTime = (panTime - d.x / ppm()).coerceIn(0.0, projectS.durationMs.toDouble())
                            onSeekS(panTime.toLong())
                        } else {
                            scrollY = (scrollY - d.y).coerceIn(0f, maxScroll())
                        }
                    }

                    when (hit) {
                        is Hit.Handle -> {
                            val c0 = proj0.findClip(hit.clipId)
                            if (c0 != null) {
                                controller.beginGesture()
                                val edge0 = if (hit.left) c0.startMs else c0.endMs
                                val x0 = down.position.x
                                dragLoop(0f, { f -> onZoomS(f) }) { _, pos ->
                                    val raw = edge0 + ((pos.x - x0) / ppm()).toLong()
                                    val thr = (snapPx / ppm()).toLong()
                                    val v = controller.snapPoint(raw, hit.clipId, phS, thr)
                                    if (hit.left) controller.trimStart(hit.clipId, v) else controller.trimEnd(hit.clipId, v)
                                }
                            }
                        }

                        is Hit.Body -> {
                            val lp = awaitLongPressOrCancellation(down.id)
                            if (lp != null) {
                                controller.select(hit.clipId)
                                val c0 = proj0.findClip(hit.clipId)
                                if (c0 != null) {
                                    controller.beginGesture()
                                    val grab = tOf(down.position.x) - c0.startMs
                                    dragLoop(0f, { f -> onZoomS(f) }) { _, pos ->
                                        val desired = tOf(pos.x) - grab
                                        val thr = (snapPx / ppm()).toLong()
                                        val snapped = controller.snapStart(desired, c0.durationMs, c0.id, phS, thr)
                                        controller.moveClip(c0.id, max(0L, snapped), trackAtY(pos.y))
                                    }
                                }
                            } else {
                                val stillDown = currentEvent.changes.any { it.pressed }
                                if (!stillDown) {
                                    controller.select(hit.clipId)
                                } else {
                                    dragLoop(0f, { f -> onZoomS(f) }) { d, _ -> pan(d) }
                                }
                            }
                        }

                        is Hit.Header -> {
                            val lp = awaitLongPressOrCancellation(down.id)
                            if (lp != null) {
                                controller.beginGesture()
                                var cur = hit.trackIdx
                                dragLoop(0f, { }) { _, pos ->
                                    val ti = trackAtY(pos.y).coerceIn(0, max(0, projectS.tracks.size - 1))
                                    if (ti != cur) {
                                        controller.moveTrack(cur, ti)
                                        cur = ti
                                    }
                                }
                            } else {
                                val stillDown = currentEvent.changes.any { it.pressed }
                                if (!stillDown) {
                                    projectS.tracks.getOrNull(hit.trackIdx)?.let { onHeaderTapS(it) }
                                } else {
                                    dragLoop(slop, { f -> onZoomS(f) }) { d, _ -> pan(d) }
                                }
                            }
                        }

                        is Hit.Ruler -> {
                            val engaged = dragLoop(slop, { f -> onZoomS(f) }) { d, _ ->
                                axis = 1
                                pan(d)
                            }
                            if (!engaged) onSeekS(max(0L, tOf(down.position.x)))
                        }

                        is Hit.Empty -> {
                            val engaged = dragLoop(slop, { f -> onZoomS(f) }) { d, _ -> pan(d) }
                            if (!engaged) controller.select(null)
                        }
                    }
                }
            }
    ) {
        if (thumbVersion < 0) return@Canvas
        drawTimeline(
            projectS, selS, phS, zoomS / 1000f, scrollY, thumbs, labelPaint, mutedPaint
        )
    }
}

// =============================================================== menggambar

private fun DrawScope.drawTimeline(
    project: Project,
    selId: String?,
    playhead: Long,
    pxPerMs: Float,
    scrollY: Float,
    thumbs: ThumbnailStore,
    labelPaint: android.graphics.Paint,
    mutedPaint: android.graphics.Paint
) {
    val m = metrics()
    val w = size.width
    val h = size.height
    val originX = m.headerW + (w - m.headerW) / 2f
    fun xOf(t: Long): Float = originX + (t - playhead) * pxPerMs

    drawRect(Palette.Panel, Offset.Zero, Size(w, h))

    // ---- area track ----
    clipRect(m.headerW, m.rulerH, w, h) {
        // Area sebelum 0 s digelapkan.
        val x0 = xOf(0L)
        if (x0 > m.headerW) drawRect(Color(0x66000000), Offset(m.headerW, m.rulerH), Size(x0 - m.headerW, h - m.rulerH))

        for ((i, track) in project.tracks.withIndex()) {
            val rowTop = m.rulerH + i * m.trackH - scrollY
            if (rowTop > h || rowTop + m.trackH < m.rulerH) continue
            if (i % 2 == 1) drawRect(Color(0x14FFFFFF), Offset(m.headerW, rowTop), Size(w - m.headerW, m.trackH))
            for (c in track.clips) {
                val xl = xOf(c.startMs)
                val xr = xOf(c.endMs)
                if (xr < m.headerW || xl > w) continue
                drawClip(c, track, xl, xr, rowTop + m.pad, m.trackH - 2 * m.pad, c.id == selId, m, pxPerMs, thumbs, labelPaint)
            }
        }
    }

    // ---- header track (kiri) ----
    for ((i, track) in project.tracks.withIndex()) {
        val rowTop = m.rulerH + i * m.trackH - scrollY
        if (rowTop > h || rowTop + m.trackH < m.rulerH) continue
        clipRect(0f, m.rulerH, m.headerW, h) {
            drawRect(Palette.Panel2, Offset(0f, rowTop), Size(m.headerW - 1f, m.trackH))
            drawRect(typeColor(track.type), Offset(0f, rowTop + m.pad), Size(4f, m.trackH - 2 * m.pad))
            val dim = track.hidden || (track.type == TrackType.AUDIO && track.muted)
            labelPaint.alpha = if (dim) 110 else 255
            drawContext.canvas.nativeCanvas.drawText(typeGlyph(track.type), 10f, rowTop + m.trackH * 0.42f, labelPaint)
            labelPaint.alpha = 255
            val state = buildString {
                if (track.hidden) append("sembunyi ")
                if (track.muted) append("mute ")
                if (track.locked) append("kunci")
            }
            if (state.isNotEmpty()) {
                drawContext.canvas.nativeCanvas.drawText(state.trim(), 10f, rowTop + m.trackH * 0.78f, mutedPaint)
            } else {
                drawContext.canvas.nativeCanvas.drawText("L${project.tracks.size - i}", 10f, rowTop + m.trackH * 0.78f, mutedPaint)
            }
        }
    }

    // ---- penggaris waktu ----
    drawRect(Color(0xFF10131B), Offset.Zero, Size(w, m.rulerH))
    clipRect(m.headerW, 0f, w, m.rulerH) {
        val steps = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
        val pxPerSec = pxPerMs * 1000f
        var stepSec = 600.0
        for (s in steps) if (s * pxPerSec >= 80.0) { stepSec = s; break }
        val stepMs = max(1L, (stepSec * 1000.0).toLong())
        val tLeft = playhead + ((m.headerW - originX) / pxPerMs).toLong()
        val tRight = playhead + ((w - originX) / pxPerMs).toLong()
        var t = (max(0L, tLeft) / stepMs) * stepMs
        val fine = stepMs < 1000L
        val sub = stepMs / 5f
        while (t <= tRight + stepMs) {
            val x = xOf(t)
            drawLine(Palette.TextMuted, Offset(x, m.rulerH * 0.45f), Offset(x, m.rulerH), 1.5f)
            drawContext.canvas.nativeCanvas.drawText(formatTime(t, fine), x + 4f, m.rulerH * 0.42f, mutedPaint)
            for (k in 1..4) {
                val xs = xOf(t + (sub * k).toLong())
                drawLine(Color(0x55FFFFFF), Offset(xs, m.rulerH * 0.75f), Offset(xs, m.rulerH), 1f)
            }
            t += stepMs
        }
    }
    drawLine(Color(0x33FFFFFF), Offset(0f, m.rulerH), Offset(w, m.rulerH), 1f)

    // ---- playhead ----
    drawLine(Color.White, Offset(originX, 0f), Offset(originX, h), 2f)
    val tri = Path()
    tri.moveTo(originX - 8f, 0f)
    tri.lineTo(originX + 8f, 0f)
    tri.lineTo(originX, 12f)
    tri.close()
    drawPath(tri, Palette.Accent)
}

private fun DrawScope.drawClip(
    c: Clip, track: Track, xl: Float, xr: Float, top: Float, hgt: Float,
    selected: Boolean, m: Metrics, pxPerMs: Float, thumbs: ThumbnailStore,
    labelPaint: android.graphics.Paint
) {
    val base = typeColor(c.type)
    val dim = track.hidden || (c.type == TrackType.AUDIO && track.muted)
    val alpha = if (dim) 0.35f else if (selected) 1f else 0.82f
    val width = xr - xl
    val radius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
    drawRoundRect(base.copy(alpha = alpha), Offset(xl, top), Size(width, hgt), radius)

    clipRect(xl, top, xr, top + hgt) {
        // Filmstrip video.
        if (c.type == TrackType.VIDEO && c.uri.isNotEmpty() && !dim) {
            val tileH = hgt
            val tileW = tileH * 16f / 9f
            val visL = max(xl, m.headerW)
            val firstTile = floor(((visL - xl) / tileW).toDouble()).toInt()
            var k = firstTile
            while (xl + k * tileW < size.width && xl + k * tileW < xr && k < firstTile + 40) {
                val tx = xl + k * tileW
                val srcMs = c.sourceTimeAt(((tx + tileW * 0.5f - xl) / pxPerMs).toDouble()).toLong()
                val img = thumbs.get(c.uri, srcMs)
                if (img != null) {
                    val ta = 16f / 9f
                    val ia = img.width.toFloat() / img.height.toFloat()
                    val srcW: Int
                    val srcH: Int
                    if (ia > ta) { srcH = img.height; srcW = (img.height * ta).toInt() }
                    else { srcW = img.width; srcH = (img.width / ta).toInt() }
                    drawImage(
                        img,
                        IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2), IntSize(srcW, srcH),
                        IntOffset(tx.toInt(), top.toInt()), IntSize(tileW.toInt() + 1, tileH.toInt()),
                        0.9f
                    )
                }
                k++
            }
            drawRect(Color(0x55000000), Offset(xl, top), Size(width, hgt))
        }

        // Gelombang audio ASLI (puncak amplitudo dari audio sumber, mengikuti trim & kurva kecepatan).
        if (c.type == TrackType.AUDIO && c.uri.isNotEmpty()) {
            val peaks = thumbs.peaks(c.uri)
            val step = 2.dp.toPx()
            val visL = max(xl, m.headerW)
            var x = xl + floor(((visL - xl) / step).toDouble()).toFloat() * step
            val mid = top + hgt / 2f
            val full = hgt * 0.9f
            if (peaks == null) {
                drawLine(Color(0x55FFFFFF), Offset(visL, mid), Offset(min(xr, size.width), mid), 2f)
            } else {
                val map = c.speedMap
                while (x < xr && x < size.width) {
                    val l0 = ((x - xl) / pxPerMs).toDouble()
                    val l1 = ((x + step - xl) / pxPerMs).toDouble()
                    val s0 = c.sourceInMs + map.offset(l0.coerceIn(0.0, c.durationMs.toDouble()))
                    val s1 = c.sourceInMs + map.offset(l1.coerceIn(0.0, c.durationMs.toDouble()))
                    var i0 = (s0 / 10.0).toInt()
                    var i1 = (s1 / 10.0).toInt()
                    if (i1 < i0) { val t = i0; i0 = i1; i1 = t }
                    var pk = 0f
                    var i = i0.coerceAtLeast(0)
                    val last = min(i1, peaks.size - 1)
                    var guard = 0
                    while (i <= last && guard < 64) {
                        if (peaks[i] > pk) pk = peaks[i]
                        i++
                        guard++
                    }
                    val amp = Math.sqrt(pk.toDouble()).toFloat() // kompresi ringan agar suara pelan tetap terlihat
                    val bh = max(2f, full * amp * c.volume.coerceIn(0f, 1.5f))
                    drawLine(Color(0xAAFFFFFF), Offset(x, mid - bh / 2f), Offset(x, mid + bh / 2f), 1.5f)
                    x += step
                }
            }
        }

        // Penanda fade in / out.
        if (c.fadeInMs > 0L) {
            val fw = min(c.fadeInMs * pxPerMs, width)
            val p = Path()
            p.moveTo(xl, top + hgt); p.lineTo(xl + fw, top); p.lineTo(xl, top); p.close()
            drawPath(p, Color(0x66000000))
        }
        if (c.fadeOutMs > 0L) {
            val fw = min(c.fadeOutMs * pxPerMs, width)
            val p = Path()
            p.moveTo(xr, top + hgt); p.lineTo(xr - fw, top); p.lineTo(xr, top); p.close()
            drawPath(p, Color(0x66000000))
        }

        // Transisi.
        if (c.transitionIn.type != TransitionType.NONE) {
            val tw = min(c.transitionIn.durationMs * pxPerMs, width)
            drawRect(Color(0x66FFFFFF), Offset(xl, top), Size(tw, hgt * 0.22f))
        }
        if (c.transitionOut.type != TransitionType.NONE) {
            val tw = min(c.transitionOut.durationMs * pxPerMs, width)
            drawRect(Color(0x66FFFFFF), Offset(xr - tw, top), Size(tw, hgt * 0.22f))
        }

        // Label.
        val label = when (c.type) {
            TrackType.TEXT -> c.text.ifEmpty { c.name }
            else -> c.name
        } + (if (c.hasSpeedRamp) "  ⏩ramp" else if (c.type == TrackType.VIDEO || c.type == TrackType.AUDIO) {
            val sp = c.baseValue(Props.SPEED)
            if (kotlin.math.abs(sp - 1f) > 0.01f) "  ${String.format(java.util.Locale.US, "%.2f", sp)}x" else ""
        } else "")
        val maxW = max(0f, width - 12f)
        val n = labelPaint.breakText(label, true, maxW, null)
        if (n > 0) {
            drawContext.canvas.nativeCanvas.drawText(label.substring(0, n), max(xl, m.headerW) + 6f, top + hgt * 0.38f, labelPaint)
        }

        // Keyframe (hanya klip terpilih).
        if (selected) {
            val seen = HashSet<Long>()
            for (kt in c.keyframes.values) for (f in kt.frames) {
                if (!seen.add(f.timeMs)) continue
                val kx = xl + f.timeMs * pxPerMs
                val ky = top + hgt * 0.78f
                val r = 5.dp.toPx()
                val p = Path()
                p.moveTo(kx, ky - r); p.lineTo(kx + r, ky); p.lineTo(kx, ky + r); p.lineTo(kx - r, ky); p.close()
                drawPath(p, Color(0xFFFFD54F))
                drawPath(p, Color(0xFF3A2E00), style = Stroke(1f))
            }
        }
    }

    if (selected) {
        drawRoundRect(Color.White, Offset(xl, top), Size(width, hgt), radius, style = Stroke(2.dp.toPx()))
        val hw = min(m.handleW, width / 3f)
        drawRoundRect(Color.White, Offset(xl, top), Size(hw * 0.5f, hgt), radius)
        drawRoundRect(Color.White, Offset(xr - hw * 0.5f, top), Size(hw * 0.5f, hgt), radius)
    }
}
