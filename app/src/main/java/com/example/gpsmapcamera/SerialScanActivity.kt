package com.example.gpsmapcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.camera.core.ImageAnalysis
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
 * Scans serial numbers into one list:
 *  - Barcode / QR mode adds every new code it sees automatically.
 *  - Text mode reads printed serial numbers; tap one to add it.
 */
class SerialScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIST = "list_id"
        private val SERIAL_TOKEN = Regex("[A-Z0-9][A-Z0-9/\\-]{6,29}")
    }

    private enum class Mode { BARCODE, TEXT }

    private lateinit var listId: String
    private lateinit var previewView: PreviewView
    private lateinit var countText: TextView
    private lateinit var lastText: TextView
    private lateinit var barcodeTab: TextView
    private lateinit var textTab: TextView
    private lateinit var candidateRow: LinearLayout
    private lateinit var candidateHint: TextView
    private lateinit var torchButton: ImageButton
    private lateinit var itemsView: RecyclerView

    private val adapter = ItemAdapter()
    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var analysisExecutor: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false
    private var tone: ToneGenerator? = null

    @Volatile private var mode = Mode.BARCODE
    private var lastAddedValue = ""
    private var lastAddedAt = 0L
    private var lastCandidateUpdate = 0L
    private var currentCandidates: List<String> = emptyList()

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serial_scan)
        SystemBars.apply(this)

        listId = intent.getStringExtra(EXTRA_LIST) ?: run { finish(); return }
        val list = SerialStore.get(this, listId) ?: run { finish(); return }

        previewView = findViewById(R.id.serialPreview)
        countText = findViewById(R.id.serialCount)
        lastText = findViewById(R.id.serialLast)
        barcodeTab = findViewById(R.id.modeBarcode)
        textTab = findViewById(R.id.modeText)
        candidateRow = findViewById(R.id.candidateRow)
        candidateHint = findViewById(R.id.candidateHint)
        torchButton = findViewById(R.id.torchButton)
        itemsView = findViewById(R.id.serialItems)

        findViewById<TextView>(R.id.serialTitle).text = list.name
        findViewById<View>(R.id.serialBack).setOnClickListener { finish() }
        itemsView.layoutManager = LinearLayoutManager(this)
        itemsView.adapter = adapter

        barcodeTab.setOnClickListener { setMode(Mode.BARCODE) }
        textTab.setOnClickListener { setMode(Mode.TEXT) }
        torchButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchButton.isSelected = torchOn
        }
        findViewById<View>(R.id.addManualButton).setOnClickListener { askManual() }
        findViewById<View>(R.id.copyAllButton).setOnClickListener { copyAll() }
        findViewById<View>(R.id.shareListButton).setOnClickListener { askShare() }

        analysisExecutor = Executors.newSingleThreadExecutor()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
        setMode(Mode.BARCODE)
        refresh()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        barcodeScanner.close()
        textRecognizer.close()
        tone?.release()
    }

    private fun setMode(m: Mode) {
        mode = m
        val active = ContextCompat.getColor(this, R.color.mode_active)
        val inactive = ContextCompat.getColor(this, R.color.mode_inactive)
        barcodeTab.setTextColor(if (m == Mode.BARCODE) active else inactive)
        textTab.setTextColor(if (m == Mode.TEXT) active else inactive)
        candidateRow.removeAllViews()
        currentCandidates = emptyList()
        candidateHint.text = getString(if (m == Mode.BARCODE) R.string.serial_hint_barcode else R.string.serial_hint_text)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera?.cameraControl?.enableTorch(torchOn)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        if (mode == Mode.BARCODE) {
            barcodeScanner.process(input)
                .addOnSuccessListener { codes ->
                    codes.mapNotNull { c -> c.rawValue?.trim()?.takeIf { it.isNotEmpty() }?.let { it to formatName(c.format) } }
                        .forEach { (value, type) -> onDetected(value, type) }
                }
                .addOnCompleteListener { proxy.close() }
        } else {
            textRecognizer.process(input)
                .addOnSuccessListener { result ->
                    val found = LinkedHashSet<String>()
                    for (block in result.textBlocks) for (line in block.lines) {
                        line.text.uppercase().split(' ', ':', ',', ';', '|', '=').forEach { raw ->
                            val t = raw.trim().trim('.', '-', '/')
                            if (SERIAL_TOKEN.matches(t) && t.count { it.isDigit() } >= 4) found += t
                        }
                    }
                    showCandidates(found.take(6))
                }
                .addOnCompleteListener { proxy.close() }
        }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_ITF -> "ITF"
        else -> "Barcode"
    }

    /** Barcode mode: add new codes automatically (each code once). */
    private fun onDetected(value: String, type: String) {
        val now = SystemClock.elapsedRealtime()
        if (value == lastAddedValue && now - lastAddedAt < 2500) return
        lastAddedValue = value
        lastAddedAt = now
        addSerial(value, type, quietIfDuplicate = true)
    }

    private fun showCandidates(list: List<String>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCandidateUpdate < 600 || list == currentCandidates || mode != Mode.TEXT) return
        lastCandidateUpdate = now
        currentCandidates = list
        candidateRow.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()
        for (value in list) {
            val chip = TextView(this).apply {
                text = getString(R.string.serial_add_chip, value)
                setTextColor(0xFF08295F.toInt())
                textSize = 15f
                setBackgroundResource(R.drawable.register_button)
                setPadding(pad, pad / 2, pad, pad / 2)
                setOnClickListener { addSerial(value, "Text", quietIfDuplicate = false) }
            }
            candidateRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = pad / 2 })
        }
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
