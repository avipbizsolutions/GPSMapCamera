package com.example.gpsmapcamera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** SolarSite Pro home: one card per tool. */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        SystemBars.apply(this)

        open(R.id.toolCamera, MainActivity::class.java)
        open(R.id.toolTilt, TiltActivity::class.java)
        open(R.id.toolDocs, DocScannerActivity::class.java)
        open(R.id.toolSerials, SerialListsActivity::class.java)
        open(R.id.toolPhotos, GalleryActivity::class.java)

        // Each number calls itself.
        findViewById<View>(R.id.callPhone1).setOnClickListener { dial(getString(R.string.company_phone)) }
        findViewById<View>(R.id.callPhone2).setOnClickListener { dial(getString(R.string.company_phone2)) }

        // Godown: shown once its address is set; directions open Google Maps.
        val address = getString(R.string.godown_address)
        if (address.isNotBlank()) {
            findViewById<View>(R.id.godownBlock).visibility = View.VISIBLE
            findViewById<TextView>(R.id.godownAddress).text = getText(R.string.godown_address) // keeps the bold label
            findViewById<View>(R.id.godownDirections).setOnClickListener { openGodownDirections(address.removePrefix("Godown: ")) }
        }
        // Bihar Solar Circle: WhatsApp group invite (WhatsApp shows its Join screen) and app download.
        findViewById<View>(R.id.bscJoinGroup).setOnClickListener { openLink(getString(R.string.bsc_group_url)) }
        findViewById<View>(R.id.bscGetApp).setOnClickListener { openLink(getString(R.string.bsc_app_url)) }
        findViewById<View>(R.id.bscCard).setOnClickListener { openLink(getString(R.string.bsc_group_url)) }

        // Distributor card: tap to call for a quote.
        findViewById<View>(R.id.brandsCard).setOnClickListener { dial(getString(R.string.company_phone)) }

        findViewById<TextView>(R.id.homeVersion).text =
            getString(R.string.home_version, AppVersion.name(this))
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate()
    }

    /**
     * The app often comes back from the background without passing the splash screen,
     * so check here too: if a newer version exists, restart through the splash screen,
     * which downloads and installs it.
     */
    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 10 * 60 * 1000L) return
        lastUpdateCheck = now
        lifecycleScope.launch {
            val latest = withTimeoutOrNull(10000) { withContext(Dispatchers.IO) { Updater.fetchLatest() } } ?: return@launch
            if (latest.versionCode > AppVersion.code(this@HomeActivity) && !isFinishing) {
                startActivity(Intent(this@HomeActivity, SplashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }

    private companion object {
        /** Splash screen has just checked on a fresh start. */
        var lastUpdateCheck = System.currentTimeMillis()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.no_viewer, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun dial(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (_: Exception) {
        }
    }

    private fun openGodownDirections(address: String) {
        val lat = getString(R.string.godown_lat)
        val lng = getString(R.string.godown_lng)
        val hasPin = lat.isNotBlank() && lng.isNotBlank()
        val target = if (hasPin) "$lat,$lng" else Uri.encode(address)
        // Turn-by-turn in Google Maps; otherwise any maps app; otherwise the browser.
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$target"))
                .setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse(
                if (hasPin) "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(getString(R.string.godown_label))})"
                else "geo:0,0?q=$target"
            )),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$target")),
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun open(id: Int, target: Class<*>) {
        findViewById<View>(id).setOnClickListener { startActivity(Intent(this, target)) }
    }
}
