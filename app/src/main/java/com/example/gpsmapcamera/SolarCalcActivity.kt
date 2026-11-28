package com.example.gpsmapcamera

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Solar size & savings: from the monthly bill to kW, panels, generation, subsidy and payback. */
class SolarCalcActivity : AppCompatActivity() {

    /** Subsidy rules (residential, on-grid, PM Surya Ghar). Keep in one place. */
    object Subsidy {
        /** Central: ₹30,000/kW for the first 2 kW, ₹18,000 for the 3rd kW, max ₹78,000. */
        fun central(kw: Double): Double =
            if (kw <= 2.0) kw * 30_000 else min(60_000 + min(kw - 2.0, 1.0) * 18_000, 78_000.0)

        /** Bihar state top-up: ₹10,000/kW up to 2 kW, max ₹20,000. */
        fun bihar(kw: Double): Double = min(kw, 2.0) * 10_000
    }

    data class Result(
        val kw: Double, val panels: Int, val panelW: Double,
        val unitsMonth: Double, val unitsYear: Double,
        val saveMonth: Double, val saveYear: Double,
        val cost: Double, val central: Double, val bihar: Double, val net: Double,
        val paybackYears: Double, val save25: Double, val roofSqFt: Double,
    )

    private lateinit var f: Forms
    private lateinit var name: EditText
    private lateinit var units: EditText
    private lateinit var tariff: EditText
    private lateinit var price: EditText
    private lateinit var panelW: EditText
    private lateinit var genPerDay: EditText
    private lateinit var manualKw: EditText
    private lateinit var box: LinearLayout
    private var last: Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        f = Forms(this, "solar_calc").screen(getString(R.string.tool_calc))
        f.note(getString(R.string.calc_intro))
        name = f.text("customer", getString(R.string.calc_customer_hint))

        f.section(getString(R.string.calc_section_use))
        units = f.number("units", getString(R.string.calc_units), 300.0)
        tariff = f.number("tariff", getString(R.string.calc_tariff), 7.5)

        f.section(getString(R.string.calc_section_system))
        manualKw = f.number("kw", getString(R.string.calc_kw), 0.0)
        panelW = f.number("panel_w", getString(R.string.calc_panel_w), 550.0, decimals = false)
        price = f.number("price", getString(R.string.calc_price), 60_000.0, decimals = false)
        genPerDay = f.number("gen", getString(R.string.calc_gen), 4.0)

        f.section(getString(R.string.calc_section_result))
        box = f.resultBox()
        f.note(getString(R.string.calc_subsidy_note), size = 12f)
        f.button(getString(R.string.calc_share_pdf)) { sharePdf() }

