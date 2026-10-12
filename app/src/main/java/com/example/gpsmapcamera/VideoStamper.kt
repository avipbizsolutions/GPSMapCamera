package com.example.gpsmapcamera

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.coroutines.resume

/**
 * Burns the GPS stamp into a recorded video using AndroidX Media3 Transformer
 * (free, open source, runs on the phone). Must be called from the main thread.
 */
@OptIn(UnstableApi::class)
object VideoStamper {

    suspend fun stamp(
        context: Context,
        input: File,
        map: Bitmap?,
        loc: Location?,
        place: PlaceInfo,
        time: Date,
    ): File? {
        val size = withContext(Dispatchers.IO) { uprightSize(input) } ?: return null
        val overlayBitmap = withContext(Dispatchers.Default) {
            // Full-frame transparent overlay, so it lines up with the video exactly.
            Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888).also {
                Stamp.draw(it, map, loc, place, time)
            }
        }
        val output = File(context.cacheDir, "stamped_${System.currentTimeMillis()}.mp4")

        return suspendCancellableCoroutine { cont ->
            val overlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val effects = Effects(
                ImmutableList.of(),
                ImmutableList.of<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))),
            )
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(effects)
                .build()

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        output.delete()
                        if (cont.isActive) cont.resume(null)
                    }
                })
                .build()

            transformer.start(item, output.absolutePath)
            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { transformer.cancel() }
            }
        }
    }

    /** Video width/height as it is displayed (rotation applied). */
    private fun uprightSize(file: File): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    fun thumbnail(file: File, widthPx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(0) ?: return null
            val scaled = Bitmap.createScaledBitmap(
                frame, widthPx, (widthPx.toFloat() * frame.height / frame.width).toInt(), true
            )
            if (scaled !== frame) frame.recycle()
            scaled
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
