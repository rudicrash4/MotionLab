package id.motionlab.feature

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import id.motionlab.core.project.AssetRefDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Previews one asset: image (pinch to zoom), video (Media3 player with seek bar) or audio (play/pause, progress, duration). */
@Composable
fun MediaPreviewDialog(lib: MediaLibraryController, asset: AssetRefDto, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("preview-close")) { Text("Close") } },
        title = { Text(asset.displayName ?: "Preview") },
        text = {
            Box(Modifier.fillMaxWidth().height(320.dp).testTag("media-preview")) {
                when (asset.kind) {
                    "IMAGE" -> ImagePreview(lib, asset)
                    "VIDEO" -> PlayerPreview(asset, video = true)
                    "AUDIO" -> PlayerPreview(asset, video = false)
                    else -> Text("No preview for this kind of file.")
                }
            }
        },
    )
}

@Composable
private fun ImagePreview(lib: MediaLibraryController, asset: AssetRefDto) {
    // Decoded with a sample size (max side 2048), never at full resolution.
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, asset.id) {
        value = withContext(Dispatchers.IO) { lib.bitmaps.load(asset, 0, 2048) { !isActive } }?.asImageBitmap()
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    val b = bmp
    if (b == null) {
        Text("Loading…")
        return
    }
    Image(
        b, asset.displayName, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> zoom = (zoom * z).coerceIn(1f, 6f) } }
            .graphicsLayer { scaleX = zoom; scaleY = zoom },
    )
}

@Composable
private fun PlayerPreview(asset: AssetRefDto, video: Boolean) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(asset.uri)))
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = "This file cannot be played on this device (${e.errorCodeName})"
                }
            })
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    if (error != null) {
        Text(error!!, color = MlColors.Danger, modifier = Modifier.testTag("preview-error"))
        return
    }
    if (video) {
        // PlayerView provides play/pause, seek bar and the position/duration labels.
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
        return
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(asset.durationUs / 1000) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            player.duration.takeIf { it > 0 }?.let { duration = it }
            playing = player.isPlaying
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row {
            TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.testTag("audio-play")) {
                Text(if (playing) "⏸ Pause" else "▶ Play")
            }
            Text("${formatDuration(position * 1000)} / ${formatDuration(duration * 1000)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp).testTag("audio-time"))
        }
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { f -> player.seekTo((f * duration).toLong()); position = player.currentPosition },
            modifier = Modifier.testTag("audio-seek"),
        )
    }
}
