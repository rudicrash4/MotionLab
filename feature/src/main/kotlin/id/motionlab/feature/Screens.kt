package id.motionlab.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.motionlab.core.common.MlException
import id.motionlab.engine.media.MediaCapabilities
import id.motionlab.engine.rendering.RenderThread
import id.motionlab.nativecore.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MotionLabRoot(services: AppServices) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Projects", "Render test", "Diagnostics")
    MaterialTheme {
        Scaffold { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    titles.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> ProjectsScreen(services)
                    1 -> RenderTestScreen()
                    else -> DiagnosticsScreen(services)
                }
            }
        }
    }
}

@Composable
fun ProjectsScreen(services: AppServices) {
    val repository = remember(services) { ProjectRepository(services.database.projectDao(), services.projectsDir) }
    val projects by remember(services) { services.database.projectDao().observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.create(name) }
                        name = ""
                        error = null
                    } catch (e: MlException) {
                        error = e.message
                    }
                }
            }) { Text("Create") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (projects.isEmpty()) Text("No projects yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p.width}x${p.height} @ ${p.frameRateNumerator}/${p.frameRateDenominator} fps")
                    }
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { repository.delete(p.id) } }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun RenderTestScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A textured quad rendered by RenderThread (EGL + GLES) onto a SurfaceView.")
        AndroidView(factory = { RenderDemoView(it) }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
fun DiagnosticsScreen(services: AppServices) {
    var lines by remember { mutableStateOf<List<String>>(listOf("Collecting...")) }
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.Default) { collectDiagnostics(services) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(lines) { Text(it) }
    }
}

private fun collectDiagnostics(services: AppServices): List<String> {
    val out = ArrayList<String>()
    val report = services.initReport
    out += "Modules initialized: ${report.initialized.joinToString().ifEmpty { "none" }}"
    report.failed.forEach { (n, e) -> out += "Module FAILED: $n (${e.code}: ${e.message})" }
    report.skipped.forEach { (n, why) -> out += "Module skipped: $n ($why)" }

    out += if (NativeCore.isLoaded) {
        "Native: ${NativeCore.version()}, FFmpeg: " +
            if (NativeCore.ffmpegAvailable()) NativeCore.ffmpegVersion() else "not bundled in this build"
    } else {
        "Native library NOT loaded: ${NativeCore.lastLoadError()?.message}"
    }

    val caps = MediaCapabilities.detect()
    out += "Export encoders: ${caps.supportedExportVideoMimes().joinToString().ifEmpty { "none found" }}"
    out += "Decode H.264: ${caps.canDecode("video/avc")}, HEVC: ${caps.canDecode("video/hevc")}, AV1: ${caps.canDecode("video/av01")}"

    try {
        RenderThread("ml-diag").use { rt ->
            val gpu = rt.call { it.capabilities }
            out += "GPU: ${gpu.renderer} (${gpu.vendor})"
            out += "GL version: ${gpu.version}, max texture: ${gpu.maxTextureSize}"
        }
    } catch (e: MlException) {
        out += "GPU init FAILED: ${e.message}"
    }
    return out
}
