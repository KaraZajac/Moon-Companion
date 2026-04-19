package org.soulstone.mooncompanion

import android.content.Context
import androidx.core.content.edit

/**
 * Thin wrapper around the default SharedPreferences so the rest of the
 * app doesn't have to remember key strings. Values tracked here are the
 * user's durable choices — things that should outlive a process restart.
 */
class Preferences(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var startAtBoot: Boolean
        get() = prefs.getBoolean(KEY_START_AT_BOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_START_AT_BOOT, value) }

    companion object {
        private const val FILE = "moon_companion_prefs"
        private const val KEY_START_AT_BOOT = "start_at_boot"
    }
}
