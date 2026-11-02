package com.example.gpsmapcamera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ScrollView
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.exifinterface.media.ExifInterface
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scans serial numbers into one list by photo: the user takes a photo of the
 * label (or picks one from the gallery); barcodes, QR codes and printed serial
 * numbers are read from it, and the user ticks / edits which ones to add.
 */
class SerialScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIST = "list_id"
        private val SERIAL_TOKEN = Regex("[A-Z0-9][A-Z0-9/\\-]{5,39}")
        private val LABEL = Regex("\\b(S\\s*/\\s*N|SR\\.?\\s*NO|SL\\.?\\s*NO|SERIAL\\s*(NO|NUMBER|NUM)?|S\\.\\s*NO|SNO|SN)(\\s*[.:#\\-]+\\s*|\\s+)")
        private const val MAX_SIDE = 2400
    }

    /** One number found in the photo. */
    private data class Found(val value: String, val type: String, val score: Int)

    private lateinit var listId: String
    private lateinit var previewView: PreviewView
    private lateinit var countText: TextView
    private lateinit var lastText: TextView
    private lateinit var torchButton: ImageButton
    private lateinit var readingOverlay: View
    private lateinit var itemsView: RecyclerView

    private val adapter = ItemAdapter()
    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var worker: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var tone: ToneGenerator? = null
    private var busy = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) readFromUri(uri)
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serial_scan)
        SystemBars.apply(this)

        listId = intent.getStringExtra(EXTRA_LIST) ?: run { finish(); return }
        val list = SerialStore.get(this, listId) ?: run { finish(); return }

        previewView = findViewById(R.id.serialPreview)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        countText = findViewById(R.id.serialCount)
        lastText = findViewById(R.id.serialLast)
        torchButton = findViewById(R.id.torchButton)
        readingOverlay = findViewById(R.id.readingOverlay)
        itemsView = findViewById(R.id.serialItems)

        findViewById<TextView>(R.id.serialTitle).text = list.name
        findViewById<View>(R.id.serialBack).setOnClickListener { finish() }
        itemsView.layoutManager = LinearLayoutManager(this)
        itemsView.adapter = adapter

        torchButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchButton.isSelected = torchOn
        }
        findViewById<View>(R.id.captureSerialButton).setOnClickListener { capture() }
        findViewById<View>(R.id.pickPhotoButton).setOnClickListener {
            if (!busy) runCatching { pickPhoto.launch("image/*") }
        }
        findViewById<View>(R.id.addManualButton).setOnClickListener { askManual() }
        findViewById<View>(R.id.copyAllButton).setOnClickListener { copyAll() }
        findViewById<View>(R.id.shareListButton).setOnClickListener { askShare() }

        // Tap to focus on the label.
        previewView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }

        worker = Executors.newSingleThreadExecutor()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
        refresh()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::worker.isInitialized) worker.shutdown()
        barcodeScanner.close()
        textRecognizer.close()
        tone?.release()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                camera?.cameraControl?.enableTorch(torchOn)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Takes a photo and reads it. */
    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        setBusy(true)
        ic.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    rotateAndScale(image.toBitmap(), image.imageInfo.rotationDegrees)
                } catch (_: Exception) {
                    null
                } finally {
                    image.close()
                }
                runOnUiThread {
                    if (bitmap == null) failed() else readBitmap(bitmap)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    setBusy(false)
                    Toast.makeText(this@SerialScanActivity,
                        getString(R.string.capture_failed, exception.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun readFromUri(uri: Uri) {
        if (busy) return
        setBusy(true)
        worker.execute {
            val bitmap = runCatching { loadBitmap(uri) }.getOrNull()
            runOnUiThread { if (bitmap == null) failed() else readBitmap(bitmap) }
        }
    }

    private fun failed() {
        setBusy(false)
        Toast.makeText(this, R.string.serial_photo_failed, Toast.LENGTH_SHORT).show()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        readingOverlay.visibility = if (b) View.VISIBLE else View.GONE
    }

    /** Runs barcode and text reading on the photo, then asks what to add. */
    private fun readBitmap(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val found = LinkedHashMap<String, Found>()
        fun put(f: Found) {
            val old = found[f.value]
            if (old == null || f.score > old.score) found[f.value] = f
        }
        barcodeScanner.process(input)
            .addOnSuccessListener { codes ->
                for (c in codes) {
                    val raw = c.rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val type = getString(if (c.format == Barcode.FORMAT_QR_CODE) R.string.serial_type_qr else R.string.serial_type_barcode)
                    if (raw.length <= 40 && !raw.contains('\n')) {
                        put(Found(raw, type, 1000))
                    } else {
                        // Long QR content: offer the serial-like parts inside it.
                        serialTokens(raw).forEach { put(Found(it.first, type, 900 + it.second)) }
                    }
                }
            }
            .addOnCompleteListener {
                textRecognizer.process(input)
                    .addOnSuccessListener { result ->
                        for (block in result.textBlocks) for (line in block.lines) {
                            serialTokens(line.text).forEach {
                                put(Found(it.first, getString(R.string.serial_type_text), it.second))
                            }
                        }
                    }
                    .addOnCompleteListener {
                        setBusy(false)
                        showFound(bitmap, found.values.sortedByDescending { it.score }.take(8))
                    }
            }
    }

    /** Serial-number-like words in one line of text, with a score (higher = more likely). */
    private fun serialTokens(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (rawLine in text.uppercase().split('\n')) {
            val labelled = LABEL.containsMatchIn(rawLine)
            val line = LABEL.replace(rawLine, " ")
            val words = line.split(' ', ':', ',', ';', '|', '=', '(', ')', '[', ']').map { it.trim().trim('.', '-', '/') }
            for (w in words) {
                if (!SERIAL_TOKEN.matches(w)) continue
                val digits = w.count { it.isDigit() }
                if (digits < 4 || w.toSet().size < 3) continue
                out += w to (100 + w.length + digits + if (labelled) 200 else 0)
            }
            // OCR sometimes splits one serial into pieces: try the line without spaces.
            val joined = line.replace(" ", "").trim('.', '-', '/', ':')
            if (words.count { it.isNotEmpty() } in 2..4 && SERIAL_TOKEN.matches(joined) &&
                joined.count { it.isDigit() } >= 6 && out.none { it.first == joined }) {
                out += joined to (60 + joined.length + if (labelled) 200 else 0)
            }
        }
        return out
    }

    /** Shows the photo with the numbers found; the user ticks / edits and adds them. */
    private fun showFound(bitmap: Bitmap, items: List<Found>) {
        val dp = resources.displayMetrics.density
        val existing = SerialStore.get(this, listId)?.items?.map { it.value }?.toSet() ?: emptySet()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        content.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = (180 * dp).toInt()
            setBackgroundColor(0xFF000000.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (180 * dp).toInt()))
        content.addView(TextView(this).apply {
            text = getString(if (items.isEmpty()) R.string.serial_found_none else R.string.serial_found_hint)
            textSize = 13f
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        })

        class Row(val check: CheckBox, val edit: EditText, val type: String)
        val rows = ArrayList<Row>()
        val entries = if (items.isEmpty()) listOf(Found("", getString(R.string.serial_type), 0)) else items
        entries.forEachIndexed { i, f ->
            val inList = f.value in existing
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val check = CheckBox(this).apply {
                isChecked = !inList && (items.isEmpty() || f.score >= 900 || i == 0)
            }
            val edit = EditText(this).apply {
                setText(f.value)
                hint = getString(R.string.serial_manual_hint)
                setSingleLine()
                textSize = 16f
                typeface = android.graphics.Typeface.MONOSPACE
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        if (hasFocus()) check.isChecked = !s.isNullOrBlank()
                    }
                })
            }
            row.addView(check)
            row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (inList) getString(R.string.serial_in_list) else f.type
                textSize = 11f
                setPadding((6 * dp).toInt(), 0, 0, 0)
            })
            content.addView(row)
            rows += Row(check, edit, f.type)
        }

        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle(R.string.serial_found_title)
            .setView(scroll)
            .setPositiveButton(R.string.serial_add_selected) { _, _ ->
                var added = 0
                var last = ""
                for (r in rows) {
                    val v = r.edit.text.toString().trim().uppercase()
                    if (!r.check.isChecked || v.isEmpty()) continue
                    if (SerialStore.add(this, listId, v, r.type)) { added++; last = v }
                }
                if (added > 0) {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    vibrate()
                    lastText.text = if (added == 1) getString(R.string.serial_added, last)
                        else getString(R.string.serial_added_count, added)
                    lastText.setBackgroundResource(R.drawable.rec_background_ok)
                    lastText.visibility = View.VISIBLE
                    refresh()
                }
            }
            .setNeutralButton(R.string.serial_retake, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rotateAndScale(src: Bitmap, rotation: Int): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        if (rotation == 0 && scale == 1f) return src
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        return rotateAndScale(bmp, rotation)
    }

    private fun addSerial(value: String, type: String, quietIfDuplicate: Boolean) {
        val added = SerialStore.add(this, listId, value, type)
        if (added) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            vibrate()
            lastText.text = getString(R.string.serial_added, value)
            lastText.setBackgroundResource(R.drawable.rec_background_ok)
            refresh()
        } else if (!quietIfDuplicate) {
            lastText.text = getString(R.string.serial_duplicate, value)
            lastText.setBackgroundResource(R.drawable.rec_background)
        } else {
            lastText.text = getString(R.string.serial_already, value)
            lastText.setBackgroundResource(R.drawable.chip_background)
        }
        lastText.visibility = View.VISIBLE
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(60, 120))
        } catch (_: Exception) {
            previewView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun refresh() {
        val list = SerialStore.get(this, listId) ?: return
        countText.text = getString(R.string.serial_count, list.items.size)
        adapter.submit(list.items.reversed())
    }

    private fun askManual() {
        val input = EditText(this).apply {
            hint = getString(R.string.serial_manual_hint)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.serial_manual_title)
            .setView(box)
            .setPositiveButton(R.string.serial_add) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) addSerial(v, "Typed", quietIfDuplicate = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun itemOptions(item: SerialItem) {
        AlertDialog.Builder(this)
            .setTitle(item.value)
            .setItems(arrayOf(getString(R.string.serial_copy), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    copy(item.value)
                } else {
                    SerialStore.remove(this, listId, item.value)
                    refresh()
                }
            }
            .show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("serials", text))
        Toast.makeText(this, R.string.serial_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyAll() {
        val list = SerialStore.get(this, listId) ?: return
        if (list.items.isEmpty()) return
        copy(list.items.joinToString("\n") { it.value })
    }

    private fun askShare() {
        val list = SerialStore.get(this, listId) ?: return
        if (list.items.isEmpty()) {
            Toast.makeText(this, R.string.serial_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.serial_share_as)
            .setItems(arrayOf(getString(R.string.serial_share_csv), getString(R.string.serial_share_text))) { _, which ->
                if (which == 0) shareCsv(list) else shareText(list)
            }
            .show()
    }

    private fun shareCsv(list: SerialList) {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val safe = list.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "serials" }
            val file = File(dir, "$safe serials.csv")
            file.writeText("﻿" + SerialStore.toCsv(list)) // BOM so Excel reads it correctly
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "${list.name} – serial numbers")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareText(list: SerialList) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, SerialStore.toText(list))
            putExtra(Intent.EXTRA_SUBJECT, "${list.name} – serial numbers")
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.itemIndex)
        val value: TextView = view.findViewById(R.id.itemValue)
        val type: TextView = view.findViewById(R.id.itemType)
    }

    private inner class ItemAdapter : RecyclerView.Adapter<ItemHolder>() {
        private var items: List<SerialItem> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<SerialItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ItemHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_serial, parent, false))

        override fun onBindViewHolder(holder: ItemHolder, position: Int) {
            val item = items[position]
            holder.index.text = (items.size - position).toString()
            holder.value.text = item.value
            holder.type.text = item.type
            holder.itemView.setOnClickListener { itemOptions(item) }
        }
    }
}
