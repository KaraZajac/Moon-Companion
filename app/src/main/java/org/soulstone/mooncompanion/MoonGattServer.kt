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
import android.util.Log
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
    private val onWrite: (ByteArray) -> ByteArray?,
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
            Log.e(TAG, "Bluetooth unavailable")
            onStateChange?.invoke("Bluetooth unavailable")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth off")
            onStateChange?.invoke("Bluetooth off")
            return
        }

        server = manager.openGattServer(context, callback) ?: run {
            Log.e(TAG, "openGattServer returned null")
            onStateChange?.invoke("Could not open GATT server")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val tx = BluetoothGattCharacteristic(
            RPC_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val rx = BluetoothGattCharacteristic(
            RPC_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        rx.addDescriptor(
            BluetoothGattDescriptor(
                CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
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
            } catch (e: SecurityException) {
                Log.w(TAG, "notify denied for ${device.address}: ${e.message}")
            }
        }
    }

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            onStateChange?.invoke("Advertise failed ($errorCode)")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Conn ${device.address} status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                onStateChange?.invoke("Advertising")
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
                val reply = try {
                    onWrite(value)
                } catch (e: Exception) {
                    Log.e(TAG, "onWrite threw: ${e.message}", e)
                    null
                }
                if (reply != null) notifySubscribers(reply)
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
                Log.i(TAG, "Notify ${if (on) "on" else "off"} for ${device.address}")
                onStateChange?.invoke(if (on) "Subscribed: ${device.address}" else "Connected: ${device.address}")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }
}
