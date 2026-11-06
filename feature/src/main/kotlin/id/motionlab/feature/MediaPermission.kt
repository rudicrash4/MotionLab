package id.motionlab.feature

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import id.motionlab.core.resource.MediaAccessState
import id.motionlab.core.resource.MediaPermissionPolicy
import id.motionlab.core.resource.MediaType

/** Current access for one media category plus the two actions the UI may offer. */
class MediaAccessUi(val state: MediaAccessState, val request: () -> Unit, val openSettings: () -> Unit)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Runtime-permission state for browsing [type] on this device, using the rules in [MediaPermissionPolicy].
 * Nothing is requested here: [MediaAccessUi.request] shows the system dialog, and the UI calls it only after the user taps
 * "Allow access" (so the dialog is always preceded by the explanation and never repeats on its own). The state is re-read
 * after the dialog and when the app resumes (e.g. back from system Settings).
 */
@Composable
fun rememberMediaAccess(type: MediaType?): MediaAccessUi {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val prefs = remember(context) { context.getSharedPreferences("ml_media_permissions", Context.MODE_PRIVATE) }
    val sdk = Build.VERSION.SDK_INT
    var tick by remember { mutableIntStateOf(0) }
    val requestTypes: Set<MediaType> = when {
        type == null -> emptySet()
        type.isVisual -> setOf(MediaType.IMAGES, MediaType.VIDEO) // one dialog covers photos and videos (and Android 14 partial access)
        else -> setOf(MediaType.AUDIO)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().apply { requestTypes.forEach { putBoolean("asked_${it.name}", true) } }.apply()
        tick++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val state = if (type == null) MediaAccessState.FULL else remember(type, tick) {
        val granted = MediaPermissionPolicy.ALL.filterTo(HashSet()) { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val asked = prefs.getBoolean("asked_${type.name}", false)
        val rationale = activity != null && MediaPermissionPolicy.permissionsFor(sdk, setOf(type)).any { activity.shouldShowRequestPermissionRationale(it) }
        MediaPermissionPolicy.accessFor(sdk, type, granted, asked, rationale)
    }
    return MediaAccessUi(
        state = state,
        request = { launcher.launch(MediaPermissionPolicy.permissionsFor(sdk, requestTypes).toTypedArray()) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}
