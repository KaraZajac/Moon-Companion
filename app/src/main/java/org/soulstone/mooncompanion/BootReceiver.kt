package org.soulstone.mooncompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start the peripheral service on device boot or after a package
 * update. Only fires if the user opted in via the Start-at-boot toggle
 * in MainActivity — we respect opt-in rather than forcing auto-start.
 *
 * Starting a foreground service from a BroadcastReceiver is allowed
 * from BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED per
 * Android's background-start restrictions; other triggers would be
 * rejected by the platform.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ALLOWED_ACTIONS) return

        val prefs = Preferences(context)
        if (!prefs.startAtBoot) {
            Log.i(TAG, "Ignoring $action — start-at-boot disabled")
            return
        }

        Log.i(TAG, "Auto-starting peripheral service on $action")
        MoonPeripheralService.start(context)
    }

    companion object {
        private const val TAG = "MoonBoot"
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
