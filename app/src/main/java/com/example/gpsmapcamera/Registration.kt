package com.example.gpsmapcamera

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * One-time device registration. Details are kept on the phone and sent to the
 * AVIP Google Sheet through a Google Apps Script web app. If the phone is
 * offline, the upload is retried on every launch until it succeeds.
 */
object Registration {

    /** Apps Script web app URL ("…/exec"). Empty = not connected yet; uploads wait. */
    private const val ENDPOINT = ""

    /** Must match SHARED_KEY in apps-script/Code.gs. */
    private const val SHARED_KEY = "avip-gpscam-reg-7Q2x"

    private const val PREFS = "registration"
    private const val KEY_DONE = "registered"
    private const val KEY_UPLOADED = "uploaded"
    private const val KEY_DATA = "data"
    private const val KEY_INSTALL_ID = "install_id"

    @Volatile
    private var uploading = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRegistered(context: Context) = prefs(context).getBoolean(KEY_DONE, false)

    fun installId(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    fun save(context: Context, name: String, company: String, mobile: String, consent: Boolean) {
        val data = JSONObject()
            .put("name", name)
            .put("company", company)
            .put("mobile", mobile)
            .put("consent", consent)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("appVersion", Updater.currentVersionName(context))
            .put("installId", installId(context))
        prefs(context).edit()
            .putBoolean(KEY_DONE, true)
            .putBoolean(KEY_UPLOADED, false)
            .putString(KEY_DATA, data.toString())
            .apply()
    }

    /** Sends a pending registration in the background, if there is one. */
    fun uploadPendingAsync(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (!p.getBoolean(KEY_DONE, false) || p.getBoolean(KEY_UPLOADED, false)) return
        if (ENDPOINT.isBlank() || uploading) return
        uploading = true
        Thread {
            try {
                val data = JSONObject(p.getString(KEY_DATA, "{}") ?: "{}")
                    .put("key", SHARED_KEY)
                    .put("appVersion", Updater.currentVersionName(app))
                if (post(data.toString())) p.edit().putBoolean(KEY_UPLOADED, true).apply()
            } catch (_: Exception) {
            } finally {
                uploading = false
            }
        }.start()
    }

    private fun post(body: String): Boolean {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            // Apps Script answers with a redirect to the result; follow it.
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code in 200..299 && text.contains("\"ok\":true")
        } finally {
            conn.disconnect()
        }
    }
}
