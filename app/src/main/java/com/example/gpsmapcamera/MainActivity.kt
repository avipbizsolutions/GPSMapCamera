package com.example.gpsmapcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var infoText: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var thumbnail: ImageView

    private lateinit var locationManager: LocationManager
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var currentLocation: Location? = null
    private var geocodedLocation: Location? = null
    private var placeInfo = PlaceInfo.EMPTY
    private var lastSavedUri: Uri? = null
    private var busy = false

    private val locationListener = LocationListener { onNewLocation(it) }

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasPermission(Manifest.permission.CAMERA)) startCamera()
            else toast(getString(R.string.camera_permission_needed))

            if (hasLocationPermission()) startLocationUpdates()
            else infoText.text = getString(R.string.no_location_permission)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        infoText = findViewById(R.id.infoText)
        shutterButton = findViewById(R.id.shutterButton)
        shareButton = findViewById(R.id.shareButton)
        thumbnail = findViewById(R.id.thumbnail)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        cameraExecutor = Executors.newSingleThreadExecutor()

        shutterButton.setOnClickListener { takePhoto() }
        shareButton.setOnClickListener { shareLastPhoto() }
        thumbnail.setOnClickListener { shareLastPhoto() }

        if (requiredPermissions.all { hasPermission(it) }) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        locationManager.removeUpdates(locationListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                toast(getString(R.string.camera_failed, e.message ?: ""))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        setBusy(true)

        // Snapshot location data on the main thread at the moment of capture.
        val loc = currentLocation
        val place = placeInfo
        val time = Date()
        if (loc == null) toast(getString(R.string.no_fix_warning))

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = try {
                    image.toBitmap()
                } finally {
                    image.close()
                }
                runOnUiThread { processAndSave(bitmap, rotation, loc, place, time) }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.capture_failed, exception.message ?: ""))
                }
            }
        })
    }

    private fun processAndSave(raw: Bitmap, rotation: Int, loc: Location?, place: PlaceInfo, time: Date) {
        lifecycleScope.launch {
            try {
                val (uri, thumb) = withContext(Dispatchers.IO) {
                    val photo = PhotoSaver.prepare(raw, rotation)
                    val map = loc?.let { MapTiles.render(it.latitude, it.longitude, zoom = 16, sizePx = 512) }
                    Stamp.draw(photo, map, loc, place, time)
                    val savedUri = PhotoSaver.save(this@MainActivity, photo, loc, time)
                    val thumbW = 200
                    val small = Bitmap.createScaledBitmap(
                        photo, thumbW, (thumbW.toFloat() * photo.height / photo.width).toInt(), true
                    )
                    photo.recycle()
                    savedUri to small
                }
                lastSavedUri = uri
                thumbnail.setImageBitmap(thumb)
                shareButton.isEnabled = true
                toast(getString(R.string.saved))
            } catch (e: Exception) {
                toast(getString(R.string.save_failed, e.message ?: ""))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        shutterButton.isEnabled = !value
        shutterButton.alpha = if (value) 0.5f else 1f
    }

    private fun shareLastPhoto() {
        val uri = lastSavedUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_photo)))
    }

    // ---------- Location ----------

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            infoText.text = getString(R.string.location_off)
            return
        }
        if (currentLocation == null) infoText.text = getString(R.string.waiting_gps)
        for (p in providers) {
            try {
                locationManager.getLastKnownLocation(p)?.let { onNewLocation(it) }
                locationManager.requestLocationUpdates(p, 2000L, 1f, locationListener, mainLooper)
            } catch (_: SecurityException) {
                // e.g. GPS provider when only coarse location was granted
            }
        }
    }

    private fun onNewLocation(loc: Location) {
        val cur = currentLocation
        val isBetter = cur == null ||
            loc.time - cur.time > 10_000 ||
            loc.accuracy <= cur.accuracy
        if (!isBetter) return

        currentLocation = loc
        updateInfoText()

        val g = geocodedLocation
        if (g == null || g.distanceTo(loc) > 30f) {
            geocodedLocation = loc
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { Geocoding.lookup(this@MainActivity, loc) }
                if (info != null) {
                    placeInfo = info
                    updateInfoText()
                } else {
                    geocodedLocation = null // retry on a later fix (e.g. once online)
                }
            }
        }
    }

    private fun updateInfoText() {
        val loc = currentLocation ?: return
        infoText.text = buildString {
            if (placeInfo.address.isNotBlank()) appendLine(placeInfo.address)
            append(Stamp.formatLatLon(loc))
            append("   ±${loc.accuracy.roundToInt()} m")
        }
    }

    // ---------- Helpers ----------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
