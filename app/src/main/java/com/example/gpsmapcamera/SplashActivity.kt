package com.example.gpsmapcamera

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Branded launch screen: logo, name, version and developer, then the camera. */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: ""
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version_label, version)

        // Quick pop-in of the brand block.
        findViewById<android.view.View>(R.id.brandBlock).apply {
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start()
        }

        lifecycleScope.launch {
            delay(1600)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
