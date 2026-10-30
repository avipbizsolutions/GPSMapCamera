package com.example.gpsmapcamera

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

object AppVersion {
    fun code(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    fun name(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}

/** A newer version found by the self-updater (link version only). */
data class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val size: Long,
)
