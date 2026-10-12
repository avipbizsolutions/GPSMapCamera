package com.example.gpsmapcamera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grid of the photos and videos this app saved. Tap to select, then Share
 * sends them all at once (WhatsApp, Gmail, Drive…). Long-press opens one.
 */
class GalleryActivity : AppCompatActivity() {

    private companion object {
        const val SPAN = 3
    }

    private lateinit var grid: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var hintText: TextView
    private lateinit var clearButton: TextView
    private lateinit var shareButton: Button

    private val adapter = MediaAdapter()
    private val thumbCache = LruCache<Long, Bitmap>(150)
    private var loadedOnce = false

    private val readPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    // Even if denied, Android 10+ still shows the files this app created itself.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid = findViewById(R.id.grid)
        emptyText = findViewById(R.id.emptyText)
        hintText = findViewById(R.id.hintText)
        clearButton = findViewById(R.id.clearButton)
        shareButton = findViewById(R.id.shareSelectedButton)

        grid.layoutManager = GridLayoutManager(this, SPAN)
        grid.adapter = adapter

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        clearButton.setOnClickListener { adapter.clearSelection() }
        shareButton.setOnClickListener { shareSelected() }

        val missing = readPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) load() else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onRestart() {
        super.onRestart()
        if (loadedOnce) load() // pick up deletions made in another app
    }

    private fun load() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { MediaRepository.query(this@GalleryActivity) }.getOrDefault(emptyList())
            }
            loadedOnce = true
            adapter.submit(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateSelectionUi() {
        val count = adapter.selectedCount()
        hintText.text = if (count == 0) getString(R.string.gallery_hint)
        else getString(R.string.selected_count, count)
        shareButton.isEnabled = count > 0
        shareButton.text = if (count == 0) getString(R.string.share) else getString(R.string.share_count, count)
        clearButton.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun shareSelected() {
        val selected = adapter.selectedEntries()
        if (selected.isEmpty()) return
        val intent = if (selected.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = selected[0].mime
                putExtra(Intent.EXTRA_STREAM, selected[0].uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = when {
                    selected.none { it.isVideo } -> "image/*"
                    selected.all { it.isVideo } -> "video/*"
                    else -> "*/*"
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(selected.map { it.uri }))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun openEntry(entry: MediaEntry) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val playBadge: View = view.findViewById(R.id.playBadge)
        val selectedOverlay: View = view.findViewById(R.id.selectedOverlay)
        val checkIcon: View = view.findViewById(R.id.checkIcon)
        var boundId = -1L
    }

    private inner class MediaAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<MediaEntry> = emptyList()
        private val selected = LinkedHashSet<Long>()

        fun submit(list: List<MediaEntry>) {
            items = list
            selected.retainAll(list.map { it.id }.toSet())
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
            updateSelectionUi()
        }

        fun selectedCount() = selected.size

        fun selectedEntries() = items.filter { it.id in selected }

        fun clearSelection() {
            selected.clear()
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
            updateSelectionUi()
        }

        private fun toggle(position: Int) {
            val entry = items.getOrNull(position) ?: return
            if (!selected.remove(entry.id)) selected.add(entry.id)
            notifyItemChanged(position)
            updateSelectionUi()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
            val width = if (parent.width > 0) parent.width else resources.displayMetrics.widthPixels
            view.layoutParams.height = width / SPAN
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.boundId = entry.id
            holder.playBadge.visibility = if (entry.isVideo) View.VISIBLE else View.GONE
            val isSelected = entry.id in selected
            holder.selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener { toggle(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener {
                items.getOrNull(holder.bindingAdapterPosition)?.let { openEntry(it) }
                true
            }

            val cached = thumbCache.get(entry.id)
            holder.image.setImageBitmap(cached)
            if (cached == null) {
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        MediaRepository.thumbnail(this@GalleryActivity, entry, 320)
                    }
                    if (bmp != null) {
                        thumbCache.put(entry.id, bmp)
                        if (holder.boundId == entry.id) holder.image.setImageBitmap(bmp)
                    }
                }
            }
        }
    }
}
