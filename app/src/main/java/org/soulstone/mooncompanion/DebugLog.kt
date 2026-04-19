package org.soulstone.mooncompanion

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log sink. Writes every entry to the platform Log as usual so
 * `adb logcat` still works, *and* keeps a capped ring buffer so the
 * Activity can render them for users who aren't near a machine with
 * adb. Thread-safe.
 */
object DebugLog {

    private const val MAX_ENTRIES = 500

    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val listeners = mutableListOf<(List<String>) -> Unit>()

    // Lazy per-thread instances keep this allocation-free on the logging
    // hot path and still thread-safe.
    private val fmt = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun d(tag: String, msg: String) { Log.d(tag, msg); append("D", tag, msg, null) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); append("I", tag, msg, null) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); append("W", tag, msg, null) }
    fun e(tag: String, msg: String, err: Throwable? = null) {
        if (err != null) Log.e(tag, msg, err) else Log.e(tag, msg)
        append("E", tag, msg, err)
    }

    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() {
        val snapshot = synchronized(lock) {
            entries.clear()
            emptyList<String>()
        }
        notifyListeners(snapshot)
    }

    fun addListener(fn: (List<String>) -> Unit) {
        synchronized(lock) { listeners.add(fn) }
        // Fire once immediately so the new subscriber sees current state.
        fn(snapshot())
    }

    fun removeListener(fn: (List<String>) -> Unit) {
        synchronized(lock) { listeners.remove(fn) }
    }

    private fun append(level: String, tag: String, msg: String, err: Throwable?) {
        val ts = fmt.get()!!.format(Date())
        val suffix = err?.message?.let { " — $it" }.orEmpty()
        val line = "$ts $level $tag: $msg$suffix"
        val snapshot = synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            entries.toList()
        }
        notifyListeners(snapshot)
    }

    private fun notifyListeners(snapshot: List<String>) {
        val copy = synchronized(lock) { listeners.toList() }
        for (fn in copy) {
            try { fn(snapshot) } catch (_: Exception) { /* listener's problem */ }
        }
    }
}
