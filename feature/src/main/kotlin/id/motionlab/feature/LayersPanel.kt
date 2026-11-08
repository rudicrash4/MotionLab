package id.motionlab.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.motionlab.engine.timeline.AddLayer
import id.motionlab.engine.timeline.GroupLayers
import id.motionlab.engine.timeline.Layer
import id.motionlab.engine.timeline.LayerType
import id.motionlab.engine.timeline.RemoveLayer
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetLayerFlags
import id.motionlab.engine.timeline.SetLayerParent
import id.motionlab.engine.timeline.Timeline
import id.motionlab.engine.timeline.UngroupLayer
import id.motionlab.engine.timeline.withLayerGroup

/**
 * The transform hierarchy (layers, groups, null objects). Separate from tracks: a track is a time lane, a layer is a
 * transform parent (use "Parent layer…" in a track's menu to put a track's clips under a layer).
 */
@Composable
fun LayersPanel(c: EditorController, modifier: Modifier = Modifier) {
    val tl = c.timeline
    val selected = c.selection.layerIds
    val single = selected.singleOrNull()
    var parentMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(6.dp).testTag("layers-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { c.run { add(c, LayerType.CONTENT) } }, modifier = Modifier.testTag("layer-add")) { Text("+ Layer") }
            OutlinedButton(onClick = { c.run { add(c, LayerType.NULL) } }, modifier = Modifier.testTag("layer-add-null")) { Text("+ Null") }
            OutlinedButton(onClick = {
                c.run {
                    val g = Layer(c.session.newId("layer"), "Group ${tl.layers.count { it.type == LayerType.GROUP } + 1}", LayerType.GROUP)
                    c.session.execute(GroupLayers(selected.toList(), g))
                }
            }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("layer-group")) { Text("Group") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { single?.let { id -> c.run { c.session.execute(UngroupLayer(id, c.playheadUs)) } } },
                enabled = single != null && tl.findLayer(single)?.type == LayerType.GROUP, modifier = Modifier.testTag("layer-ungroup")) { Text("Ungroup") }
            OutlinedButton(onClick = { single?.let { id -> c.session.select(c.selection.withLayerGroup(tl, id)) } },
                enabled = single != null, modifier = Modifier.testTag("layer-select-group")) { Text("Select group") }
            Box {
                MenuButton("Parent", "layer-parent") { if (single != null) parentMenu = true }
                DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }) {
                    DropdownMenuItem(text = { Text("(none)") }, onClick = {
                        parentMenu = false
                        single?.let { id -> c.run { c.session.execute(SetLayerParent(id, null, atUs = c.playheadUs)) } }
                    })
                    tl.layers.filter { it.id != single }.forEach { l ->
                        DropdownMenuItem(text = { Text(l.name) }, onClick = {
                            parentMenu = false
                            single?.let { id -> c.run { c.session.execute(SetLayerParent(id, l.id, atUs = c.playheadUs)) } }
                        })
                    }
                }
            }
            OutlinedButton(onClick = { single?.let { id -> c.run { c.session.execute(RemoveLayer(id, cascade = false)) } } },
                enabled = single != null, modifier = Modifier.testTag("layer-delete")) { Text("Delete") }
        }
        if (tl.layers.isEmpty()) Text("No layers. Layers let several tracks share one animated transform.", style = MaterialTheme.typography.bodySmall)
        val rows = visibleRows(tl)
        LazyColumn(Modifier.height((rows.size.coerceAtMost(6) * 48).dp.coerceAtLeast(0.dp))) {
            items(rows, key = { it.first.id }) { (layer, depth) ->
                val isSel = layer.id in selected
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { c.session.select(Selection(layerIds = if (c.multiSelect) (if (isSel) selected - layer.id else selected + layer.id) else setOf(layer.id))) }
                        .testTag("layer-row-${layer.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width((depth * 16).dp))
                    val hasChildren = tl.layers.any { it.parentId == layer.id }
                    IconCell(if (!hasChildren) MlIcons.Handle else if (layer.collapsed) MlIcons.ChevronRight else MlIcons.ExpandMore, "Expand ${layer.name}", "layer-collapse-${layer.id}") {
                        c.run { c.session.execute(SetLayerFlags(layer.id, collapsed = !layer.collapsed)) }
                    }
                    Text("${layer.name} (${layer.type.label})", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconCell(if (layer.visible) MlIcons.Visible else MlIcons.Hidden, "Toggle visibility of ${layer.name}", "layer-visible-${layer.id}") {
                        c.run { c.session.execute(SetLayerFlags(layer.id, visible = !layer.visible)) }
                    }
                    IconCell(if (layer.locked) MlIcons.Lock else MlIcons.LockOpen, "Toggle lock of ${layer.name}", "layer-lock-${layer.id}") {
                        c.run { c.session.execute(SetLayerFlags(layer.id, locked = !layer.locked)) }
                    }
                }
            }
        }
    }
}

private fun add(c: EditorController, type: LayerType) {
    val n = c.timeline.layers.count { it.type == type } + 1
    val layer = Layer(c.session.newId("layer"), "${type.label} $n", type)
    c.session.execute(AddLayer(layer))
    c.session.select(Selection(layerIds = setOf(layer.id)))
}

/** Depth-first list of layers whose ancestors are all expanded. */
private fun visibleRows(tl: Timeline): List<Pair<Layer, Int>> {
    val out = ArrayList<Pair<Layer, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (l in tl.layers.filter { it.parentId == parent }) {
            out += l to depth
            if (!l.collapsed) walk(l.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}
