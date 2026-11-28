package com.example.gpsmapcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Sun position (NOAA formulas): azimuth from true north (clockwise) and elevation, in degrees. */
object SunPosition {
    fun at(lat: Double, lon: Double, utcMillis: Long): DoubleArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        val day = c.get(Calendar.DAY_OF_YEAR)
        val minutes = c.get(Calendar.HOUR_OF_DAY) * 60.0 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
        val g = 2 * Math.PI / 365.0 * (day - 1 + (minutes / 60.0 - 12) / 24)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val tst = minutes + eqTime + 4 * lon
        val ha = Math.toRadians(tst / 4 - 180)
        val la = Math.toRadians(lat)
        val cosZen = (sin(la) * sin(decl) + cos(la) * cos(decl) * cos(ha)).coerceIn(-1.0, 1.0)
        val zen = acos(cosZen)
        val az = Math.toDegrees(atan2(sin(ha), cos(ha) * sin(la) - tan(decl) * cos(la))) + 180
        return doubleArrayOf((az + 360) % 360, 90 - Math.toDegrees(zen))
    }
}

/**
 * Shadow Checker: the camera view with the sun's path drawn on it for the longest day,
 * the equinox, the shortest day and today. Anything the path passes behind will shade
 * the roof at that time of year.
 */
class ShadowCheckActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: SunPathView
    private lateinit var info: TextView
    private var sensorManager: SensorManager? = null
    private val rv = FloatArray(5)
    private var haveRv = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlay = SunPathView(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // Top bar: back + title + info
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(0, 0, px(12), px(8))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(52), px(52)))
        bar.addView(TextView(this).apply {
            text = getString(R.string.tool_shadow)
            setTextColor(Color.WHITE); textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        top.addView(bar)
        info = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD")); textSize = 12.5f
            setPadding(px(14), 0, 0, 0)
            text = getString(R.string.shadow_hint)
        }
        top.addView(info)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Legend + save button
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(px(10), px(8), px(10), px(12))
        }
        val legend = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf(
            getString(R.string.shadow_june) to SunPathView.JUNE,
            getString(R.string.shadow_equinox) to SunPathView.EQUINOX,
            getString(R.string.shadow_december) to SunPathView.DECEMBER,
            getString(R.string.shadow_today) to SunPathView.TODAY,
        ).forEach { (label, color) ->
            legend.addView(TextView(this).apply {
                text = "● $label"
                setTextColor(color); textSize = 12f
                setPadding(px(6), 0, px(6), 0)
            })
        }
        bottom.addView(legend)
        bottom.addView(TextView(this).apply {
            text = getString(R.string.shadow_save)
            setTextColor(Color.WHITE); textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(20), px(10), px(20), px(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1FA855")); cornerRadius = 24 * dp
            }
            setOnClickListener { saveSnapshot() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        SystemBars.apply(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        LocationWarmup.start(this)
        setupLocation()
        overlay.focalFactor = cameraFocalFactor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val s = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) info.text = getString(R.string.shadow_no_sensor)
        else sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        setupLocation()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun setupLocation() {
        var loc: Location? = LocationWarmup.location
        if (loc == null) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            loc = try {
                listOfNotNull(
                    lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                    lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                ).maxByOrNull { it.time }
            } catch (_: SecurityException) { null }
        }
        val lat = loc?.latitude ?: 25.59   // Patna, until a fix arrives
        val lon = loc?.longitude ?: 85.14
        overlay.setPlace(lat, lon, loc != null)
        overlay.declination = GeomagneticField(lat.toFloat(), lon.toFloat(), 50f, System.currentTimeMillis()).declination
        info.text = getString(R.string.shadow_hint) + "\n" +
            (if (loc != null) String.format(Locale.US, "📍 %.4f, %.4f", lat, lon) else getString(R.string.shadow_no_gps))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Focal length divided by the sensor's long side, so the overlay matches the camera's view. */
    private fun cameraFocalFactor(): Float = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val ch = cm.getCameraCharacteristics(id)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        focal / max(size.width, size.height)
    } catch (_: Exception) {
        0.78f // about 65° across the long side
    }

    override fun onSensorChanged(event: SensorEvent) {
        val n = minOf(event.values.size, 5)
        if (!haveRv) {
            for (i in 0 until n) rv[i] = event.values[i]
            haveRv = true
        } else {
            for (i in 0 until n) rv[i] += 0.2f * (event.values[i] - rv[i])
        }
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, rv.copyOf(minOf(n, 4)))
        overlay.setRotation(r)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Saves the camera view with the sun paths to the gallery (Pictures/GPSMapCamera). */
    private fun saveSnapshot() {
        val shot = previewView.bitmap ?: return
        val out = shot.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        overlay.drawPaths(c, out.width, out.height)
        // Caption strip
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = out.width / 30f; color = Color.WHITE }
        val h = p.textSize * 2.8f
        c.drawRect(0f, out.height - h, out.width.toFloat(), out.height.toFloat(), Paint().apply { color = Color.parseColor("#AA000000") })
        c.drawText("Sun path · " + String.format(Locale.US, "%.4f, %.4f", overlay.lat, overlay.lon) +
            " · " + java.text.SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date()),
            p.textSize * 0.6f, out.height - h + p.textSize * 1.2f, p)
        p.textSize *= 0.85f
        var x = p.textSize * 0.6f
        listOf("Jun 21" to SunPathView.JUNE, "Mar/Sep 21" to SunPathView.EQUINOX,
            "Dec 21" to SunPathView.DECEMBER, "Today" to SunPathView.TODAY).forEach { (t, col) ->
            p.color = col
            c.drawText("● $t", x, out.height - p.textSize * 0.7f, p)
            x += p.measureText("● $t") + p.textSize
        }
        try {
            PhotoSaver.save(this, out, LocationWarmup.location, Date(), 90)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.save_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
}

/** Draws the sun's daily paths projected into the camera's view. */
class SunPathView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        val JUNE = Color.parseColor("#FF9800")
        val EQUINOX = Color.parseColor("#FFEB3B")
        val DECEMBER = Color.parseColor("#4FC3F7")
        val TODAY = Color.WHITE
    }

    var lat = 25.59; private set
    var lon = 85.14; private set
    var declination = 0f
    var focalFactor = 0.78f
    private var rot: FloatArray? = null
    /** Each path: list of (azimuth, elevation, hour or -1) samples. */
    private var paths: List<Pair<Int, List<DoubleArray>>> = emptyList()

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density; color = Color.WHITE
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    fun setPlace(lat: Double, lon: Double, @Suppress("UNUSED_PARAMETER") real: Boolean) {
        if (lat == this.lat && lon == this.lon && paths.isNotEmpty()) return
        this.lat = lat; this.lon = lon
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val today = Calendar.getInstance()
        paths = listOf(
            JUNE to dayPath(year, Calendar.JUNE, 21),
            EQUINOX to dayPath(year, Calendar.MARCH, 21),
            DECEMBER to dayPath(year, Calendar.DECEMBER, 21),
            TODAY to dayPath(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH)),
        )
        invalidate()
    }

    private fun dayPath(year: Int, month: Int, day: Int): List<DoubleArray> {
        val c = Calendar.getInstance()
        val out = ArrayList<DoubleArray>()
        var minutes = 4 * 60
        while (minutes <= 20 * 60) {
            c.set(year, month, day, minutes / 60, minutes % 60, 0)
            val p = SunPosition.at(lat, lon, c.timeInMillis)
            if (p[1] > -3) out.add(doubleArrayOf(p[0], p[1], if (minutes % 60 == 0) (minutes / 60).toDouble() else -1.0))
            minutes += 10
        }
        return out
    }

    fun setRotation(r: FloatArray) {
        rot = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPaths(canvas, width, height)
    }

    /** Projects a direction (azimuth from true north, elevation) to screen x/y, or null if behind. */
    private fun project(az: Double, el: Double, w: Int, h: Int): FloatArray? {
        val r = rot ?: return null
        val a = Math.toRadians(az - declination)
        val e = Math.toRadians(el)
        val east = cos(e) * sin(a)
        val north = cos(e) * cos(a)
        val up = sin(e)
        // device = Rᵀ · world
        val dx = r[0] * east + r[3] * north + r[6] * up
        val dy = r[1] * east + r[4] * north + r[7] * up
        val dz = r[2] * east + r[5] * north + r[8] * up
        if (-dz < 0.08) return null
        val longSide = max(h.toFloat(), w * 4f / 3f) // 4:3 camera image filling the view
        val f = focalFactor * longSide
        return floatArrayOf((w / 2f + f * dx / -dz).toFloat(), (h / 2f - f * dy / -dz).toFloat())
    }

    fun drawPaths(canvas: Canvas, w: Int, h: Int) {
        if (rot == null) return
        // Horizon with compass points
        line.color = Color.parseColor("#88FFFFFF"); line.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        val horizon = Path(); var started = false
        for (az in 0..360 step 3) {
            val p = project(az.toDouble(), 0.0, w, h)
            if (p == null) { started = false; continue }
            if (!started) { horizon.moveTo(p[0], p[1]); started = true } else horizon.lineTo(p[0], p[1])
        }
        canvas.drawPath(horizon, line)
        line.pathEffect = null
        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (az, t) ->
            project(az.toDouble(), 0.0, w, h)?.let { canvas.drawText(t, it[0] - 5 * density, it[1] - 6 * density, label) }
        }
        for ((color, pts) in paths) {
            line.color = color
            line.pathEffect = if (color == TODAY) DashPathEffect(floatArrayOf(14f, 10f), 0f) else null
            val path = Path(); var on = false
            for (s in pts) {
                val p = project(s[0], s[1], w, h)
                if (p == null || s[1] < 0) { on = false; continue }
                if (!on) { path.moveTo(p[0], p[1]); on = true } else path.lineTo(p[0], p[1])
            }
            canvas.drawPath(path, line)
            // Hour marks: every hour; labels on June and December paths
            dot.color = color
            for (s in pts) {
                if (s[2] < 0 || s[1] < 0) continue
                val p = project(s[0], s[1], w, h) ?: continue
                canvas.drawCircle(p[0], p[1], 4.5f * density, dot)
                if (color == JUNE || color == DECEMBER) {
                    val hr = s[2].toInt()
                    val t = if (hr == 12) "12" else if (hr > 12) "${hr - 12}pm" else "${hr}am"
                    canvas.drawText(t, p[0] + 6 * density, p[1] - 6 * density, label)
                }
            }
        }
        line.pathEffect = null
    }
}
