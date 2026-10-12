package com.example.gpsmapcamera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val mime: String,
)

/** Finds the photos and videos this app saved in Pictures/GPSMapCamera. */
object MediaRepository {
    const val FOLDER = "GPSMapCamera"

    @Suppress("DEPRECATION")
    fun query(context: Context, limit: Int = 0): List<MediaEntry> {
        val files = MediaStore.Files.getContentUri("external")
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            type,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to "%$FOLDER%"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to "%/$FOLDER/%"
        }
        val selection = "($type=$image OR $type=$video) AND ${pathSelection.first}"
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"

        val out = mutableListOf<MediaEntry>()
        context.contentResolver.query(files, projection, selection, arrayOf(pathSelection.second), sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val typeCol = c.getColumnIndexOrThrow(type)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == video
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val mime = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                out += MediaEntry(id, ContentUris.withAppendedId(base, id), isVideo, mime)
                if (limit > 0 && out.size >= limit) break
            }
        }
        return out
    }

    fun latest(context: Context): MediaEntry? =
        runCatching { query(context, 1).firstOrNull() }.getOrNull()

    @Suppress("DEPRECATION")
    fun thumbnail(context: Context, entry: MediaEntry, sizePx: Int): Bitmap? = try {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cr.loadThumbnail(entry.uri, Size(sizePx, sizePx), null)
        } else if (entry.isVideo) {
            MediaStore.Video.Thumbnails.getThumbnail(cr, entry.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(cr, entry.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    } catch (_: Exception) {
        null
    }
}
