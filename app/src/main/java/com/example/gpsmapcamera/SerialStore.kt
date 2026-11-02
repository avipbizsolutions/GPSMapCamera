package com.example.gpsmapcamera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SerialItem(val value: String, val type: String, val time: Long)

data class SerialList(
    val id: String,
    var name: String,
    val created: Long,
    val items: MutableList<SerialItem> = mutableListOf(),
)

/** Serial-number lists, kept in a small JSON file on the phone. */
object SerialStore {
    private const val FILE = "serial_lists.json"
    private var cache: MutableList<SerialList>? = null

    @Synchronized
    fun all(context: Context): List<SerialList> = load(context).sortedByDescending { lastChange(it) }

    @Synchronized
    fun get(context: Context, id: String): SerialList? = load(context).firstOrNull { it.id == id }

    @Synchronized
    fun create(context: Context, name: String): SerialList {
        val list = SerialList(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        load(context).add(list)
        save(context)
        return list
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        load(context).removeAll { it.id == id }
        save(context)
    }

    /** Adds a serial; returns false if it is already in the list. */
    @Synchronized
    fun add(context: Context, id: String, value: String, type: String): Boolean {
        val list = get(context, id) ?: return false
        val v = value.trim()
        if (v.isEmpty() || list.items.any { it.value.equals(v, ignoreCase = true) }) return false
        list.items.add(SerialItem(v, type, System.currentTimeMillis()))
        save(context)
        return true
    }

    @Synchronized
    fun remove(context: Context, id: String, value: String) {
        get(context, id)?.items?.removeAll { it.value == value }
        save(context)
    }

    fun lastChange(list: SerialList): Long = list.items.maxOfOrNull { it.time } ?: list.created

    /** CSV for Excel / Google Sheets. */
    fun toCsv(list: SerialList): String {
        val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        return buildString {
            append("S.No,Serial number,Type,Scanned at,List\r\n")
            list.items.forEachIndexed { i, it ->
                append("${i + 1},${q(it.value)},${q(it.type)},${q(fmt.format(Date(it.time)))},${q(list.name)}\r\n")
            }
        }
    }

    fun toText(list: SerialList): String = buildString {
        append(list.name).append(" – ").append(list.items.size).append(" serial numbers\n")
        list.items.forEachIndexed { i, it -> append(i + 1).append(". ").append(it.value).append('\n') }
    }

    private fun load(context: Context): MutableList<SerialList> {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val lists = mutableListOf<SerialList>()
        if (file.exists()) {
            runCatching {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val items = mutableListOf<SerialItem>()
                    val ia = o.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until ia.length()) {
                        val it = ia.getJSONObject(j)
                        items += SerialItem(it.getString("v"), it.optString("t"), it.optLong("ts"))
                    }
                    lists += SerialList(o.getString("id"), o.getString("name"), o.optLong("created"), items)
                }
            }
        }
        cache = lists
        return lists
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (l in load(context)) {
            val items = JSONArray()
            l.items.forEach { items.put(JSONObject().put("v", it.value).put("t", it.type).put("ts", it.time)) }
            arr.put(JSONObject().put("id", l.id).put("name", l.name).put("created", l.created).put("items", items))
        }
        val file = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
