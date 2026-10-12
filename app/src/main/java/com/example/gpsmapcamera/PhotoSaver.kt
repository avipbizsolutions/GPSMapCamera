package com.example.gpsmapcamera

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object PhotoSaver {

    /** Rotates upright, caps size to keep memory reasonable, and returns a mutable bitmap. */
    fun prepare(src: Bitmap, rotation: Int): Bitmap {
        val scale = min(1f, 4000f / max(src.width, src.height))
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        if (out.isMutable) return out
        val copy = out.copy(Bitmap.Config.ARGB_8888, true)
        out.recycle()
        return copy
    }

    /** Saves to Pictures/GPSMapCamera in the gallery and writes GPS EXIF tags. */
    fun save(context: Context, bitmap: Bitmap, loc: Location?, time: Date): Uri {
        val resolver = context.contentResolver
        return insert(
            resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            fileName(time, "jpg"), "image/jpeg", time,
        ) { uri, out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.close()
            writeExif(resolver, uri, loc, time)
        }
    }

    /** Copies a finished video file into Pictures/GPSMapCamera in the gallery. */
    fun saveVideo(context: Context, file: File, time: Date): Uri =
        insert(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            fileName(time, "mp4"), "video/mp4", time,
        ) { _, out ->
            file.inputStream().use { it.copyTo(out) }
        }

    private fun fileName(time: Date, ext: String) =
        "GPS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(time) + "." + ext

    private fun insert(
        resolver: ContentResolver,
        collection: Uri,
        name: String,
        mime: String,
        time: Date,
        write: (Uri, OutputStream) -> Unit,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, time.time)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + MediaRepository.FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    MediaRepository.FOLDER
                ).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create gallery entry")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open output stream")
            out.use { write(uri, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun writeExif(resolver: ContentResolver, uri: Uri, loc: Location?, time: Date) {
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (loc != null) exif.setGpsInfo(loc)
                exif.setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(time)
                )
                exif.saveAttributes()
            }
        } catch (_: Exception) {
            // EXIF is a bonus; the visible stamp is already in the image.
        }
    }
}
