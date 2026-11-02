package com.example.gpsmapcamera

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/** The label photos of one serial list, each with the serial numbers read from it. */
class SerialPhotosActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIST = "list_id"
    }

    private lateinit var listId: String
    private lateinit var emptyText: TextView
    private val adapter = PhotoAdapter()
    private val loader = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serial_photos)
        SystemBars.apply(this)

        listId = intent.getStringExtra(EXTRA_LIST) ?: run { finish(); return }
        emptyText = findViewById(R.id.photosEmpty)
        findViewById<View>(R.id.photosBack).setOnClickListener { finish() }
        findViewById<View>(R.id.photosShare).setOnClickListener { SerialShare.ask(this, listId) }
        val grid = findViewById<RecyclerView>(R.id.photosGrid)
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun refresh() {
        val list = SerialStore.get(this, listId) ?: run { finish(); return }
        findViewById<TextView>(R.id.photosListName).text = list.name
        val photos = SerialStore.photos(list)
        adapter.submit(photos)
        emptyText.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(photo: String) {
        val file = SerialStore.photoFile(this, listId, photo)
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun options(photo: String, items: List<SerialItem>) {
        AlertDialog.Builder(this)
            .setTitle(items.joinToString(", ") { it.value })
            .setItems(arrayOf(getString(R.string.serial_photo_open), getString(R.string.serial_photo_share), getString(R.string.serial_photo_delete))) { _, which ->
                when (which) {
                    0 -> open(photo)
                    1 -> shareOne(photo, items)
                    else -> AlertDialog.Builder(this)
                        .setMessage(R.string.serial_photo_delete_msg)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            SerialStore.removePhoto(this, listId, photo)
                            thumbs.remove(photo)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun shareOne(photo: String, items: List<SerialItem>) {
        val list = SerialStore.get(this, listId) ?: return
        Thread {
            val ok = runCatching {
                val bmp = SerialShare.decode(SerialStore.photoFile(this, listId, photo), 1600)!!
                val dir = File(File(cacheDir, "exports"), "one").apply { deleteRecursively(); mkdirs() }
                val out = File(dir, items.first().value.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")
                out.outputStream().use { SerialShare.stamp(bmp, list, items).compress(Bitmap.CompressFormat.JPEG, 88, it) }
                FileProvider.getUriForFile(this, "$packageName.files", out)
            }.getOrNull()
            runOnUiThread {
                if (ok == null) {
                    Toast.makeText(this, R.string.serial_share_failed, Toast.LENGTH_SHORT).show()
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, ok)
                        putExtra(Intent.EXTRA_TEXT, items.joinToString("\n") { "S/N: ${it.value}" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share)))
                }
            }
        }.start()
    }

    private class Holder(val root: LinearLayout, val image: ImageView, val caption: TextView) : RecyclerView.ViewHolder(root)

    private inner class PhotoAdapter : RecyclerView.Adapter<Holder>() {
        private var photos: List<Pair<String, List<SerialItem>>> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(p: List<Pair<String, List<SerialItem>>>) {
            photos = p
            notifyDataSetChanged()
        }

        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val dp = resources.displayMetrics.density
            val size = (parent.width / 2 - 12 * dp).toInt().coerceAtLeast((120 * dp).toInt())
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF222222.toInt())
            }
            root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (size * 0.75f).toInt()))
            val caption = TextView(parent.context).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.START
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }
            root.addView(caption)
            return Holder(root, image, caption)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (photo, items) = photos[position]
            holder.caption.text = items.joinToString("\n") { it.value }
            holder.root.setOnClickListener { open(photo) }
            holder.root.setOnLongClickListener { options(photo, items); true }
            holder.image.tag = photo
            val cached = thumbs.get(photo)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
                return
            }
            holder.image.setImageDrawable(null)
            loader.execute {
                val bmp = SerialShare.decode(SerialStore.photoFile(this@SerialPhotosActivity, listId, photo), 500) ?: return@execute
                runOnUiThread {
                    thumbs.put(photo, bmp)
                    if (holder.image.tag == photo) holder.image.setImageBitmap(bmp)
                }
            }
        }
    }
}
