package com.example.gpsmapcamera

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Cable size & voltage drop, and string sizing (panels per string) for the inverter. */
class CableCalcActivity : AppCompatActivity() {

    /** Copper cable sizes (mm²) with an approximate safe current (A) for single-core cable in conduit/air. */
    private val sizes = listOf(
        1.5 to 16.0, 2.5 to 22.0, 4.0 to 30.0, 6.0 to 38.0, 10.0 to 52.0,
        16.0 to 69.0, 25.0 to 90.0, 35.0 to 111.0, 50.0 to 133.0,
    )
    /** Copper resistivity at about 60 °C working temperature (Ω·mm²/m). */
    private val rho = 0.0203

    private lateinit var f: Forms
    private lateinit var kind: () -> Int
    private lateinit var current: EditText
    private lateinit var voltage: EditText
    private lateinit var length: EditText
    private lateinit var maxDrop: EditText
    private lateinit var cableBox: LinearLayout

    private lateinit var voc: EditText
    private lateinit var vmp: EditText
    private lateinit var isc: EditText
    private lateinit var coef: EditText
    private lateinit var tMin: EditText
    private lateinit var tMax: EditText
    private lateinit var invMax: EditText
    private lateinit var mpptMin: EditText
    private lateinit var mpptMax: EditText
    private lateinit var invI: EditText
    private lateinit var stringBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        f = Forms(this, "cable_calc").screen(getString(R.string.tool_cable))

        // ---- Cable size & voltage drop
        f.section(getString(R.string.cable_section))
        kind = f.choice("kind", listOf(getString(R.string.cable_dc), getString(R.string.cable_ac1), getString(R.string.cable_ac3)), 0)
        current = f.number("current", getString(R.string.cable_current), 13.0)
        voltage = f.number("voltage", getString(R.string.cable_voltage), 400.0)
        length = f.number("length", getString(R.string.cable_length), 25.0)
        maxDrop = f.number("max_drop", getString(R.string.cable_max_drop), 2.0)
        cableBox = f.resultBox()
        f.note(getString(R.string.cable_note), size = 12f)

        // ---- String sizing
        f.section(getString(R.string.string_section))
        f.note(getString(R.string.string_panel_hint), size = 12f)
        voc = f.number("voc", getString(R.string.string_voc), 49.9)
        vmp = f.number("vmp", getString(R.string.string_vmp), 41.9)
        isc = f.number("isc", getString(R.string.string_isc), 14.0)
        coef = f.number("coef", getString(R.string.string_coef), -0.28, signed = true)
        tMin = f.number("tmin", getString(R.string.string_tmin), 5.0, signed = true)
        tMax = f.number("tmax", getString(R.string.string_tmax), 70.0)
        f.note(getString(R.string.string_inv_hint), size = 12f)
        invMax = f.number("inv_max", getString(R.string.string_inv_max), 600.0)
        mpptMin = f.number("mppt_min", getString(R.string.string_mppt_min), 90.0)
        mpptMax = f.number("mppt_max", getString(R.string.string_mppt_max), 520.0)
        invI = f.number("inv_i", getString(R.string.string_inv_i), 16.0)
        stringBox = f.resultBox()

        f.onChange { refresh() }
        refresh()
    }

    private fun v(e: EditText) = Forms.value(e)
    private fun d1(x: Double) = String.format(Locale.US, "%.1f", x)

    private fun refresh() {
        refreshCable()
        refreshString()
    }

    private fun refreshCable() {
        cableBox.removeAllViews()
        val i = v(current); val volts = v(voltage); val l = v(length); val limit = v(maxDrop)
        if (i <= 0 || volts <= 0 || l <= 0) { f.line(cableBox, getString(R.string.calc_fill), ""); return }
        // DC and single-phase: current flows out and back (2 × length); three-phase: √3 × length.
        val factor = if (kind() == 2) sqrt(3.0) else 2.0
        var best: Double? = null
        for ((area, amps) in sizes) {
            val dropV = factor * l * i * rho / area
            val dropPct = dropV / volts * 100
            val ok = dropPct <= limit && i <= amps
            if (ok && best == null) best = area
            val note = when {
                i > amps -> getString(R.string.cable_too_hot)
                dropPct > limit -> getString(R.string.cable_too_much_drop)
                else -> "✓"
            }
            f.line(cableBox, "${Forms.fmt(area)} mm² · ${d1(dropV)} V (${d1(dropPct)}%)", note,
                if (ok) "#69F0AE" else "#FF8A80")
        }
        f.line(cableBox, getString(R.string.cable_recommended),
            best?.let { "${Forms.fmt(it)} mm²" } ?: getString(R.string.cable_none), "#FFD54F", big = true)
    }

    private fun refreshString() {
        stringBox.removeAllViews()
        val voc25 = v(voc); val vmp25 = v(vmp); val c = v(coef) / 100
        if (voc25 <= 0 || vmp25 <= 0 || v(invMax) <= 0) { f.line(stringBox, getString(R.string.calc_fill), ""); return }
        // Panel voltage rises on cold mornings and falls when panels are hot.
        val vocCold = voc25 * (1 + c * (v(tMin) - 25))
        val vmpHot = vmp25 * (1 + c * (v(tMax) - 25))
        val vmpCold = vmp25 * (1 + c * (v(tMin) - 25))
        val maxBySafety = floor(v(invMax) / vocCold).toInt()
        val maxByMppt = if (v(mpptMax) > 0) floor(v(mpptMax) / vmpCold).toInt() else maxBySafety
        val maxN = minOf(maxBySafety, maxByMppt)
        val minN = ceil(v(mpptMin) / vmpHot).toInt()
        val strings = if (v(isc) > 0) floor(v(invI) / v(isc)).toInt() else 0

        f.line(stringBox, getString(R.string.string_voc_cold), "${d1(vocCold)} V")
        f.line(stringBox, getString(R.string.string_vmp_hot), "${d1(vmpHot)} V")
        if (maxN >= minN && maxN > 0) {
            f.line(stringBox, getString(R.string.string_range), "$minN – $maxN", "#FFD54F", big = true)
            f.line(stringBox, getString(R.string.string_max_voc, maxN), "${d1(maxN * vocCold)} V")
        } else {
            f.line(stringBox, getString(R.string.string_range), getString(R.string.string_no_fit), "#FF8A80", big = true)
        }
        f.line(stringBox, getString(R.string.string_parallel), if (strings >= 1) "$strings" else getString(R.string.string_current_high),
            if (strings >= 1) "#FFFFFF" else "#FF8A80")
    }
}
