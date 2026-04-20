package org.soulstone.mooncompanion

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-device app-level auth-token store, keyed by Bluetooth identity
 * address. Sitting on top of the BLE bond: once the Flipper is a bonded
 * peer, we want the same RPC-layer auth token to survive disconnects so
 * the user doesn't press "pair" on the Flipper every session.
 *
 * Addresses are normalized to uppercase — BluetoothDevice.getAddress()
 * returns uppercase on all Android versions we care about, but the
 * contract is explicit here to avoid silent misses if that ever drifts.
 */
interface BondedTokenStore {
    fun get(address: String): ByteArray?
    fun put(address: String, token: ByteArray)
    fun remove(address: String)

    companion object {
        fun normalize(address: String): String = address.uppercase()
    }
}

/**
 * Default production store: EncryptedSharedPreferences keyed by a
 * MasterKey in the Android Keystore. Tokens are stored as base64 strings
 * so the prefs XML stays text-only.
 */
class EncryptedBondedTokenStore(context: Context) : BondedTokenStore {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(address: String): ByteArray? {
        val encoded = prefs.getString(BondedTokenStore.normalize(address), null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun put(address: String, token: ByteArray) {
        prefs.edit()
            .putString(
                BondedTokenStore.normalize(address),
                Base64.encodeToString(token, Base64.NO_WRAP),
            )
            .apply()
    }

    override fun remove(address: String) {
        prefs.edit().remove(BondedTokenStore.normalize(address)).apply()
    }

    companion object {
        private const val PREFS_NAME = "moon_bonded_tokens"
    }
}

/** In-memory impl for tests and the unbonded-fallback path. */
class InMemoryBondedTokenStore : BondedTokenStore {
    private val map = HashMap<String, ByteArray>()
    override fun get(address: String): ByteArray? = map[BondedTokenStore.normalize(address)]
    override fun put(address: String, token: ByteArray) {
        map[BondedTokenStore.normalize(address)] = token
    }
    override fun remove(address: String) {
        map.remove(BondedTokenStore.normalize(address))
    }
}
