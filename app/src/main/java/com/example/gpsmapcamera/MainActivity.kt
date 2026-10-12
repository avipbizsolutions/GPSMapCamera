package com.example.gpsmapcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
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
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    /** Location/time captured at the moment the shutter is pressed. */
    private data class Snapshot(val loc: Location?, val place: PlaceInfo, val time: Date)

    private lateinit var previewView: PreviewView
    private lateinit var infoText: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var thumbnail: ImageView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var modeBar: View
    private lateinit var recTimer: TextView
    private lateinit var processingView: View
    private lateinit var processingText: TextView

    private lateinit var locationManager: LocationManager
    private lateinit var cameraExecutor: ExecutorService

    private var mode = Mode.PHOTO
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var currentLocation: Location? = null
    private var geocodedLocation: Location? = null
    private var placeInfo = PlaceInfo.EMPTY
    private var lastSaved: MediaEntry? = null
    private var busy = false

    private val locationListener = LocationListener { onNewLocation(it) }

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
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
        photoTab = findViewById(R.id.photoTab)
        videoTab = findViewById(R.id.videoTab)
        modeBar = findViewById(R.id.modeBar)
        recTimer = findViewById(R.id.recTimer)
        processingView = findViewById(R.id.processingView)
        processingText = findViewById(R.id.processingText)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        cameraExecutor = Executors.newSingleThreadExecutor()

        shutterButton.setOnClickListener { onShutter() }
        shareButton.setOnClickListener { shareLast() }
        thumbnail.setOnClickListener {
            if (recording == null) startActivity(Intent(this, GalleryActivity::class.java))
        }
        photoTab.setOnClickListener { setMode(Mode.PHOTO) }
        videoTab.setOnClickListener { setMode(Mode.VIDEO) }
        updateModeUi()

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

    override fun onResume() {
        super.onResume()
        refreshThumbnail()
    }

    override fun onStop() {
        super.onStop()
        locationManager.removeUpdates(locationListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        cameraExecutor.shutdown()
    }

    // ---------- Camera & modes ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        try {
            provider.unbindAll()
            imageCapture = null
            videoCapture = null
            if (mode == Mode.PHOTO) {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } else {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                videoCapture = capture
            }
        } catch (e: Exception) {
            toast(getString(R.string.camera_failed, e.message ?: ""))
        }
    }

    private fun setMode(newMode: Mode) {
        if (newMode == mode || busy || recording != null) return
        mode = newMode
        updateModeUi()
        bindUseCases()
    }

    private fun updateModeUi() {
        val active = ContextCompat.getColor(this, R.color.mode_active)
        val inactive = ContextCompat.getColor(this, R.color.mode_inactive)
        photoTab.setTextColor(if (mode == Mode.PHOTO) active else inactive)
        videoTab.setTextColor(if (mode == Mode.VIDEO) active else inactive)
        shutterButton.setBackgroundResource(
            when {
                recording != null -> R.drawable.shutter_recording
                mode == Mode.VIDEO -> R.drawable.shutter_video
                else -> R.drawable.shutter_button
            }
        )
        shutterButton.contentDescription = getString(
            when {
                recording != null -> R.string.stop_recording
                mode == Mode.VIDEO -> R.string.start_recording
                else -> R.string.take_photo
            }
        )
    }

    private fun onShutter() {
        when {
            mode == Mode.PHOTO -> takePhoto()
            recording == null -> startRecording()
            else -> recording?.stop()
        }
    }

    private fun snapshot(): Snapshot {
        val s = Snapshot(currentLocation, placeInfo, Date())
        if (s.loc == null) toast(getString(R.string.no_fix_warning))
        return s
    }

    // ---------- Photo ----------

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        setBusy(true)
        val snap = snapshot()

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = try {
                    image.toBitmap()
                } finally {
                    image.close()
                }
                runOnUiThread { processPhoto(bitmap, rotation, snap) }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.capture_failed, exception.message ?: ""))
                }
            }
        })
    }

    private fun processPhoto(raw: Bitmap, rotation: Int, snap: Snapshot) {
        lifecycleScope.launch {
            try {
                val (uri, thumb) = withContext(Dispatchers.IO) {
                    val photo = PhotoSaver.prepare(raw, rotation)
                    val map = snap.loc?.let { MapTiles.render(it.latitude, it.longitude, zoom = 16, sizePx = 512) }
                    Stamp.draw(photo, map, snap.loc, snap.place, snap.time)
                    val savedUri = PhotoSaver.save(this@MainActivity, photo, snap.loc, snap.time)
                    val small = Bitmap.createScaledBitmap(
                        photo, 200, (200f * photo.height / photo.width).toInt(), true
                    )
                    photo.recycle()
                    savedUri to small
                }
                onSaved(MediaEntry(ContentUris.parseId(uri), uri, false, "image/jpeg"), thumb)
                toast(getString(R.string.saved))
            } catch (e: Exception) {
                toast(getString(R.string.save_failed, e.message ?: ""))
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Video ----------

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        if (busy) return
        val snap = snapshot()
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")

        var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        } else {
            toast(getString(R.string.no_audio_permission))
        }

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recTimer.text = formatDuration(0)
                    recTimer.visibility = View.VISIBLE
                    modeBar.visibility = View.INVISIBLE
                    updateModeUi()
                }
                is VideoRecordEvent.Status -> {
                    recTimer.text = formatDuration(event.recordingStats.recordedDurationNanos)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recTimer.visibility = View.GONE
                    modeBar.visibility = View.VISIBLE
                    updateModeUi()
                    if (file.exists() && file.length() > 0) {
                        processVideo(file, snap)
                    } else {
                        file.delete()
                        toast(getString(R.string.capture_failed, "error ${event.error}"))
                    }
                }
            }
        }
        updateModeUi()
    }

    private fun processVideo(raw: File, snap: Snapshot) {
        setBusy(true)
        processingText.text = getString(R.string.processing_video)
        processingView.visibility = View.VISIBLE
        lifecycleScope.launch {
            var stamped: File? = null
            try {
                val map = withContext(Dispatchers.IO) {
                    snap.loc?.let { MapTiles.render(it.latitude, it.longitude, zoom = 16, sizePx = 512) }
                }
                stamped = VideoStamper.stamp(this@MainActivity, raw, map, snap.loc, snap.place, snap.time)
                if (stamped == null) toast(getString(R.string.video_stamp_failed))
                val finalFile = stamped ?: raw

                val (uri, thumb) = withContext(Dispatchers.IO) {
                    PhotoSaver.saveVideo(this@MainActivity, finalFile, snap.time) to
                        VideoStamper.thumbnail(finalFile, 200)
                }
                onSaved(MediaEntry(ContentUris.parseId(uri), uri, true, "video/mp4"), thumb)
                toast(getString(R.string.saved_video))
            } catch (e: Exception) {
                toast(getString(R.string.save_failed, e.message ?: ""))
            } finally {
                raw.delete()
                stamped?.delete()
                processingView.visibility = View.GONE
                setBusy(false)
            }
        }
    }

    private fun formatDuration(nanos: Long): String {
        val total = TimeUnit.NANOSECONDS.toSeconds(nanos)
        return String.format(Locale.US, "● %02d:%02d", total / 60, total % 60)
    }

    // ---------- Saved media ----------

    private fun onSaved(entry: MediaEntry, thumb: Bitmap?) {
        lastSaved = entry
        if (thumb != null) thumbnail.setImageBitmap(thumb)
        shareButton.isEnabled = true
    }

    private fun refreshThumbnail() {
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) { MediaRepository.latest(this@MainActivity) }
            if (entry == null) {
                // Nothing found (e.g. all deleted, or no read access yet)
                return@launch
            }
            if (entry.uri == lastSaved?.uri) return@launch
            val thumb = withContext(Dispatchers.IO) { MediaRepository.thumbnail(this@MainActivity, entry, 200) }
            onSaved(entry, thumb)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        shutterButton.isEnabled = !value
        shutterButton.alpha = if (value) 0.5f else 1f
    }

    private fun shareLast() {
        val entry = lastSaved ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = entry.mime
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
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
