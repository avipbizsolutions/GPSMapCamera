package com.example.gpsmapcamera

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Branded launch screen. While it shows, it checks GitHub for a newer version;
 * if there is one it downloads and installs it, otherwise it opens the camera.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingRow: View
    private var leaving = false
    private var pendingRelease: ReleaseInfo? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android wants a one-tap confirmation for this update.
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        try {
                            startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            return
                        } catch (_: Exception) {
                        }
                    }
                    goToCamera()
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // Android restarts the app process with the new version.
                }
                else -> {
                    toast(getString(R.string.update_failed))
                    goToCamera()
                }
            }
        }
    }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val release = pendingRelease
            if (release != null && Updater.canInstall(this)) {
                lifecycleScope.launch { downloadAndInstall(release) }
            } else {
                goToCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.updateProgress)
        loadingRow = findViewById(R.id.loadingRow)

        // Start finding the GPS location now, so the camera opens with it ready.
        LocationWarmup.start(this)
        val gpsText = findViewById<TextView>(R.id.gpsStatusText)
        if (LocationWarmup.hasPermission(this)) gpsText.visibility = View.VISIBLE

        // Animated "Loading." → "Loading.." → "Loading..."
        val loadingText = findViewById<TextView>(R.id.loadingText)
        lifecycleScope.launch {
            var dots = 0
            while (true) {
                loadingText.text = "Loading" + ".".repeat(dots + 1)
                dots = (dots + 1) % 3
                gpsText.text = getString(
                    if (LocationWarmup.location != null) R.string.gps_found else R.string.gps_finding
                )
                delay(400)
            }
        }

        // Tap a number to call it.
        fun dial(number: String) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            } catch (_: Exception) {
            }
        }
        findViewById<View>(R.id.phone1).setOnClickListener { dial(getString(R.string.company_phone)) }
        findViewById<View>(R.id.phone2).setOnClickListener { dial(getString(R.string.company_phone2)) }

        val versionName = Updater.currentVersionName(this)
        val versionCode = Updater.currentVersionCode(this)
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version_label, versionName)

        // Tell the user once after an update.
        val lastSeen = prefs.getLong(KEY_LAST_VERSION, 0L)
        if (lastSeen in 1 until versionCode) {
            statusText.text = getString(R.string.updated_text, versionName)
            statusText.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.updated_text, versionName), Toast.LENGTH_LONG).show()
        }
        prefs.edit().putLong(KEY_LAST_VERSION, versionCode).apply()

        // Send a registration that couldn't be uploaded earlier (e.g. offline).
        Registration.uploadPendingAsync(this)

        ContextCompat.registerReceiver(
            this, installStatusReceiver, IntentFilter(Updater.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        findViewById<View>(R.id.brandBlock).apply {
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start()
        }

        lifecycleScope.launch {
            val minimumShow = async { delay(3000) }
            val latest = withTimeoutOrNull(3000) { withContext(Dispatchers.IO) { Updater.fetchLatest() } }

            if (latest != null && latest.versionCode > versionCode) {
                pendingRelease = latest
                if (Updater.canInstall(this@SplashActivity)) {
                    downloadAndInstall(latest)
                    return@launch
                }
                if (prefs.getLong(KEY_DECLINED_VERSION, 0L) != latest.versionCode) {
                    minimumShow.await()
                    askInstallPermission(latest)
                    return@launch
                }
            }
            minimumShow.await()
            goToCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(installStatusReceiver)
        } catch (_: Exception) {
        }
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo) {
        loadingRow.visibility = View.GONE
        statusText.text = getString(R.string.updating_to, release.versionName)
        statusText.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE

        val apk = withContext(Dispatchers.IO) {
            Updater.download(this@SplashActivity, release) { pct ->
                runOnUiThread { progressBar.progress = pct }
            }
        }
        if (apk == null) {
            toast(getString(R.string.update_failed))
            goToCamera()
            return
        }
        statusText.text = getString(R.string.installing_update)
        try {
            withContext(Dispatchers.IO) { Updater.install(this@SplashActivity, apk) }
            // Result arrives in installStatusReceiver.
        } catch (_: Exception) {
            toast(getString(R.string.update_failed))
            goToCamera()
        }
    }

    /** One-time Android setting that lets the app install its own updates. */
    private fun askInstallPermission(release: ReleaseInfo) {
        loadingRow.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_update_title)
            .setMessage(getString(R.string.auto_update_message, release.versionName))
            .setCancelable(false)
            .setPositiveButton(R.string.allow) { _, _ ->
                unknownSourcesLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.later) { _, _ ->
                prefs.edit().putLong(KEY_DECLINED_VERSION, release.versionCode).apply()
                goToCamera()
            }
            .show()
    }

    private fun goToCamera() {
        if (leaving || isFinishing) return
        leaving = true
        val next = if (Registration.isRegistered(this)) MainActivity::class.java
        else RegistrationActivity::class.java
        startActivity(Intent(this, next))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val KEY_LAST_VERSION = "last_version_code"
        const val KEY_DECLINED_VERSION = "declined_update_version"
    }
}
