package com.example.gpsmapcamera

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serial Scanner home: one list per site / customer. */
class SerialListsActivity : AppCompatActivity() {

    private val adapter = ListAdapter()
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serial_lists)
        SystemBars.apply(this)

        emptyText = findViewById(R.id.serialListsEmpty)
        val rv = findViewById<RecyclerView>(R.id.serialLists)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<View>(R.id.serialListsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.newListButton).setOnClickListener { askNewList() }
    }

    override fun onResume() {
        super.onResume()
        val lists = SerialStore.all(this)
        adapter.submit(lists)
        emptyText.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun askNewList() {
        val input = EditText(this).apply {
            hint = getString(R.string.serial_list_hint)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.serial_new_list)
            .setView(box)
            .setPositiveButton(R.string.serial_start) { _, _ ->
                val name = input.text.toString().trim().ifEmpty {
                    "Site " + SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date())
                }
                val list = SerialStore.create(this, name)
                openList(list.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun openList(id: String) {
        startActivity(Intent(this, SerialScanActivity::class.java).putExtra(SerialScanActivity.EXTRA_LIST, id))
    }

    private fun confirmDelete(list: SerialList) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.serial_delete_list, list.name))
            .setMessage(getString(R.string.serial_delete_list_msg, list.items.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                SerialStore.delete(this, list.id)
                onResume()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.listName)
        val info: TextView = view.findViewById(R.id.listInfo)
    }

    private inner class ListAdapter : RecyclerView.Adapter<Holder>() {
        private var lists: List<SerialList> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(l: List<SerialList>) {
            lists = l
            notifyDataSetChanged()
        }

        override fun getItemCount() = lists.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_serial_list, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val list = lists[position]
            holder.name.text = list.name
            holder.info.text = getString(
                R.string.serial_list_info,
                list.items.size,
                DateFormat.format("dd MMM yyyy, hh:mm a", SerialStore.lastChange(list)).toString(),
            )
            holder.itemView.setOnClickListener { openList(list.id) }
            holder.itemView.setOnLongClickListener { confirmDelete(list); true }
        }
    }
}
