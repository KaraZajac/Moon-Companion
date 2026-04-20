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

    /**
     * Set when the peer transitions to BOND_BONDED (via the OS broadcast
     * receiver in MoonPeripheralService). Cleared on BOND_NONE or when
     * the connection drops. When set, authTokenFor() persists tokens so
     * re-pairs resolve to the same token across sessions.
     */
    var bondedAddress: String? = null

    /**
     * Return the auth token for `address`, reusing the persisted one if
     * we have it, else issuing a fresh random 16-byte token and storing
     * it. Also sets the instance fields so checkAuth() continues to
     * match against `state.authToken`.
     */
    fun authTokenFor(address: String, store: BondedTokenStore): ByteArray {
        val existing = store.get(address)
        val token = existing ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        if (existing == null) store.put(address, token)
        authToken = token
        paired = true
        return token
    }

    /** Ephemeral path — used only when we don't have a peer address. */
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
