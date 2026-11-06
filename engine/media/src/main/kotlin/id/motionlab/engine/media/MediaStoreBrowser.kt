package id.motionlab.engine.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import id.motionlab.core.common.MlLog
import id.motionlab.core.resource.MediaType

/** One item of the device gallery, read from MediaStore columns only (no file is opened to list it). */
data class DeviceMedia(
    val uri: String,
    val type: MediaType,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mime: String?,
)

/**
 * Lists images, videos and audio of the device with MediaStore, newest first, one page at a time (so a library of tens of
 * thousands of files costs one small query per screenful). Needs the media permission matching the type (on Android 14 a
 * partial grant simply returns the user-selected subset). Blocking: call from a background dispatcher.
 */
class MediaStoreBrowser(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private fun collection(type: MediaType): Uri = when (type) {
        MediaType.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    /** At most [limit] items starting at [offset]. Returns an empty list at the end, or when access is not granted. */
    fun page(type: MediaType, offset: Int, limit: Int): List<DeviceMedia> {
        val base = collection(type)
        val cols = ArrayList<String>().apply {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED); add(MediaStore.MediaColumns.MIME_TYPE)
            if (type != MediaType.AUDIO) { add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) }
            if (type != MediaType.IMAGES) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val selection = if (type == MediaType.AUDIO) "${MediaStore.Audio.Media.DURATION} > 0" else null
        return try {
            val cursor = if (Build.VERSION.SDK_INT >= 26) {
                val args = Bundle().apply {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                }
                resolver.query(base, cols, args, null)
            } else {
                // API 23-25: the provider accepts LIMIT / OFFSET in the sort order.
                resolver.query(base, cols, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit OFFSET $offset")
            }
            val out = ArrayList<DeviceMedia>(limit)
            cursor?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    out += DeviceMedia(
                        uri = ContentUris.withAppendedId(base, id).toString(),
                        type = type,
                        name = c.getString(iName) ?: "media-$id",
                        durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L,
                        width = if (iW >= 0 && !c.isNull(iW)) c.getInt(iW) else 0,
                        height = if (iH >= 0 && !c.isNull(iH)) c.getInt(iH) else 0,
                        sizeBytes = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                        dateAddedSec = if (c.isNull(iDate)) 0L else c.getLong(iDate),
                        mime = c.getString(iMime),
                    )
                }
            }
            out
        } catch (e: SecurityException) {
            MlLog.w("MediaStoreBrowser", "no access to $type: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            MlLog.w("MediaStoreBrowser", "query failed for $type: ${e.message}")
            emptyList()
        }
    }

    /**
     * A small thumbnail (never the full image) produced by the system. Null for audio without cover art or when the system
     * cannot make one; the UI then shows a type icon.
     */
    fun thumbnail(item: DeviceMedia, sidePx: Int): Bitmap? {
        val uri = Uri.parse(item.uri)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.loadThumbnail(uri, Size(sidePx, sidePx), null)
            } else {
                val id = ContentUris.parseId(uri)
                @Suppress("DEPRECATION")
                when (item.type) {
                    MediaType.IMAGES -> MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    MediaType.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    MediaType.AUDIO -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
