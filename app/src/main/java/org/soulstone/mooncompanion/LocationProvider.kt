package org.soulstone.mooncompanion

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Thin wrapper around FusedLocationProviderClient. Keeps the last known
 * fix around so GetPosition / SubscribePosition handlers can read it
 * without blocking.
 *
 * Threading: callbacks come in on the main looper (we pass it into
 * requestLocationUpdates). `lastLocation` is therefore only mutated on
 * the main thread. Readers on other threads get a lightly-stale value,
 * which is fine — GPS fixes age in seconds anyway.
 */
class LocationProvider(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var lastLocation: Location? = null

    private var running = false
    private var currentIntervalMs: Long = DEFAULT_INTERVAL_MS

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fix = result.lastLocation ?: return
            lastLocation = fix
            Log.d(
                TAG,
                "fix lat=${fix.latitude} lon=${fix.longitude} acc=${fix.accuracy}m " +
                    "alt=${if (fix.hasAltitude()) fix.altitude else null}"
            )
        }
    }

    /** Start streaming location updates at (approx) this cadence. Idempotent. */
    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (running && intervalMs == currentIntervalMs) return
        if (running) stop()
        currentIntervalMs = intervalMs.coerceAtLeast(500L)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
            .setMinUpdateIntervalMillis(currentIntervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            running = true
            Log.i(TAG, "Location updates on (interval=${currentIntervalMs}ms)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted: ${e.message}")
        }

        // Prime lastLocation with whatever the system already knows so we
        // don't return NOT_AVAILABLE on the first GetPosition just because
        // the update-stream hasn't ticked yet.
        try {
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && lastLocation == null) {
                    lastLocation = loc
                    Log.i(TAG, "Primed with last-known fix (age=${System.currentTimeMillis() - loc.time}ms)")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "lastLocation denied: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
        Log.i(TAG, "Location updates off")
    }

    /** Returns the freshest fix known, or null. Does not block. */
    fun current(): Location? = lastLocation

    companion object {
        private const val TAG = "MoonLoc"
        const val DEFAULT_INTERVAL_MS = 2_000L
    }
}
