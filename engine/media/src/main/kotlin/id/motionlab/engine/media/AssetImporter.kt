package id.motionlab.engine.media

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.ExifOrientation
import id.motionlab.core.resource.FontValidator
import id.motionlab.core.resource.MediaClassifier
import id.motionlab.core.resource.MediaUriPolicy
import id.motionlab.core.resource.ResourceKind
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed interface ImportOutcome {
    data class Imported(val asset: AssetRefDto, val note: String? = null) : ImportOutcome
    data class Failed(val name: String, val reason: String) : ImportOutcome
}

/**
 * Imports files the user picked with the system Photo Picker / document picker. Everything goes through
 * [android.content.ContentResolver]; a URI is never treated as a file path.
 *
 * Access strategy per file:
 *  1. try to take a persistable read grant (works for ACTION_OPEN_DOCUMENT URIs), so the project keeps working after a
 *     restart without copying anything;
 *  2. if the provider cannot grant that (Photo Picker URIs are only valid for a while), the file is copied into the app's
 *     private storage and referenced from there, so the project never ends up pointing at a dead URI.
 * Fonts are always copied (validated first) so text layers keep working. The user's original files are never modified or
 * deleted. All methods block: call them off the main thread. [cancelled] is polled while copying.
 */
class AssetImporter(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mediaDir: File get() = File(context.filesDir, "media").also { it.mkdirs() }
    private val fontDir: File get() = File(context.filesDir, "fonts").also { it.mkdirs() }

    private val capabilities: MediaCapabilities by lazy { MediaCapabilities.detect() }

    private class Info(val name: String, val size: Long, val mime: String?)

    private fun queryInfo(uri: Uri): Info {
        var name = uri.lastPathSegment ?: "media"
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
            MlLog.w("AssetImporter", "query failed for $uri: ${e.message}")
        }
        val mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        return Info(name, size, mime)
    }

    /** Gallery items (content://media/external/...) are referenced directly while the media permission lasts; no copy is made. */
    private fun isDirectMediaStore(uri: Uri): Boolean =
        MediaUriPolicy.isMediaStoreItem(uri.scheme, uri.authority, uri.pathSegments.firstOrNull())

    private fun tryPersist(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        if (isDirectMediaStore(uri)) return true
        return try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false // provider offers no persistable grant
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * [expected] is the category the user asked for (e.g. "Import audio"); a file of another category is rejected with a
     * clear message instead of being silently misfiled. Null accepts any supported category.
     */
    fun import(uri: Uri, expected: Set<ResourceKind>? = null, cancelled: () -> Boolean = { false }): ImportOutcome {
        val info = queryInfo(uri)
        val kind = MediaClassifier.classify(info.mime, info.name)
        if (kind == ResourceKind.OTHER) return ImportOutcome.Failed(info.name, "Unsupported file type (${info.mime ?: "unknown"})")
        if (expected != null && kind !in expected) return ImportOutcome.Failed(info.name, "This is ${kind.name.lowercase()}, not ${expected.joinToString("/") { it.name.lowercase() }}")
        return try {
            when (kind) {
                ResourceKind.FONT -> importFont(uri, info)
                else -> importMedia(uri, info, kind, cancelled)
            }
        } catch (e: MlException) {
            ImportOutcome.Failed(info.name, e.message ?: e.code.name)
        } catch (e: SecurityException) {
            ImportOutcome.Failed(info.name, "Access to this file was denied. Pick it again.")
        } catch (e: java.io.FileNotFoundException) {
            ImportOutcome.Failed(info.name, "The file is no longer available")
        } catch (e: java.io.IOException) {
            ImportOutcome.Failed(info.name, "Could not read the file: ${e.message}")
        } catch (e: RuntimeException) {
            ImportOutcome.Failed(info.name, "Unexpected problem: ${e.message}")
        }
    }

    private fun importMedia(uri: Uri, info: Info, kind: ResourceKind, cancelled: () -> Boolean): ImportOutcome {
        var useUri = uri
        var copied: File? = null
        var note: String? = null
        if (!tryPersist(uri)) {
            val ext = info.name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 } ?: "bin"
            val target = File(mediaDir, "${UUID.randomUUID()}.$ext")
            copyTo(uri, target, cancelled)
            copied = target
            useUri = Uri.fromFile(target)
            note = "Copied into app storage (this picker gives only temporary access)"
        }
        try {
            val dto = when (kind) {
                ResourceKind.IMAGE -> probeImage(useUri, info)
                ResourceKind.VIDEO, ResourceKind.AUDIO -> probeAv(useUri, info, kind)
                else -> throw MlException(ErrorCode.UNSUPPORTED, "Unsupported category")
            }
            return ImportOutcome.Imported(dto, note)
        } catch (e: Exception) {
            copied?.delete() // do not leave orphan copies behind a failed import
            throw e
        }
    }

    private fun copyTo(uri: Uri, target: File, cancelled: () -> Boolean) {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("cannot open $uri")
        try {
            input.use { ins ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw MlException(ErrorCode.CANCELLED, "Import cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun probeImage(uri: Uri, info: Info): AssetRefDto {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true } // bounds only: no pixels are loaded
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw java.io.FileNotFoundException("cannot open $uri")
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw MlException(ErrorCode.DECODE, "Not a readable image")
        // Photos taken sideways carry an EXIF orientation: the shown size is the CORRECTED one (the decoder rotates accordingly).
        val swap = ExifOrientation.swapsAxes(readExifOrientation(uri))
        return AssetRefDto(
            id = UUID.randomUUID().toString(), uri = uri.toString(), kind = "IMAGE", displayName = info.name,
            width = if (swap) opts.outHeight else opts.outWidth, height = if (swap) opts.outWidth else opts.outHeight, mimeType = opts.outMimeType ?: info.mime, sizeBytes = info.size, addedAtMs = clock(),
        )
    }

    private fun readExifOrientation(uri: Uri): Int {
        if (android.os.Build.VERSION.SDK_INT < 24) return 1
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            } ?: 1
        } catch (e: Exception) {
            1
        }
    }

    private fun probeAv(uri: Uri, info: Info, kind: ResourceKind): AssetRefDto {
        val md = AndroidMetadataBackend(context).extract(uri.toString())
        val ex = readStreams(uri)
        if (kind == ResourceKind.VIDEO) {
            if (md.video == null) throw MlException(ErrorCode.UNSUPPORTED, "No video track found")
            val vmime = ex.videoMime
            if (vmime != null && !capabilities.canDecode(vmime)) {
                throw MlException(ErrorCode.UNSUPPORTED, "This device cannot decode $vmime video")
            }
        } else {
            if (md.audio == null && ex.audioMime == null) throw MlException(ErrorCode.UNSUPPORTED, "No audio track found")
            val amime = ex.audioMime
            if (amime != null && !capabilities.canDecode(amime)) throw MlException(ErrorCode.UNSUPPORTED, "This device cannot decode $amime audio")
        }
        if (md.durationUs <= 0) throw MlException(ErrorCode.UNSUPPORTED, "The duration could not be read")
        val rotated = md.rotationDegrees == 90 || md.rotationDegrees == 270
        val v = md.video
        return AssetRefDto(
            id = UUID.randomUUID().toString(), uri = uri.toString(), kind = kind.name, displayName = info.name,
            durationUs = md.durationUs,
            width = if (v == null) 0 else if (rotated) v.height else v.width,
            height = if (v == null) 0 else if (rotated) v.width else v.height,
            mimeType = info.mime ?: md.container,
            sampleRate = ex.sampleRate, channels = ex.channels, hasAudio = md.audio != null || ex.audioMime != null,
            sizeBytes = info.size, addedAtMs = clock(),
        )
    }

    /** Stream-level facts MediaMetadataRetriever does not give: codec mime types, sample rate, channel count. */
    private class Streams(val videoMime: String?, val audioMime: String?, val sampleRate: Int, val channels: Int)

    private fun readStreams(uri: Uri): Streams {
        val ex = MediaExtractor()
        try {
            if (uri.scheme == "file") ex.setDataSource(uri.path ?: uri.toString()) else ex.setDataSource(context, uri, null)
            var vm: String? = null
            var am: String? = null
            var rate = 0
            var ch = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vm == null) vm = mime
                if (mime.startsWith("audio/") && am == null) {
                    am = mime
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            return Streams(vm, am, rate, ch)
        } catch (e: Exception) {
            MlLog.w("AssetImporter", "stream probe failed: ${e.message}")
            return Streams(null, null, 0, 0)
        } finally {
            try { ex.release() } catch (_: Exception) { }
        }
    }

    private fun importFont(uri: Uri, info: Info): ImportOutcome {
        val bytes = context.contentResolver.openInputStream(uri)?.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > FontValidator.MAX_FONT_BYTES) throw MlException(ErrorCode.UNSUPPORTED, "Font file is too large")
            }
            out.toByteArray()
        } ?: throw java.io.FileNotFoundException("cannot open $uri")
        val font = FontValidator.validate(bytes) // throws with a readable reason; nothing is stored for an invalid font
        val id = UUID.randomUUID().toString()
        val file = File(fontDir, "$id.${if (font.isOpenType) "otf" else "ttf"}")
        FileOutputStream(file).use { it.write(bytes) }
        return ImportOutcome.Imported(
            AssetRefDto(id, Uri.fromFile(file).toString(), "FONT", info.name, mimeType = info.mime, sizeBytes = bytes.size.toLong(), fontFamily = font.family, addedAtMs = clock()),
        )
    }
}

/** Checks whether the file behind an asset can still be opened. Blocking; call off the main thread. */
object MediaAccess {
    fun isAccessible(context: Context, asset: AssetRefDto): Boolean {
        val uri = Uri.parse(asset.uri)
        return try {
            if (uri.scheme == "file" || uri.scheme == null) {
                File(uri.path ?: asset.uri).let { it.isFile && it.canRead() }
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }
}
