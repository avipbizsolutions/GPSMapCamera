package com.example.gpsmapcamera

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Selected items, shared by the gallery grid and the full-screen viewer. */
object GallerySelection {
    val ids = LinkedHashSet<Long>()
    fun toggle(id: Long) {
        if (!ids.remove(id)) ids.add(id)
    }
}

object MediaShare {
    fun share(activity: Activity, entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        val intent = if (entries.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = entries[0].mime
                putExtra(Intent.EXTRA_STREAM, entries[0].uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = when {
                    entries.none { it.isVideo } -> "image/*"
                    entries.all { it.isVideo } -> "video/*"
                    else -> "*/*"
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(entries.map { it.uri }))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share)))
    }

    /** Full-screen-sized picture of a photo, or the first frame of a video. */
    fun loadLarge(activity: Activity, entry: MediaEntry, maxSide: Int): Bitmap? = try {
        if (entry.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(activity, entry.uri)
                val frame = r.getFrameAtTime(0)
                frame?.let { scaleDown(it, maxSide) }
            } finally {
                r.release()
            }
        } else {
            val cr = activity.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            cr.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) {
        null
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val f = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
        if (out !== b) b.recycle()
        return out
    }
}

/**
 * Asks for confirmation, deletes, and handles Android's extra permission
 * screen for files saved by an earlier install of the app.
 * Create it as a field of the activity (it registers for a result).
 */
class MediaDeleter(
    private val activity: AppCompatActivity,
    private val onDone: () -> Unit,
) {
    private var pendingDeleted = 0
    private var pendingNeedsPermission = 0

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                finish(pendingDeleted + pendingNeedsPermission, 0)
            } else {
                finish(pendingDeleted, pendingNeedsPermission)
            }
        }

    fun confirmAndDelete(entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.delete_title, entries.size))
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ -> delete(entries) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(entries: List<MediaEntry>) {
        activity.lifecycleScope.launch {
            val needsPermission = withContext(Dispatchers.IO) {
                val blocked = mutableListOf<MediaEntry>()
                for (e in entries) {
                    try {
                        activity.contentResolver.delete(e.uri, null, null)
                    } catch (_: SecurityException) {
                        blocked += e // saved by an earlier install of the app
                    }
                }
                blocked
            }
            val deleted = entries.size - needsPermission.size
            if (needsPermission.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleted = deleted
                pendingNeedsPermission = needsPermission.size
                try {
                    val request = MediaStore.createDeleteRequest(
                        activity.contentResolver, needsPermission.map { it.uri }
                    )
                    permissionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    return@launch
                } catch (_: Exception) {
                }
            }
            finish(deleted, needsPermission.size)
        }
    }

    private fun finish(deleted: Int, failed: Int) {
        val msg = buildString {
            if (deleted > 0) append(activity.getString(R.string.deleted_count, deleted))
            if (failed > 0) {
                if (isNotEmpty()) append(" · ")
                append(activity.getString(R.string.delete_failed, failed))
            }
        }
        if (msg.isNotEmpty()) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        onDone()
    }
}
