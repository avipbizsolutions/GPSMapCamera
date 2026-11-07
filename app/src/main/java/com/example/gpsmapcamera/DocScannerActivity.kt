package com.example.gpsmapcamera

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A PDF saved by the document scanner. */
data class ScanDoc(val name: String, val uri: Uri, val size: Long, val modified: Long)

/** Saves and lists scanned PDFs in Documents/SolarSite Pro. */
object ScanStore {
    const val FOLDER = "SolarSite Pro"
    /** Folder used before the app was renamed; its PDFs are moved to [FOLDER]. */
    private const val OLD_FOLDER = "AVIP Tools"
    @Volatile private var migrated = false

    @Suppress("DEPRECATION")
    private fun legacyDir(folder: String = FOLDER): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), folder)

    /**
     * Moves PDFs from Documents/AVIP Tools to Documents/SolarSite Pro (once per launch until done).
     * Files that cannot be moved (e.g. saved by an earlier install) stay where they are and are still listed.
     */
    @Synchronized
    fun migrateOldFolder(context: Context) {
        if (migrated) return
        val prefs = context.getSharedPreferences("docs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("folder_moved", false)) { migrated = true; return }
        var allMoved = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val files = MediaStore.Files.getContentUri("external")
                val ids = mutableListOf<Long>()
                context.contentResolver.query(
                    files, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
                    arrayOf("${Environment.DIRECTORY_DOCUMENTS}/$OLD_FOLDER%", "application/pdf"), null
                )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
                for (id in ids) {
                    try {
                        val moved = context.contentResolver.update(
                            ContentUris.withAppendedId(files, id),
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/")
                            }, null, null
                        )
                        if (moved <= 0) allMoved = false
                    } catch (_: Exception) {
                        allMoved = false
                    }
                }
            } else {
                val old = legacyDir(OLD_FOLDER)
                val pdfs = old.listFiles { f -> f.name.endsWith(".pdf", true) } ?: emptyArray()
                if (pdfs.isNotEmpty()) {
                    val dir = legacyDir().apply { mkdirs() }
                    val paths = ArrayList<String>()
                    for (f in pdfs) {
                        var target = File(dir, f.name)
                        var n = 2
                        while (target.exists()) target = File(dir, f.name.removeSuffix(".pdf") + " ($n).pdf").also { n++ }
                        if (f.renameTo(target)) { paths += f.absolutePath; paths += target.absolutePath } else allMoved = false
                    }
                    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
                }
                if (allMoved) old.delete() // only removes the folder if it is now empty
            }
        } catch (_: Exception) {
            allMoved = false
        }
        migrated = true
        if (allMoved) prefs.edit().putBoolean("folder_moved", true).apply()
    }

    fun save(context: Context, source: Uri, name: String): Uri {
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Scan" } + ".pdf"
        val cr = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw IllegalStateException("Could not create file")
            try {
                cr.openInputStream(source)!!.use { input ->
                    cr.openOutputStream(uri)!!.use { input.copyTo(it) }
                }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            val dir = legacyDir().apply { mkdirs() }
            var file = File(dir, fileName)
            var n = 2
            while (file.exists()) file = File(dir, fileName.removeSuffix(".pdf") + " ($n).pdf").also { n++ }
            cr.openInputStream(source)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/pdf"), null)
            fileUri(context, file)
        }
    }

    fun list(context: Context): List<ScanDoc> {
        migrateOldFolder(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val pdfs = listOf(legacyDir(), legacyDir(OLD_FOLDER)).flatMap { dir ->
                dir.listFiles()?.filter { f -> f.name.endsWith(".pdf", true) } ?: emptyList()
            }
            return pdfs
                .sortedByDescending { it.lastModified() }
                .map { ScanDoc(it.name.removeSuffix(".pdf"), fileUri(context, it), it.length(), it.lastModified()) }
        }
        val out = mutableListOf<ScanDoc>()
        val files = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // New folder, plus any old-folder PDFs that could not be moved.
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)" +
            " AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val args = arrayOf(
            "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER/%",
            "${Environment.DIRECTORY_DOCUMENTS}/$OLD_FOLDER/%",
            "application/pdf",
        )
        context.contentResolver.query(
            files, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += ScanDoc(
                    (c.getString(1) ?: "Scan").removeSuffix(".pdf"),
                    ContentUris.withAppendedId(files, id),
                    c.getLong(2),
                    c.getLong(3) * 1000,
                )
            }
        }
        return out
    }

    fun delete(context: Context, doc: ScanDoc): Boolean = try {
        if (doc.uri.scheme == "file" || doc.uri.authority?.endsWith(".files") == true) {
            File(legacyDir(), doc.name + ".pdf").delete() || File(legacyDir(OLD_FOLDER), doc.name + ".pdf").delete()
        } else {
            context.contentResolver.delete(doc.uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }

    private fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

/**
 * Document Scanner: multi-page scanning with auto-crop and clean-up
 * (Google's on-device ML Kit scanner), saved as one PDF per document.
 */
class DocScannerActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = DocAdapter()

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scan?.pages?.map { it.imageUri }.orEmpty()
            val pdf = scan?.pdf
            if (pages.isEmpty() && pdf == null) return@registerForActivityResult
            askNameAndSave(pages, pdf?.uri, if (pages.isNotEmpty()) pages.size else pdf!!.pageCount)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docs)
        SystemBars.apply(this)

        list = findViewById(R.id.docList)
        emptyText = findViewById(R.id.docEmpty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.docsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.scanButton).setOnClickListener { startScan() }

        // Android 9 and older need storage permission to save into Documents.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                Toast.makeText(this, R.string.docs_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    private fun askNameAndSave(pageImages: List<Uri>, scannerPdf: Uri?, pages: Int) {
        val input = EditText(this).apply {
            setText("Scan " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date()))
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_name_title, pages))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.docs_save) { _, _ -> save(pageImages, scannerPdf, input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun save(pageImages: List<Uri>, scannerPdf: Uri?, name: String) {
        Toast.makeText(this, R.string.docs_making, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    // Our own compact PDF (under 1.8 MB); the scanner's own PDF only as a fallback.
                    val small = runCatching { CompactPdf.fromPages(this@DocScannerActivity, pageImages) }.getOrNull()
                    val source = small?.let { Uri.fromFile(it) } ?: scannerPdf ?: error("No pages")
                    try {
                        ScanStore.save(this@DocScannerActivity, source, name)
                    } finally {
                        small?.delete()
                    }
                }.getOrNull()
            }
            if (saved == null) {
                Toast.makeText(this@DocScannerActivity, R.string.docs_save_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@DocScannerActivity, R.string.docs_saved, Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) {
                runCatching { ScanStore.list(this@DocScannerActivity) }.getOrDefault(emptyList())
            }
            adapter.submit(docs)
            emptyText.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(doc: ScanDoc) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(doc: ScanDoc) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, doc.uri)
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun confirmDelete(doc: ScanDoc) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_delete_title, doc.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { ScanStore.delete(this@DocScannerActivity, doc) }
                    if (!ok) Toast.makeText(this@DocScannerActivity, R.string.docs_delete_failed, Toast.LENGTH_SHORT).show()
                    load()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class DocHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.docName)
        val info: TextView = view.findViewById(R.id.docInfo)
        val shareBtn: View = view.findViewById(R.id.docShare)
    }

    private inner class DocAdapter : RecyclerView.Adapter<DocHolder>() {
        private var docs: List<ScanDoc> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<ScanDoc>) {
            docs = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = docs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DocHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_doc, parent, false))

        override fun onBindViewHolder(holder: DocHolder, position: Int) {
            val doc = docs[position]
            holder.name.text = doc.name
            holder.info.text = DateFormat.format("dd MMM yyyy, hh:mm a", doc.modified).toString() +
                " · " + Formatter.formatShortFileSize(this@DocScannerActivity, doc.size)
            holder.itemView.setOnClickListener { open(doc) }
            holder.itemView.setOnLongClickListener { confirmDelete(doc); true }
            holder.shareBtn.setOnClickListener { share(doc) }
        }
    }
}
