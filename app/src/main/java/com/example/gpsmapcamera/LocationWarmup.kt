package com.example.gpsmapcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Starts finding the GPS location (plus the address and map thumbnail) while
 * the launch screen is showing, so the camera opens with them ready.
 * The camera screen takes the results and then does its own tracking.
 */
object LocationWarmup {

    /** A remembered position older than this is ignored (the user may have moved). */
    private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60 * 1000L

    @Volatile var location: Location? = null; private set
    @Volatile var place: PlaceInfo? = null; private set
    @Volatile var placeFor: Location? = null; private set
    @Volatile private var map: Bitmap? = null
    @Volatile private var mapFor: Location? = null

    @Volatile private var geocoding = false
    @Volatile private var mapping = false
    private var manager: LocationManager? = null
    private var appContext: Context? = null
    private val listener = LocationListener { onFix(it) }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Is a remembered position recent enough to trust? */
    fun isFresh(loc: Location): Boolean =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000 < MAX_LAST_KNOWN_AGE_MS

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (manager != null || !hasPermission(context)) return
        val app = context.applicationContext
        val m = app.getSystemService(LocationManager::class.java) ?: return
        appContext = app
        manager = m
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!runCatching { m.isProviderEnabled(p) }.getOrDefault(false)) continue
            try {
                m.getLastKnownLocation(p)?.takeIf { isFresh(it) }?.let { onFix(it) }
                m.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // e.g. GPS provider when only approximate location was allowed
            }
        }
    }

    fun stop() {
        manager?.removeUpdates(listener)
        manager = null
    }

    /** Hands the prepared map to the camera screen (which then owns it). */
    fun takeMap(): Pair<Bitmap, Location>? {
        val bmp = map ?: return null
        val at = mapFor ?: return null
        map = null
        mapFor = null
        return bmp to at
    }

    private fun onFix(loc: Location) {
        val cur = location
        val better = cur == null || loc.time - cur.time > 10_000 || loc.accuracy <= cur.accuracy
        if (!better) return
        location = loc
        val app = appContext ?: return

        val pf = placeFor
        if (!geocoding && (pf == null || pf.distanceTo(loc) > 30f)) {
            geocoding = true
            Thread {
                Geocoding.lookup(app, loc)?.let { place = it; placeFor = loc }
                geocoding = false
            }.start()
        }
        val mf = mapFor
        if (!mapping && (mf == null || mf.distanceTo(loc) > 30f)) {
            mapping = true
            Thread {
                MapTiles.render(loc.latitude, loc.longitude, zoom = 16, sizePx = 512)?.let {
                    val old = map
                    map = it
                    mapFor = loc
                    old?.recycle()
                }
                mapping = false
            }.start()
        }
    }
}
