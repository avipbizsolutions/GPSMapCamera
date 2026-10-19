package com.example.gpsmapcamera

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen viewer: swipe left/right through photos and videos, tick the
 * circle (top-right) to select, then Share or Delete.
 * With nothing selected, Share / Delete act on the item on screen.
 */
class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POSITION = "position"
        private const val KEY_POSITION = "position"
    }

    private lateinit var pager: ViewPager2
    private lateinit var counterText: TextView
    private lateinit var selectBox: ImageView
    private lateinit var shareButton: Button
    private lateinit var deleteButton: Button
    private lateinit var topBar: View
    private lateinit var bottomBar: View

    private var items: List<MediaEntry> = emptyList()
    private val adapter = PageAdapter()
    private var playing: PageHolder? = null
    private val deleter = MediaDeleter(this) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        pager = findViewById(R.id.pager)
        counterText = findViewById(R.id.counterText)
        selectBox = findViewById(R.id.viewerSelectBox)
        shareButton = findViewById(R.id.viewerShareButton)
        deleteButton = findViewById(R.id.viewerDeleteButton)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)

        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                stopVideo()
                updateUi()
            }
        })

        findViewById<View>(R.id.viewerBackButton).setOnClickListener { finish() }
        selectBox.setOnClickListener {
            current()?.let { GallerySelection.toggle(it.id) }
            updateUi()
        }
        shareButton.setOnClickListener { MediaShare.share(this, targets()) }
        deleteButton.setOnClickListener { deleter.confirmAndDelete(targets()) }

        val start = savedInstanceState?.getInt(KEY_POSITION) ?: intent.getIntExtra(EXTRA_POSITION, 0)
        load(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, pager.currentItem)
    }

    override fun onPause() {
        super.onPause()
        stopVideo()
    }

    private fun load(position: Int) {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) {
                runCatching { MediaRepository.query(this@MediaViewerActivity) }.getOrDefault(emptyList())
            }
            if (items.isEmpty()) {
                finish()
                return@launch
            }
            GallerySelection.ids.retainAll(items.map { it.id }.toSet())
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
            pager.setCurrentItem(position.coerceIn(0, items.size - 1), false)
            updateUi()
        }
    }

    private fun reload() {
        GallerySelection.ids.clear()
        load(pager.currentItem)
    }

    private fun current(): MediaEntry? = items.getOrNull(pager.currentItem)

    /** Selected items, or the one on screen if nothing is selected. */
    private fun targets(): List<MediaEntry> {
        val selected = items.filter { it.id in GallerySelection.ids }
        return selected.ifEmpty { listOfNotNull(current()) }
    }

    private fun updateUi() {
        val entry = current() ?: return
        counterText.text = getString(R.string.viewer_counter, pager.currentItem + 1, items.size)
        selectBox.setImageResource(
            if (entry.id in GallerySelection.ids) R.drawable.ic_check else R.drawable.ic_select_off
        )
        val n = GallerySelection.ids.size
        shareButton.text = if (n > 0) getString(R.string.share_count, n) else getString(R.string.share)
        deleteButton.text = if (n > 0) getString(R.string.delete_count, n) else getString(R.string.delete)
    }

    private fun toggleBars() {
        val show = topBar.visibility != View.VISIBLE
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun stopVideo() {
        playing?.let {
            it.video.stopPlayback()
            it.video.visibility = View.GONE
            it.image.visibility = View.VISIBLE
            it.play.visibility = View.VISIBLE
        }
        playing = null
    }

    private fun playVideo(holder: PageHolder, entry: MediaEntry) {
        stopVideo()
        playing = holder
        holder.image.visibility = View.GONE
        holder.play.visibility = View.GONE
        holder.video.visibility = View.VISIBLE
        holder.video.setMediaController(MediaController(this).also { it.setAnchorView(holder.video) })
        holder.video.setVideoURI(entry.uri)
        holder.video.setOnCompletionListener { stopVideo() }
        holder.video.start()
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pageImage)
        val video: VideoView = view.findViewById(R.id.pageVideo)
        val play: View = view.findViewById(R.id.pagePlay)
        var boundId = -1L
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_viewer_page, parent, false))

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val entry = items[position]
            holder.boundId = entry.id
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.VISIBLE
            holder.video.visibility = View.GONE
            holder.play.visibility = if (entry.isVideo) View.VISIBLE else View.GONE

            holder.image.setOnClickListener { toggleBars() }
            holder.play.setOnClickListener { playVideo(holder, entry) }

            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            lifecycleScope.launch {
                val bmp: Bitmap? = withContext(Dispatchers.IO) {
                    MediaShare.loadLarge(this@MediaViewerActivity, entry, maxSide)
                }
                if (holder.boundId == entry.id) holder.image.setImageBitmap(bmp)
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            if (playing === holder) stopVideo()
        }
    }
}
