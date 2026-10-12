package com.example.gpsmapcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Compass {
    private val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun direction(deg: Float): String = names[(((deg + 22.5f) % 360f) / 45f).toInt() % 8]

    fun label(deg: Float): String = "${deg.roundToInt() % 360}° ${direction(deg)}"

    /**
     * Draws a north arrow: red half points to north. [heading] is where the
     * camera faces (0 = north), so the arrow is rotated by -heading.
     */
    fun drawNorthArrow(c: Canvas, cx: Float, cy: Float, radius: Float, heading: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.06f
        }
        c.drawCircle(cx, cy, radius, bg)
        c.drawCircle(cx, cy, radius, ring)

        c.save()
        c.rotate(-heading, cx, cy)
        val tip = radius * 0.62f
        val half = radius * 0.22f
        val north = Path().apply {
            moveTo(cx, cy - tip); lineTo(cx - half, cy); lineTo(cx + half, cy); close()
        }
        val south = Path().apply {
            moveTo(cx, cy + tip); lineTo(cx - half, cy); lineTo(cx + half, cy); close()
        }
        c.drawPath(north, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) })
        c.drawPath(south, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = radius * 0.34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText("N", cx, cy - tip - radius * 0.04f, letter)
        c.restore()
    }
}

/**
 * Reports the compass direction the camera is pointing at (degrees from north),
 * using the phone's rotation-vector sensor. When the phone is flat, it reports
 * where the top of the phone points instead, like a normal compass.
 */
class HeadingProvider(context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    private var smoothX = 0f
    private var smoothY = 0f
    private var initialised = false

    /** Magnetic declination at the current location, to report true north. */
    var declination = 0f

    var heading: Float? = null
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Camera looks along the phone's -Z axis; world x = east, y = north.
        var east = -rotation[2]
        var north = -rotation[5]
        if (hypot(east, north) < 0.35f) {
            // Pointing mostly up/down: use the top edge of the phone instead.
            east = rotation[1]
            north = rotation[4]
        }
        val angle = atan2(east, north)
        val x = sin(angle)
        val y = cos(angle)
        if (!initialised) {
            smoothX = x; smoothY = y; initialised = true
        } else {
            smoothX += 0.15f * (x - smoothX)
            smoothY += 0.15f * (y - smoothY)
        }
        var deg = Math.toDegrees(atan2(smoothX, smoothY).toDouble()).toFloat() + declination
        deg = ((deg % 360f) + 360f) % 360f
        heading = deg
        onHeading(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Live compass dial: the card turns so its red N always points north. */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var heading = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 1.2f * density
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 4f * density
        canvas.drawCircle(cx, cy, r, bg)
        canvas.drawCircle(cx, cy, r, ring)

        canvas.save()
        canvas.rotate(-heading, cx, cy)
        for (i in 0 until 12) {
            val len = if (i % 3 == 0) r * 0.18f else r * 0.1f
            canvas.drawLine(cx, cy - r, cx, cy - r + len, tick)
            canvas.rotate(30f, cx, cy)
        }
        letter.textSize = r * 0.34f
        val textR = r * 0.58f
        val baseline = letter.textSize * 0.35f
        val labels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        for ((text, a) in labels) {
            canvas.save()
            canvas.rotate(a, cx, cy)
            letter.color = if (text == "N") Color.rgb(255, 82, 82) else Color.WHITE
            canvas.drawText(text, cx, cy - textR + baseline, letter)
            canvas.restore()
        }
        // Red north needle
        val needle = Path().apply {
            moveTo(cx, cy - r * 0.32f)
            lineTo(cx - r * 0.1f, cy)
            lineTo(cx + r * 0.1f, cy)
            close()
        }
        canvas.drawPath(needle, northPaint)
        canvas.restore()

        // Fixed yellow marker = the direction the camera is facing.
        val m = Path().apply {
            moveTo(cx, cy - r + r * 0.22f)
            lineTo(cx - r * 0.12f, cy - r - 2f * density)
            lineTo(cx + r * 0.12f, cy - r - 2f * density)
            close()
        }
        canvas.drawPath(m, marker)
    }
}

/** Rule-of-thirds grid drawn over the camera preview. */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        strokeWidth = 1f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, line)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, line)
        }
    }
}