        f.onChange { refresh() }
        refresh()
    }

    private fun compute(): Result? {
        val u = Forms.value(units)
        val t = Forms.value(tariff)
        val w = Forms.value(panelW).takeIf { it >= 100 } ?: return null
        val g = Forms.value(genPerDay).takeIf { it > 0 } ?: return null
        val p = Forms.value(price)
        val want = Forms.value(manualKw)
        val targetKw = if (want > 0) want else u / (g * 30)
        if (targetKw <= 0) return null
        val panels = max(1, ceil(targetKw * 1000 / w - 1e-9).toInt())
        val kw = panels * w / 1000
        val unitsMonth = kw * g * 30
        val unitsYear = unitsMonth * 12
        val saveMonth = min(unitsMonth, if (u > 0) u else unitsMonth) * t
        val saveYear = saveMonth * 12
        val cost = kw * p
        val central = Subsidy.central(kw)
        val bihar = Subsidy.bihar(kw)
        val net = max(0.0, cost - central - bihar)
        // 25 years with 0.5% yearly panel ageing, same tariff.
        var save25 = 0.0
        for (y in 0 until 25) save25 += saveYear * Math.pow(0.995, y.toDouble())
        return Result(kw, panels, w, unitsMonth, unitsYear, saveMonth, saveYear, cost, central, bihar, net,
            if (saveYear > 0) net / saveYear else 0.0, save25, kw * 100)
    }

    private fun refresh() {
        box.removeAllViews()
        val r = compute()
        last = r
        if (r == null) {
            f.line(box, getString(R.string.calc_fill), "")
            return
        }
        val gold = "#FFD54F"
        val green = "#69F0AE"
        f.line(box, getString(R.string.calc_r_size), "${Forms.fmt(round2(r.kw))} kW", gold, big = true)
        f.line(box, getString(R.string.calc_r_panels), "${r.panels} × ${Forms.fmt(r.panelW)} W")
        f.line(box, getString(R.string.calc_r_roof), "~${Math.round(r.roofSqFt)} sq ft")
        f.line(box, getString(R.string.calc_r_gen_month), "${Math.round(r.unitsMonth)} units")
        f.line(box, getString(R.string.calc_r_gen_year), "${Math.round(r.unitsYear)} units")
        f.line(box, getString(R.string.calc_r_save_month), Forms.rupees(r.saveMonth), green)
        f.line(box, getString(R.string.calc_r_save_year), Forms.rupees(r.saveYear), green)
        f.line(box, getString(R.string.calc_r_cost), Forms.rupees(r.cost))
        f.line(box, getString(R.string.calc_r_central), "− " + Forms.rupees(r.central), green)
        f.line(box, getString(R.string.calc_r_bihar), "− " + Forms.rupees(r.bihar), green)
        f.line(box, getString(R.string.calc_r_net), Forms.rupees(r.net), gold, big = true)
        f.line(box, getString(R.string.calc_r_payback),
            getString(R.string.calc_years, String.format(Locale.US, "%.1f", r.paybackYears)))
        f.line(box, getString(R.string.calc_r_25), Forms.rupees(r.save25), green)
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    /** One-page estimate (always in English) to share with the customer. */
    private fun sharePdf() {
        val r = last ?: return
        try {
            val pdf = SimplePdf()
            val navy = Color.rgb(8, 41, 95)
            val grey = Color.rgb(110, 110, 110)
            val line = Color.rgb(210, 216, 224)
            val fr = SimplePdf.Font.REGULAR
            val fb = SimplePdf.Font.BOLD
            val m = 50f
            pdf.newPage()
            var y = 70f
            pdf.text(m, y, "Rooftop Solar Estimate", fb, 22f, navy); y += 22f
            val who = name.text.toString().trim()
            val date = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
            pdf.text(m, y, (if (who.isNotEmpty()) "For: $who   ·   " else "") + "Date: $date", fr, 11f, grey); y += 30f

            fun row(label: String, value: String, bold: Boolean = false) {
                pdf.text(m, y, label, if (bold) fb else fr, 12f)
                pdf.text(545f - pdf.width(value, if (bold) fb else fr, 12f), y, value, if (bold) fb else fr, 12f,
                    if (bold) navy else Color.BLACK)
                y += 6f; pdf.line(m, y, 545f, y, line); y += 16f
            }
            fun head(t: String) { y += 6f; pdf.text(m, y, t, fb, 13f, navy); y += 18f }

            head("Your electricity use")
            row("Monthly consumption", "${Forms.fmt(Forms.value(units))} units")
            row("Tariff", "Rs ${Forms.fmt(Forms.value(tariff))} per unit")
            head("Recommended system")
            row("System size", "${Forms.fmt(round2(r.kw))} kW", bold = true)
            row("Solar panels", "${r.panels} x ${Forms.fmt(r.panelW)} W")
            row("Shadow-free roof area needed", "about ${Math.round(r.roofSqFt)} sq ft")
            row("Expected generation", "${Math.round(r.unitsMonth)} units/month · ${Math.round(r.unitsYear)} units/year")
            head("Savings and cost")
            row("Bill saving", "${rs(r.saveMonth)} per month · ${rs(r.saveYear)} per year")
            row("System cost", rs(r.cost))
            row("PM Surya Ghar central subsidy", "- " + rs(r.central))
            row("Bihar state subsidy", "- " + rs(r.bihar))
            row("Your net cost", rs(r.net), bold = true)
            row("Payback", "about ${String.format(Locale.US, "%.1f", r.paybackYears)} years")
            row("Savings over 25 years", rs(r.save25), bold = true)
            y += 8f
            pdf.text(m, y, "Estimate only. Generation is based on about ${Forms.fmt(Forms.value(genPerDay))} units per kW per day in Bihar.", fr, 9.5f, grey); y += 13f
            pdf.text(m, y, "Subsidy applies to residential on-grid systems under PM Surya Ghar, as per current rules; final amounts are set by the government.", fr, 9.5f, grey); y += 34f

            pdf.text(m, y, "AVIP Solar · AVIP Business Solutions LLP", fb, 13f, navy); y += 17f
            pdf.text(m, y, "Call / WhatsApp: ${getString(R.string.company_phone)} · ${getString(R.string.company_phone2)}", fr, 11f); y += 15f
            pdf.text(m, y, "One stop solution for all your solar requirements", fr, 11f, grey)
            pdf.text(m, 842f - 24f, "Made with SolarSite Pro", fr, 9f, grey)

            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val safe = who.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Customer" }
            val file = File(dir, "Solar estimate - $safe.pdf")
            pdf.writeTo(file)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Rooftop solar estimate")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.serial_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Rupee text for the PDF's standard font (₹ is not in it). */
    private fun rs(v: Double) = Forms.rupees(v).replace("₹", "Rs ")
}
