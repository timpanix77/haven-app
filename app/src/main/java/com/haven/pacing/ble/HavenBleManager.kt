package com.haven.pacing.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.*
import kotlin.math.min

class HavenBleManager(
    private val context: Context,
    private val onHeartRateReceived: (Int) -> Unit,
    private val onConnectionStatusChanged: (Boolean, String) -> Unit
) {
    private val HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var isConnected = false
    private var isScanning = false
    private var targetDeviceAddress: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectBackoffMs = 2000L

    fun startConnect(explicitAddress: String? = null) {
        targetDeviceAddress = explicitAddress
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onConnectionStatusChanged(false, "Bluetooth Disabled")
            return
        }

        if (targetDeviceAddress != null) {
            val device = bluetoothAdapter.getRemoteDevice(targetDeviceAddress)
            connectToDevice(device)
        } else {
            startScan()
        }
    }

    private fun startScan() {
        if (isScanning) return
        isScanning = true
        onConnectionStatusChanged(false, "Scanning for Venu 3...")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            onConnectionStatusChanged(false, "Missing BLE Permission")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: ""
            // Prioritize Venu 3 or target bonded device to avoid latching onto Polar
            if (targetDeviceAddress == null || result.device.address == targetDeviceAddress || deviceName.contains("Venu", ignoreCase = true)) {
                try {
                    scanner?.stopScan(this)
                } catch (e: SecurityException) {}
                isScanning = false
                targetDeviceAddress = result.device.address
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            scheduleReconnect()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        onConnectionStatusChanged(false, "Connecting: ${device.name ?: device.address}")
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            onConnectionStatusChanged(false, "Security Exception on connect")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true
                    reconnectBackoffMs = 2000L
                    onConnectionStatusChanged(true, "Connected: ${gatt.device.name ?: "Venu 3"}")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false
                    onConnectionStatusChanged(false, "Disconnected")
                    gatt.close()
                    scheduleReconnect()
                }
            } catch (e: SecurityException) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    val service = gatt.getService(HR_SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(HR_CHAR_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }
                } catch (e: SecurityException) {}
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HR_CHAR_UUID) {
                val flag = characteristic.properties
                val format = if ((flag and 0x01) != 0) BluetoothGattCharacteristic.FORMAT_UINT16 else BluetoothGattCharacteristic.FORMAT_UINT8
                val hr = characteristic.getIntValue(format, 1) ?: 0
                if (hr > 0) {
                    onHeartRateReceived(hr)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            if (!isConnected) {
                startConnect(targetDeviceAddress)
                reconnectBackoffMs = min(reconnectBackoffMs * 2, 30000L) // Exponential backoff max 30s
            }
        }, reconnectBackoffMs)
    }

    fun disconnect() {
        try {
            scanner?.stopScan(scanCallback)
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {}
        isConnected = false
        isScanning = false
        handler.removeCallbacksAndMessages(null)
    }
}
