package com.example.gpsmapcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
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
 * Grid of the photos and videos this app saved.
 * Tap a picture to open it full screen; tap the small circle to select it.
 * Share / Delete act on the selected items.
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
    private lateinit var deleteButton: Button

    private val adapter = MediaAdapter()
    private val thumbCache = LruCache<Long, Bitmap>(150)
    private var loadedOnce = false
    private val deleter = MediaDeleter(this) {
        GallerySelection.ids.clear()
        thumbCache.evictAll()
        load()
    }

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
        if (savedInstanceState == null) GallerySelection.ids.clear()

        grid = findViewById(R.id.grid)
        emptyText = findViewById(R.id.emptyText)
        hintText = findViewById(R.id.hintText)
        clearButton = findViewById(R.id.clearButton)
        shareButton = findViewById(R.id.shareSelectedButton)
        deleteButton = findViewById(R.id.deleteSelectedButton)

        grid.layoutManager = GridLayoutManager(this, SPAN)
        grid.adapter = adapter

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        clearButton.setOnClickListener {
            GallerySelection.ids.clear()
            adapter.refresh()
        }
        shareButton.setOnClickListener { MediaShare.share(this, adapter.selectedEntries()) }
        deleteButton.setOnClickListener { deleter.confirmAndDelete(adapter.selectedEntries()) }

        val missing = readPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) load() else permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the viewer: pick up selections and deletions made there.
        if (loadedOnce) load()
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
        val count = GallerySelection.ids.size
        hintText.text = if (count == 0) getString(R.string.gallery_hint)
        else getString(R.string.selected_count, count)
        shareButton.isEnabled = count > 0
        shareButton.text = if (count == 0) getString(R.string.share) else getString(R.string.share_count, count)
        deleteButton.isEnabled = count > 0
        deleteButton.text = if (count == 0) getString(R.string.delete) else getString(R.string.delete_count, count)
        clearButton.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val playBadge: View = view.findViewById(R.id.playBadge)
        val selectedOverlay: View = view.findViewById(R.id.selectedOverlay)
        val selectBox: ImageView = view.findViewById(R.id.selectBox)
        var boundId = -1L
    }

    private inner class MediaAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<MediaEntry> = emptyList()

        fun submit(list: List<MediaEntry>) {
            items = list
            GallerySelection.ids.retainAll(list.map { it.id }.toSet())
            refresh()
        }

        @Suppress("NotifyDataSetChanged")
        fun refresh() {
            notifyDataSetChanged()
            updateSelectionUi()
        }

        fun selectedEntries() = items.filter { it.id in GallerySelection.ids }

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
            val isSelected = entry.id in GallerySelection.ids
            holder.selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.selectBox.setImageResource(if (isSelected) R.drawable.ic_check else R.drawable.ic_select_off)

            // Tap the small circle to select; tap the picture to open it.
            holder.selectBox.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                val e = items.getOrNull(pos) ?: return@setOnClickListener
                GallerySelection.toggle(e.id)
                notifyItemChanged(pos)
                updateSelectionUi()
            }
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                startActivity(
                    Intent(this@GalleryActivity, MediaViewerActivity::class.java)
                        .putExtra(MediaViewerActivity.EXTRA_POSITION, pos)
                )
            }
            // Long-press also selects, as in most gallery apps.
            holder.itemView.setOnLongClickListener {
                holder.selectBox.performClick()
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
