package com.example.gpsmapcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Builds a scanned-document PDF from the page photos, kept under [LIMIT] bytes. */
object CompactPdf {

    /** 1.8 MB, with a little room to spare. */
    const val LIMIT = 1_780_000L

    /** Page sharpness steps (longer side in pixels, JPEG quality), best first. */
    private val STEPS = listOf(
        2000 to 80, 1700 to 75, 1500 to 70, 1300 to 65, 1150 to 60,
        1000 to 55, 880 to 50, 760 to 45, 660 to 40, 560 to 35,
    )

    /** Returns a temporary PDF file, or null when there are no pages. */
    fun fromPages(context: Context, pages: List<Uri>): File? {
        if (pages.isEmpty()) return null
        val out = File(context.cacheDir, "scan_${System.currentTimeMillis()}.pdf")
        for ((i, step) in STEPS.withIndex()) {
            val last = i == STEPS.lastIndex
            val pdf = build(context, pages, step.first, step.second, if (last) Long.MAX_VALUE else LIMIT) ?: continue
            pdf.writeTo(out)
            if (out.length() <= LIMIT || last) return out
        }
        return out
    }

    /** One page per scan, filling an A4 page; null as soon as the pages go over [limit]. */
    private fun build(context: Context, pages: List<Uri>, maxSide: Int, quality: Int, limit: Long): SimplePdf? {
        val pdf = SimplePdf()
        val margin = 12f
        for (uri in pages) {
            val bmp = decode(context, uri, maxSide) ?: continue
            pdf.newPage()
            val boxW = pdf.pageWidth - 2 * margin
            val boxH = pdf.pageHeight - 2 * margin
            val scale = minOf(boxW / bmp.width, boxH / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            pdf.bitmap(bmp, (pdf.pageWidth - w) / 2, (pdf.pageHeight - h) / 2, w, h, quality)
            bmp.recycle()
            if (pdf.imageBytes > limit) return null
        }
        return if (pdf.pageCount == 0) null else pdf
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = context.contentResolver
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        if (b.outWidth <= 0) return null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        val s = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        if (s == 1f && rotation == 0) return raw
        val m = Matrix().apply { postScale(s, s); postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }
}
