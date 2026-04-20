package org.soulstone.mooncompanion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Owns the peripheral-side BluetoothGattServer + LE advertiser. Surfaces
 * a narrow callback contract so MoonPeripheralService doesn't need to know
 * anything about BLE plumbing.
 *
 * Threading: all BluetoothGattServerCallback calls land on the main thread
 * on Android 10+ when we use the zero-arg openGattServer. Every mutable
 * field here is only ever touched from that thread, so no locking.
 *
 * UUIDs must stay in lockstep with applications/services/moon_companion/
 * moon_companion_ble.h over in Moon-Firmware.
 */
class MoonGattServer(
    private val context: Context,
    private val onWrite: (BluetoothDevice, ByteArray) -> ByteArray?,
) {
    companion object {
        private const val TAG = "MoonGatt"

        val SERVICE_UUID: UUID = UUID.fromString("df98ad38-b0b8-44c0-bd88-905db2d6b365")
        val RPC_TX_UUID:  UUID = UUID.fromString("7a30f8d0-2a4e-43b7-a383-1f786173991b")
        val RPC_RX_UUID:  UUID = UUID.fromString("902dac74-55ba-46d4-8ff4-6d40893ae052")

        // Standard Client Characteristic Configuration descriptor — the
        // subscribe toggle that every notify-capable char needs.
        private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()

    var onStateChange: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = manager.adapter ?: run {
            DebugLog.e(TAG, "Bluetooth unavailable")
            onStateChange?.invoke("Bluetooth unavailable")
            return
        }
        if (!adapter.isEnabled) {
            DebugLog.e(TAG, "Bluetooth off")
            onStateChange?.invoke("Bluetooth off")
            return
        }

        server = manager.openGattServer(context, callback) ?: run {
            DebugLog.e(TAG, "openGattServer returned null")
            onStateChange?.invoke("Could not open GATT server")
            return
        }
        DebugLog.i(TAG, "GATT server opened")

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Encrypted permissions force Android's SMP to run on first read/
        // write, which is what gets the Flipper into the OS-level bonded
        // list. Just-Works (no MITM variant) — the Flipper has no display
        // or keyboard IO capability to satisfy MITM-protected pairing.
        val tx = BluetoothGattCharacteristic(
            RPC_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )

        val rx = BluetoothGattCharacteristic(
            RPC_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        // CCCD must also require encryption; otherwise a central can flip
        // notifications on without triggering SMP on some stacks.
        rx.addDescriptor(
            BluetoothGattDescriptor(
                CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
            )
        )
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        server!!.addService(service)
        rxChar = rx

        advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setTimeout(0)
            .build()

        // Keep the advert small. The device name can push us over 31 bytes
        // once the service UUID is in there — put it in the scan response
        // instead so the primary advert still fits.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        DebugLog.i(
            TAG,
            "Advertising as ${adapter.name} (service=${SERVICE_UUID}, tx=${RPC_TX_UUID}, rx=${RPC_RX_UUID})"
        )
        onStateChange?.invoke("Advertising")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        server?.close()
        server = null
        subscribers.clear()
        onStateChange?.invoke("Stopped")
    }

    @SuppressLint("MissingPermission")
    fun notifySubscribers(payload: ByteArray) {
        val server = server ?: return
        val rx = rxChar ?: return
        rx.value = payload
        for (device in subscribers.toList()) {
            try {
                server.notifyCharacteristicChanged(device, rx, /* confirm = */ false)
                DebugLog.d(TAG, "notify ${payload.size} bytes -> ${device.address}")
            } catch (e: SecurityException) {
                DebugLog.w(TAG, "notify denied for ${device.address}: ${e.message}")
            }
        }
    }

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            DebugLog.i(TAG, "Advertising started (txPower=${settingsInEffect.txPowerLevel})")
        }

        override fun onStartFailure(errorCode: Int) {
            DebugLog.e(TAG, "Advertising failed: ${describeAdvertiseError(errorCode)}")
            onStateChange?.invoke("Advertise failed ($errorCode)")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val stateStr = describeConnectionState(newState)
            val statusStr = describeGattStatus(status)
            val bond = describeBondState(device.bondState)
            DebugLog.i(
                TAG,
                "Connection ${device.address} $stateStr (status=$statusStr, bond=$bond)"
            )
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                // status 34 = GATT_INSUFFICIENT_ENCRYPTION. Seen when the
                // central tried to read/write before SMP ran — the central
                // doesn't support bonding. Surface a diagnostic message so
                // the user knows to upgrade the Flipper side.
                if (status == 34) {
                    onStateChange?.invoke("Encryption failed — update Flipper firmware")
                } else {
                    onStateChange?.invoke("Advertising")
                }
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStateChange?.invoke("Connected: ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == RPC_TX_UUID) {
                DebugLog.i(
                    TAG,
                    "RPC_TX write ${value.size} bytes from ${device.address} " +
                        "(first=${value.take(8).joinToString("") { "%02x".format(it) }})"
                )
                val reply = try {
                    onWrite(device, value)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "onWrite threw: ${e.message}", e)
                    null
                }
                if (reply != null) notifySubscribers(reply)
            } else {
                DebugLog.d(TAG, "Unexpected write on ${characteristic.uuid}")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            DebugLog.d(
                TAG,
                "read ${characteristic.uuid} from ${device.address} (${value.size} bytes)"
            )
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCC_UUID) {
                val on = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (on) subscribers.add(device) else subscribers.remove(device)
                DebugLog.i(TAG, "CCCD notify=${if (on) "on" else "off"} from ${device.address}")
                onStateChange?.invoke(
                    if (on) "Subscribed: ${device.address}" else "Connected: ${device.address}"
                )
            } else {
                DebugLog.d(TAG, "Descriptor write ${descriptor.uuid} from ${device.address}")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            DebugLog.i(TAG, "MTU changed with ${device.address}: $mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                DebugLog.w(TAG, "notify to ${device.address} delivered status=$status")
            }
        }
    }

    private fun describeConnectionState(state: Int) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "state=$state"
    }

    private fun describeBondState(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "bond=$state"
    }

    // Common BluetoothGatt status codes we see on disconnect.
    private fun describeGattStatus(status: Int) = when (status) {
        0 -> "SUCCESS"
        8 -> "CONN_TIMEOUT"
        19 -> "PEER_USER"           // peer disconnected
        22 -> "LOCAL_HOST"          // we disconnected
        34 -> "INSUFFICIENT_ENCRYPT"
        62 -> "CONN_FAIL_ESTABLISH"
        133 -> "GATT_ERROR(133)"
        137 -> "INSUFFICIENT_AUTH"
        else -> "status=$status"
    }

    private fun describeAdvertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        else -> "code=$code"
    }
}
