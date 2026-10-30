package com.example.gpsmapcamera

import android.content.Context
import java.io.File

/**
 * Play Store version: updates come through Google Play, so there is no
 * self-updater (Play policy). Same calls as the link version, doing nothing.
 */
object Updater {
    const val ACTION_INSTALL_STATUS = "com.example.gpsmapcamera.INSTALL_STATUS"

    fun canInstall(context: Context): Boolean = false

    fun fetchLatest(): ReleaseInfo? = null

    fun download(context: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): File? = null

    fun install(context: Context, apk: File) = Unit
}
