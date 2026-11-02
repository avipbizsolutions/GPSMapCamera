package com.example.gpsmapcamera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** AVIP Tools home: one card per tool. */
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

        findViewById<View>(R.id.callCard).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + getString(R.string.company_phone))))
            } catch (_: Exception) {
            }
        }
        findViewById<TextView>(R.id.homeVersion).text =
            getString(R.string.home_version, AppVersion.name(this))
    }

    private fun open(id: Int, target: Class<*>) {
        findViewById<View>(id).setOnClickListener { startActivity(Intent(this, target)) }
    }
}
