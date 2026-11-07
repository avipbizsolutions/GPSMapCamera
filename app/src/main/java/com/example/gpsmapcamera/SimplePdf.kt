package com.example.gpsmapcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Small PDF writer for the serial report. Unlike Android's PdfDocument (which stores
 * photos uncompressed), photos are embedded as JPEG, so the file size stays under control.
 * Coordinates are in points from the top-left of an A4 page (595 × 842).
 * Text uses the standard PDF fonts; text they cannot show (e.g. Hindi) is drawn as an image.
 */
class SimplePdf {

    enum class Font(val pdfName: String, val res: String) {
        REGULAR("Helvetica", "F1"), BOLD("Helvetica-Bold", "F2"), MONO("Courier", "F3"),
    }

    val pageWidth = 595f
    val pageHeight = 842f

    private class Img(val data: ByteArray, val w: Int, val h: Int, val gray: Boolean)

    private val pages = ArrayList<StringBuilder>()
    private val pageImages = ArrayList<MutableList<Int>>()
    private val images = ArrayList<Img>()
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)

    val pageCount get() = pages.size

    /** Approximate total size so far (images dominate). */
    val imageBytes get() = images.sumOf { it.data.size.toLong() }

    fun newPage() {
        pages.add(StringBuilder())
        pageImages.add(ArrayList())
    }

    private val cur get() = pages.last()

    private fun y(top: Float) = pageHeight - top
    private fun f(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

    private fun color(c: Int, stroke: Boolean): String {
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        return "${f(r)} ${f(g)} ${f(b)} ${if (stroke) "RG" else "rg"}\n"
    }

    fun width(text: String, font: Font, size: Float): Float {
        if (font == Font.MONO) return text.length * size * 0.6f
        measure.textSize = size
        measure.typeface = if (font == Font.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return measure.measureText(text)
    }

    /** Cuts text with "…" so it fits [maxWidth]. */
    fun fit(text: String, font: Font, size: Float, maxWidth: Float): String {
        if (width(text, font, size) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && width("$t…", font, size) > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    /** Draws text with its baseline at [top] points from the top of the page. */
    fun text(x: Float, top: Float, text: String, font: Font = Font.REGULAR, size: Float = 11f, c: Int = Color.BLACK) {
        val bytes = winAnsi(text)
        if (bytes == null) {
            textAsImage(x, top, text, font, size, c)
            return
        }
        cur.append("BT\n").append(color(c, false))
            .append("/${font.res} ${f(size)} Tf\n${f(x)} ${f(y(top))} Td\n(")
        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            when {
                ch == '('.code || ch == ')'.code || ch == '\\'.code -> cur.append('\\').append(ch.toChar())
                ch in 32..126 -> cur.append(ch.toChar())
                else -> cur.append('\\').append(Integer.toOctalString(ch).padStart(3, '0'))
            }
        }
        cur.append(") Tj\nET\n")
    }

    fun line(x1: Float, top1: Float, x2: Float, top2: Float, c: Int, width: Float = 0.7f) {
        cur.append("q\n").append(color(c, true)).append("${f(width)} w\n")
            .append("${f(x1)} ${f(y(top1))} m ${f(x2)} ${f(y(top2))} l S\nQ\n")
    }

    /** Draws a JPEG image in the box [x, top, w, h]. */
    fun jpeg(data: ByteArray, pxW: Int, pxH: Int, x: Float, top: Float, w: Float, h: Float, gray: Boolean = false) {
        images.add(Img(data, pxW, pxH, gray))
        val index = images.size - 1
        pageImages.last().add(index)
        cur.append("q\n${f(w)} 0 0 ${f(h)} ${f(x)} ${f(y(top + h))} cm\n/Im$index Do\nQ\n")
    }

    /** Draws a bitmap, compressed as JPEG at [quality]. */
    fun bitmap(bmp: Bitmap, x: Float, top: Float, w: Float, h: Float, quality: Int) {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        jpeg(out.toByteArray(), bmp.width, bmp.height, x, top, w, h)
    }

    private fun textAsImage(x: Float, top: Float, text: String, font: Font, size: Float, c: Int) {
        val scale = 4f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * scale
            color = c
            typeface = when (font) {
                Font.BOLD -> Typeface.DEFAULT_BOLD
                Font.MONO -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
        }
        val fm = p.fontMetrics
        val w = (p.measureText(text) + 2).toInt().coerceAtLeast(1)
        val h = (fm.descent - fm.ascent + 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText(text, 1f, -fm.ascent + 1, p)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        jpeg(out.toByteArray(), w, h, x, top + fm.ascent / scale, w / scale, h / scale)
    }

    /** Encodes text for the standard fonts, or null if it has characters they cannot show. */
    private fun winAnsi(text: String): ByteArray? {
        val out = ByteArray(text.length)
        for ((i, ch) in text.withIndex()) {
            out[i] = when {
                ch.code in 32..126 -> ch.code.toByte()
                ch == '·' -> 0xB7.toByte()
                ch == '•' -> 0x95.toByte()
                ch == '–' -> 0x96.toByte()
                ch == '—' -> 0x97.toByte()
                ch == '…' -> 0x85.toByte()
                ch == '°' -> 0xB0.toByte()
                ch == '©' -> 0xA9.toByte()
                ch == '₹' -> return null
                ch.code in 0xA0..0xFF -> ch.code.toByte()
                else -> return null
            }
        }
        return out
    }

    fun writeTo(file: File) = file.outputStream().buffered().use { writeTo(it) }

    private fun writeTo(os: OutputStream) {
        var pos = 0L
        val offsets = ArrayList<Long>()
        fun raw(b: ByteArray) { os.write(b); pos += b.size }
        fun str(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: () -> Unit) { offsets += pos; str("${offsets.size} 0 obj\n"); body(); str("\nendobj\n") }

        // Object numbers: 1 catalog, 2 pages, 3-5 fonts, then images, then page+content pairs.
        val firstImage = 6
        val firstPage = firstImage + images.size
        val pageObj = { i: Int -> firstPage + i * 2 }

        str("%PDF-1.4\n%âãÏÓ\n")
        obj { str("<< /Type /Catalog /Pages 2 0 R >>") }
        obj {
            str("<< /Type /Pages /Count ${pages.size} /Kids [" +
                pages.indices.joinToString(" ") { "${pageObj(it)} 0 R" } + "] >>")
        }
        for (font in Font.values()) obj {
            str("<< /Type /Font /Subtype /Type1 /BaseFont /${font.pdfName} /Encoding /WinAnsiEncoding >>")
        }
        for (img in images) obj {
            str("<< /Type /XObject /Subtype /Image /Width ${img.w} /Height ${img.h} " +
                "/ColorSpace /${if (img.gray) "DeviceGray" else "DeviceRGB"} /BitsPerComponent 8 " +
                "/Filter /DCTDecode /Length ${img.data.size} >>\nstream\n")
            raw(img.data)
            str("\nendstream")
        }
        pages.forEachIndexed { i, content ->
            val contentBytes = content.toString().toByteArray(Charsets.ISO_8859_1)
            obj {
                val xobjects = pageImages[i].joinToString(" ") { "/Im$it ${firstImage + it} 0 R" }
                str("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${pageWidth.toInt()} ${pageHeight.toInt()}] " +
                    "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> /XObject << $xobjects >> >> " +
                    "/Contents ${pageObj(i) + 1} 0 R >>")
            }
            obj {
                str("<< /Length ${contentBytes.size} >>\nstream\n")
                raw(contentBytes)
                str("\nendstream")
            }
        }
        val xref = pos
        str("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) str(String.format(java.util.Locale.US, "%010d 00000 n \n", o))
        str("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }
}
