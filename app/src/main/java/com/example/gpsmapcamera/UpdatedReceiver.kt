package com.example.gpsmapcamera

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Runs right after the app has been replaced by a newer version: tells the user. */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL, context.getString(R.string.updates_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val version = Updater.currentVersionName(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.updated_title))
            .setContentText(context.getString(R.string.updated_text, version))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1001, notification)
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val CHANNEL = "updates"
    }
}
