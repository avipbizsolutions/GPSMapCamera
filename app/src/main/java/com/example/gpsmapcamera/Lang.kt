package com.example.gpsmapcamera

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App language: English or Hindi (Devanagari, with technical words such as GPS,
 * Panel and Serial Number kept in English). Photo stamps and PDF reports stay in English.
 */
object Lang {
    fun isHindi(activity: Activity): Boolean =
        activity.resources.configuration.locales[0].language == "hi"

    /** Switches English <-> Hindi; Android re-opens the screens in the new language and remembers it. */
    fun toggle(activity: Activity) {
        val next = if (isHindi(activity)) "en" else "hi"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }

    /** Wires a "हिंदी / English" chip. */
    fun bind(activity: Activity, chip: View) {
        chip.setOnClickListener { toggle(activity) }
    }
}
