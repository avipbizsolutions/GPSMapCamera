package com.example.gpsmapcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tilt Meter: lay the phone flat on a roof or panel to read its angle.
 * Shows a bubble level, the direction the top of the phone points, and the
 * recommended panel tilt for the current latitude.
 */
class TiltActivity : AppCompatActivity(), SensorEventListener {

    private companion object {
        const val PREFS = "tilt"
        const val KEY_REF = "zero_ref"
    }

    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private lateinit var level: LevelView
    private lateinit var angleText: TextView
    private lateinit var statusText: TextView
    private lateinit var facingText: TextView
    private lateinit var holdButton: Button
    private lateinit var zeroButton: Button
    private lateinit var headingProvider: HeadingProvider

    private val g = FloatArray(3)
    private var hasReading = false
    private var holding = false
    private var reference: FloatArray? = null // "zero" position, unit vector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tilt)
        SystemBars.apply(this)

        level = findViewById(R.id.levelView)
        angleText = findViewById(R.id.angleText)
        statusText = findViewById(R.id.levelStatus)
        facingText = findViewById(R.id.facingText)
        holdButton = findViewById(R.id.holdButton)
        zeroButton = findViewById(R.id.zeroButton)
        findViewById<View>(R.id.tiltBack).setOnClickListener { finish() }

        sensorManager = getSystemService(SensorManager::class.java)
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) angleText.text = getString(R.string.tilt_no_sensor)

        reference = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_REF, null)
            ?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.toFloatArray()
        updateZeroButton()

        holdButton.setOnClickListener {
            holding = !holding
            holdButton.text = getString(if (holding) R.string.tilt_release else R.string.tilt_hold)
        }
        zeroButton.setOnClickListener {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (reference != null) {
                reference = null
                prefs.edit().remove(KEY_REF).apply()
            } else if (hasReading) {
                val n = norm(g)
                reference = floatArrayOf(g[0] / n, g[1] / n, g[2] / n)
                prefs.edit().putString(KEY_REF, reference!!.joinToString(",")).apply()
            }
            updateZeroButton()
        }

        headingProvider = HeadingProvider(this) { deg ->
            if (!holding) facingText.text = getString(R.string.tilt_facing, Compass.label(deg))
        }

        findViewById<TextView>(R.id.recommendText).text = recommendation()
    }

    override fun onResume() {
        super.onResume()
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        headingProvider.start()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        headingProvider.stop()
    }

    private fun updateZeroButton() {
        zeroButton.text = getString(if (reference != null) R.string.tilt_zero_reset else R.string.tilt_zero)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!hasReading) {
            event.values.copyInto(g, 0, 0, 3)
            hasReading = true
        } else {
            for (i in 0..2) g[i] += 0.15f * (event.values[i] - g[i]) // smooth
        }
        if (holding) return

        val n = norm(g)
        if (n < 1f) return
        val ux = g[0] / n
        val uy = g[1] / n
        val uz = g[2] / n
        val ref = reference ?: floatArrayOf(0f, 0f, 1f)

        // Angle between "up" now and "up" when flat (or at the zero position).
        val dot = (ux * ref[0] + uy * ref[1] + uz * ref[2]).coerceIn(-1f, 1f)
        val angle = Math.toDegrees(acos(dot).toDouble()).toFloat()
        val flatEnough = abs(uz) > 0.5f || reference != null

        angleText.text = if (flatEnough) String.format("%.1f°", angle) else "--"
        statusText.text = getString(
            when {
                !flatEnough -> R.string.tilt_lay_flat
                angle < 0.5f -> R.string.tilt_level
                else -> R.string.tilt_measuring
            }
        )
        level.update(ux - ref[0], uy - ref[1], angle < 0.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** Rule of thumb: a fixed panel collects most over the year at a tilt about equal to the latitude. */
    @Suppress("MissingPermission")
    private fun recommendation(): String {
        val loc = LocationWarmup.location ?: try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        } catch (_: Exception) {
            null
        }
        return if (loc != null) {
            val lat = abs(loc.latitude)
            val facing = if (loc.latitude >= 0) getString(R.string.tilt_south) else getString(R.string.tilt_north)
            getString(R.string.tilt_recommend, lat.roundToInt(), facing, String.format("%.1f", lat))
        } else {
            getString(R.string.tilt_recommend_bihar)
        }
    }
}

/** Round bubble level: the bubble moves to the higher side, turns green when level. */
class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bx = 0f
    private var by = 0f
    private var level = false
    private val density = resources.displayMetrics.density

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(150, 255, 255, 255)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        color = Color.argb(90, 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(x: Float, y: Float, isLevel: Boolean) {
        bx = x
        by = y
        level = isLevel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 4f * density
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r * 0.33f, ring)
        canvas.drawLine(cx - r, cy, cx + r, cy, cross)
        canvas.drawLine(cx, cy - r, cx, cy + r, cross)

        // Full scale = about 30° of tilt.
        val scale = r / 0.5f
        var dx = bx * scale
        var dy = -by * scale
        val bubbleR = r * 0.16f
        val maxD = r - bubbleR
        val d = sqrt(dx * dx + dy * dy)
        if (d > maxD) {
            dx *= maxD / d
            dy *= maxD / d
        }
        bubble.color = if (level) Color.rgb(76, 217, 100) else Color.rgb(255, 213, 79)
        canvas.drawCircle(cx + dx, cy + dy, bubbleR, bubble)
    }
}
