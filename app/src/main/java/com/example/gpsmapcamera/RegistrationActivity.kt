package com.example.gpsmapcamera

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** One-time registration shown on first launch, before the camera. */
class RegistrationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        val name = findViewById<EditText>(R.id.nameInput)
        val company = findViewById<EditText>(R.id.companyInput)
        val mobile = findViewById<EditText>(R.id.mobileInput)
        val consent = findViewById<CheckBox>(R.id.consentCheck)

        findViewById<Button>(R.id.registerButton).setOnClickListener {
            val n = name.text.toString().trim().replace(Regex("\\s+"), " ")
            val c = company.text.toString().trim().replace(Regex("\\s+"), " ")
            val m = mobile.text.toString().filter { it.isDigit() }.let {
                // Accept +91 / 0 prefixes, keep the 10-digit number.
                if (it.length > 10) it.takeLast(10) else it
            }

            var ok = true
            if (n.length < 2) {
                name.error = getString(R.string.reg_error_name); ok = false
            }
            if (c.length < 2) {
                company.error = getString(R.string.reg_error_company); ok = false
            }
            if (!Regex("[6-9]\\d{9}").matches(m)) {
                mobile.error = getString(R.string.reg_error_mobile); ok = false
            }
            if (!ok) return@setOnClickListener

            Registration.save(this, n, c, m, consent.isChecked)
            Registration.uploadPendingAsync(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Registration is required: Back closes the app instead of skipping it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })
    }
}
