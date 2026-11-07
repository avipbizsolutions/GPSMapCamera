package com.example.gpsmapcamera

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sharing a serial list: PDF report with photos, photos + Excel file, Excel file, or text. */
object SerialShare {

    private val timeFmt = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US)

    fun ask(activity: Activity, listId: String) {
        val list = SerialStore.get(activity, listId) ?: return
        if (list.items.isEmpty()) {
            Toast.makeText(activity, R.string.serial_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val hasPhotos = SerialStore.photos(list).isNotEmpty()
        val options = buildList {
            if (hasPhotos) add(activity.getString(R.string.serial_share_pdf) to { sharePdf(activity, list) })
            if (hasPhotos) add(activity.getString(R.string.serial_share_photos) to { sharePhotos(activity, list) })
            add(activity.getString(R.string.serial_share_csv) to { shareCsv(activity, list) })
            add(activity.getString(R.string.serial_share_text) to { shareText(activity, list) })
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.serial_share_as)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun exportDir(activity: Activity, sub: String = ""): File =
        File(File(activity.cacheDir, "exports"), sub).apply {
            if (sub.isNotEmpty()) deleteRecursively()
            mkdirs()
        }

    private fun safeName(name: String) =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "serials" }

    private fun uri(activity: Activity, file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)

    private fun subject(list: SerialList) = "${list.name} – serial numbers"

    private fun csvFile(activity: Activity, list: SerialList, dir: File): File =
        File(dir, "${safeName(list.name)} serials.csv").apply {
            writeText("﻿" + SerialStore.toCsv(list)) // BOM so Excel reads it correctly
        }

    fun shareCsv(activity: Activity, list: SerialList) = guard(activity) {
        val file = csvFile(activity, list, exportDir(activity))
        send(activity, Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri(activity, file))
            putExtra(Intent.EXTRA_SUBJECT, subject(list))
        })
    }

    fun shareText(activity: Activity, list: SerialList) = send(activity, Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, SerialStore.toText(list))
        putExtra(Intent.EXTRA_SUBJECT, subject(list))
    })

    /** Each label photo (with its serial numbers printed along the bottom) plus the Excel file. */
    fun sharePhotos(activity: Activity, list: SerialList) = runInBackground(activity) {
        val dir = exportDir(activity, "photos")
        val uris = ArrayList<Uri>()
        uris += uri(activity, csvFile(activity, list, dir))
        SerialStore.photos(list).forEachIndexed { i, (photo, items) ->
            val src = SerialStore.photoFile(activity, list.id, photo)
            val bmp = decode(src, 1600) ?: return@forEachIndexed
            val stamped = stamp(bmp, list, items)
            val name = "${i + 1}_" + safeName(items.first().value) + ".jpg"
            val out = File(dir, name)
            out.outputStream().use { stamped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            uris += uri(activity, out)
        }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, subject(list))
            putExtra(Intent.EXTRA_TEXT, SerialStore.toText(list))
        }
    }

    /** One PDF: the full serial table, then every label photo with its serial numbers. */
    fun sharePdf(activity: Activity, list: SerialList) = runInBackground(activity) {
        val file = File(exportDir(activity), "${safeName(list.name)} serials.pdf")
        writePdf(activity, list, file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri(activity, file))
            putExtra(Intent.EXTRA_SUBJECT, subject(list))
        }
    }

    /** Largest PDF we make; photos are made smaller until the report fits. */
    private const val PDF_LIMIT = 4_800_000L

    /** Photo size steps (longer side in pixels, JPEG quality), best first. */
    private val PDF_STEPS = listOf(1600 to 80, 1400 to 75, 1200 to 70, 1000 to 65, 850 to 60, 700 to 55, 600 to 50, 480 to 45)

    private fun writePdf(activity: Activity, list: SerialList, file: File) {
        val photos = SerialStore.photos(list)
        // Try the best photo quality first; step down until the report fits the limit.
        for ((i, step) in PDF_STEPS.withIndex()) {
            val last = i == PDF_STEPS.lastIndex
            val pdf = buildPdf(activity, list, photos, step.first, step.second, if (last) Long.MAX_VALUE else PDF_LIMIT)
                ?: continue
            pdf.writeTo(file)
            if (file.length() <= PDF_LIMIT || last) return
        }
    }

    /** Builds the report; returns null as soon as the photos alone go over [limit]. */
    private fun buildPdf(
        activity: Activity,
        list: SerialList,
        photos: List<Pair<String, List<SerialItem>>>,
        maxSide: Int,
        quality: Int,
        limit: Long,
    ): SimplePdf? {
        val pdf = SimplePdf()
        val pw = pdf.pageWidth
        val ph = pdf.pageHeight
        val margin = 40f
        val navy = Color.rgb(8, 41, 95)
        val lineColor = Color.rgb(210, 216, 224)
        val fr = SimplePdf.Font.REGULAR
        val fb = SimplePdf.Font.BOLD
        val fm = SimplePdf.Font.MONO
        var y = 0f

        fun newPage() {
            pdf.newPage()
            val n = pdf.pageCount
            pdf.text(margin, ph - 20f, pdf.fit("SolarSite Pro · ${list.name}", fr, 9f, 400f), fr, 9f, Color.GRAY)
            val label = "Page $n"
            pdf.text(pw - margin - pdf.width(label, fr, 9f), ph - 20f, label, fr, 9f, Color.GRAY)
            y = margin
        }

        val photoNo = photos.mapIndexed { i, p -> p.first to i + 1 }.toMap()

        newPage()
        y += 18f
        pdf.text(margin, y, pdf.fit(list.name, fb, 18f, pw - 2 * margin), fb, 18f, navy)
        y += 18f
        pdf.text(margin, y, "${list.items.size} serial numbers · ${photos.size} label photos · " +
            "report made ${timeFmt.format(Date())}", fr, 11f)
        y += 22f

        val cols = floatArrayOf(margin, margin + 36f, margin + 270f, margin + 350f, margin + 470f)
        fun header() {
            listOf("S.No", "Serial number", "Type", "Scanned at", "Photo").forEachIndexed { i, h -> pdf.text(cols[i], y, h, fb, 11f) }
            y += 5f; pdf.line(margin, y, pw - margin, y, lineColor); y += 13f
        }
        header()
        list.items.forEachIndexed { i, it ->
            if (y + 16f > ph - 40f) { newPage(); header() }
            pdf.text(cols[0], y, "${i + 1}", fr, 11f)
            pdf.text(cols[1], y, pdf.fit(it.value, fm, 11f, cols[2] - cols[1] - 6f), fm, 11f)
            pdf.text(cols[2], y, pdf.fit(it.type, fr, 11f, cols[3] - cols[2] - 6f), fr, 11f)
            pdf.text(cols[3], y, timeFmt.format(Date(it.time)), fr, 11f)
            pdf.text(cols[4], y, photoNo[it.photo]?.let { n -> "Photo $n" } ?: "–", fr, 11f)
            y += 4f; pdf.line(margin, y, pw - margin, y, lineColor); y += 12f
        }

        // Photos, two per page where they fit
        val maxW = pw - 2 * margin
        for ((i, entry) in photos.withIndex()) {
            val (photo, items) = entry
            val src = decode(SerialStore.photoFile(activity, list.id, photo), maxSide) ?: continue
            val s = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true) else src
            val maxH = 300f
            val scale = minOf(maxW / bmp.width, maxH / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val textH = 18f + 14f * items.size
            if (i == 0 || y + h + textH + 30f > ph - 40f) newPage()
            pdf.text(margin, y + 12f, "Photo ${i + 1}", fb, 11f)
            y += 20f
            pdf.bitmap(bmp, margin + (maxW - w) / 2, y, w, h, quality)
            if (bmp !== src) bmp.recycle()
            src.recycle()
            if (pdf.imageBytes > limit) return null
            y += h + 14f
            items.forEach { item ->
                pdf.text(margin, y, pdf.fit("• ${item.value}   (${item.type}, ${timeFmt.format(Date(item.time))})", fm, 11f, maxW), fm, 11f)
                y += 14f
            }
            y += 16f
        }
        return pdf
    }

    private fun fit(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** Copy of the photo with a white strip at the bottom listing its serial numbers. */
    fun stamp(src: Bitmap, list: SerialList, items: List<SerialItem>): Bitmap {
        val textSize = (src.width / 28f).coerceIn(22f, 48f)
        val lineH = textSize * 1.3f
        val lines = items.map { "S/N: ${it.value}" } + "${list.name} · ${timeFmt.format(Date(items.first().time))}"
        val strip = (lines.size * lineH + textSize * 0.8f).toInt()
        val out = Bitmap.createBitmap(src.width, src.height + strip, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, null, Rect(0, 0, src.width, src.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; color = Color.rgb(8, 41, 95) }
        var y = src.height + lineH
        lines.forEachIndexed { i, s ->
            p.typeface = if (i < items.size) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.DEFAULT
            p.color = if (i < items.size) Color.rgb(8, 41, 95) else Color.DKGRAY
            c.drawText(s, textSize * 0.6f, y, p)
            y += lineH
        }
        return out
    }

    /** Decodes a photo file scaled down so its longer side is about [maxSide] pixels. */
    fun decode(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun runInBackground(activity: Activity, build: () -> Intent) {
        val wait = Toast.makeText(activity, R.string.serial_preparing, Toast.LENGTH_SHORT)
        wait.show()
        Thread {
            val intent = try { build() } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (intent == null) Toast.makeText(activity, R.string.serial_share_failed, Toast.LENGTH_SHORT).show()
                else send(activity, intent)
            }
        }.start()
    }

    private fun guard(activity: Activity, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun send(activity: Activity, intent: Intent) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share)))
        } catch (_: Exception) {
        }
    }
}
