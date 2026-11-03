package id.motionlab.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.CompositeCommand
import id.motionlab.engine.timeline.SetPropertyValue

/**
 * "Anchor & Align": align the selected clip to the canvas edges/centre (moves Position X/Y at the playhead, auto-key when
 * animated) and place the anchor on a 3x3 grid of the content box. Anchor presets do NOT compensate position, so the
 * object may visibly shift; this matches editing the anchor value by hand in Attributes.
 */
@Composable
fun AnchorAlignPanel(c: EditorController, modifier: Modifier = Modifier) {
    val s = c.session
    @Suppress("UNUSED_VARIABLE") val v = c.timeline
    val owner = c.ownerOfSelection()?.takeIf { it is AnimationOwner.ClipOwner && s.exists(it) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp).testTag("anchor-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (owner == null) {
            Text("Select a clip to align it or move its anchor.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val clipId = (owner as AnimationOwner.ClipOwner).clipId
        val item = s.frame().items.firstOrNull { it.clip.id == clipId }
        val b = item?.let { clipBounds(c, it) }
        Text("Align to canvas", style = MaterialTheme.typography.labelLarge)
        val cw = s.canvasWidth / 2.0
        val ch = s.canvasHeight / 2.0
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            alignButton(c, owner, b, "align-left", "Left", { -cw - it[0] }, { 0.0 })
            alignButton(c, owner, b, "align-hcenter", "Centre", { -(it[0] + it[2]) / 2 }, { 0.0 })
            alignButton(c, owner, b, "align-right", "Right", { cw - it[2] }, { 0.0 })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            alignButton(c, owner, b, "align-top", "Top", { 0.0 }, { -ch - it[1] })
            alignButton(c, owner, b, "align-vcenter", "Middle", { 0.0 }, { -(it[1] + it[3]) / 2 })
            alignButton(c, owner, b, "align-bottom", "Bottom", { 0.0 }, { ch - it[3] })
        }
        Text("Anchor point", style = MaterialTheme.typography.labelLarge)
        val size = c.contentSizes[clipId]?.let { it.first.toDouble() to it.second.toDouble() }
            ?: item?.let { id.motionlab.engine.timeline.PreviewMath.estimateContentSize(it.clip, it.trackKind, s.canvasWidth) }
        for (row in -1..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in -1..1) {
                    OutlinedButton(
                        enabled = size != null,
                        onClick = {
                            val (w, h) = size ?: return@OutlinedButton
                            val t = s.localTime(owner)
                            c.run {
                                s.execute(
                                    CompositeCommand(
                                        "Set anchor",
                                        listOf(
                                            SetPropertyValue(owner, PropertyNames.ANCHOR_X, t, col * w / 2),
                                            SetPropertyValue(owner, PropertyNames.ANCHOR_Y, t, row * h / 2),
                                        ),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.testTag("anchor-${row + 1}-${col + 1}"),
                    ) { Text(if (row == 0 && col == 0) "●" else "·") }
                }
            }
        }
    }
}

@Composable
private fun alignButton(c: EditorController, owner: AnimationOwner, b: DoubleArray?, tag: String, label: String, dx: (DoubleArray) -> Double, dy: (DoubleArray) -> Double) {
    OutlinedButton(
        enabled = b != null,
        onClick = {
            val bb = b ?: return@OutlinedButton
            val s = c.session
            val t = s.localTime(owner)
            val x = s.valueAtPlayhead(owner, PropertyNames.POSITION_X)
            val y = s.valueAtPlayhead(owner, PropertyNames.POSITION_Y)
            val cmds = ArrayList<id.motionlab.engine.timeline.Command>(2)
            val ddx = dx(bb); val ddy = dy(bb)
            if (ddx != 0.0) cmds.add(SetPropertyValue(owner, PropertyNames.POSITION_X, t, x + ddx))
            if (ddy != 0.0) cmds.add(SetPropertyValue(owner, PropertyNames.POSITION_Y, t, y + ddy))
            if (cmds.isNotEmpty()) c.run { s.execute(CompositeCommand("Align", cmds)) }
        },
        modifier = Modifier.testTag(tag),
    ) { Text(label) }
}
