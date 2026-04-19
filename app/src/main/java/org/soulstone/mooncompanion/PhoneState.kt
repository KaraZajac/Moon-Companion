package org.soulstone.mooncompanion

import java.security.SecureRandom

/**
 * Mutable state for a running peripheral. Single-threaded by contract —
 * every mutation happens on the main looper (which is where BLE callbacks
 * land on Android), so no locking is needed yet. If we move notification
 * emission onto a background coroutine, revisit.
 */
class PhoneState {
    var paired: Boolean = false
    var authToken: ByteArray = ByteArray(0)
    var positionSubscribed: Boolean = false
    var positionIntervalMs: Long = 2_000
    var positionTick: Int = 0

    fun issueAuthToken(): ByteArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        authToken = bytes
        paired = true
        return bytes
    }

    fun resetPairing() {
        paired = false
        authToken = ByteArray(0)
        positionSubscribed = false
    }
}
