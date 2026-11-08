package com.example.gpsmapcamera

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Keeps checking for a newer version while the app is in use (every 15 minutes and
 * whenever a screen comes back). When one is found, the app goes to the splash screen,
 * where updating is mandatory. A video being recorded is never interrupted.
 */
object UpdateWatch : Application.ActivityLifecycleCallbacks {

    private const val INTERVAL = 15 * 60 * 1000L
    private const val RETRY_WHILE_BUSY = 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastCheck = 0L
    @Volatile private var checking = false
    private var current: Activity? = null

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** The splash screen has just checked. */
    fun markChecked() { lastCheck = System.currentTimeMillis() }

    private val tick = Runnable { check(force = false) }

    override fun onActivityResumed(activity: Activity) {
        current = activity
        check(force = false)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current === activity) current = null
        main.removeCallbacks(tick)
    }

    private fun schedule(delay: Long) {
        main.removeCallbacks(tick)
        main.postDelayed(tick, delay)
    }

    private fun check(force: Boolean) {
        val activity = current ?: return
        if (activity is SplashActivity) return
        val now = System.currentTimeMillis()
        val due = lastCheck + INTERVAL - now
        if (!force && due > 0) { schedule(due); return }
        if (checking) return
        checking = true
        val app = activity.applicationContext
        thread(name = "update-check") {
            val latest = try { Updater.fetchLatest() } catch (_: Exception) { null }
            main.post {
                checking = false
                lastCheck = System.currentTimeMillis()
                val a = current
                if (latest != null && latest.versionCode > AppVersion.code(app) && a != null) {
                    if (a is MainActivity && a.isRecordingVideo()) {
                        schedule(RETRY_WHILE_BUSY) // finish the video first
                        lastCheck = 0L
                    } else {
                        a.startActivity(Intent(a, SplashActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                } else {
                    schedule(INTERVAL)
                }
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
