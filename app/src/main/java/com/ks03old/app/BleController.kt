package com.ks03old.app

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Handles scanning for KS03- (old protocol) devices and writing raw frames
 * to the fff0/fff3 GATT characteristic. Short UUIDs are expanded using the
 * standard Bluetooth base UUID, same as real KS03 firmware expects.
 */
class BleController(private val context: Context) {

    companion object {
        private const val NAME_PREFIX = "KS03-"
        private val SERVICE_UUID = shortUuid("fff0")
        private val CHAR_UUID = shortUuid("fff3")

        private fun shortUuid(shortHex: String): UUID =
            UUID.fromString("0000$shortHex-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onReady() // characteristic resolved, can write now
        fun onLog(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var listener: Listener? = null
    private var scanning = false

    private val seenAddresses = mutableSetOf<String>()

    fun setListener(l: Listener) {
        listener = l
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressWarnings("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        seenAddresses.clear()
        scanning = true
        listener?.onLog("Scanning for $NAME_PREFIX* devices…")

        // Bonded-device fast path: if the phone already paired with a KS03-
        // device, hand it back immediately instead of waiting on a fresh scan.
        adapter.bondedDevices?.firstOrNull { d ->
            d.name?.startsWith(NAME_PREFIX, ignoreCase = true) == true
        }?.let { bonded ->
            seenAddresses.add(bonded.address)
            listener?.onDeviceFound(bonded, 0)
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // An empty ScanFilter.Builder().build() is NOT "no filter" — it's a
        // filter with every match field null, and several OEM Bluetooth
        // stacks (Samsung/MediaTek) silently match nothing against it.
        // Filter on the real service UUID instead, and fall back to a
        // genuinely unfiltered scan (filters = null) if nothing turns up in
        // the first few seconds, in case a unit doesn't advertise the
        // service UUID in its primary advertisement packet.
        val serviceFilters = listOf(
            ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()
        )

        scanner.startScan(serviceFilters, settings, scanCallback)

        handler.postDelayed({
            if (!scanning) return@postDelayed
            if (seenAddresses.isEmpty()) {
                try {
                    scanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
                listener?.onLog("No results from filtered scan, retrying unfiltered…")
                scanner.startScan(null, settings, scanCallback)
            }
        }, 4000L)

        // Auto-stop after 15s so we don't drain the battery if the user walks away.
        handler.postDelayed({ stopScan() }, 15000)
    }

    @SuppressWarnings("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        listener?.onLog("Scan stopped.")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressWarnings("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // scanRecord.deviceName reflects what's actually being broadcast right
            // now; device.name relies on the OS's cached GATT record, which can be
            // null/stale until the phone has connected to the device before.
            val name = (result.scanRecord?.deviceName ?: device.name)?.trim() ?: return
            if (!name.startsWith(NAME_PREFIX, ignoreCase = true)) return
            if (!seenAddresses.add(device.address)) return
            listener?.onDeviceFound(device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onLog("Scan failed, error code $errorCode")
        }
    }

    @SuppressWarnings("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        listener?.onLog("Connecting to ${device.address}…")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressWarnings("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.post { listener?.onConnected(g.device) }
                    // Request the shortest connection interval Android/the peripheral
                    // will grant. This raises the real ceiling on writes/sec — it's
                    // a request, not a guarantee, but costs nothing to ask for.
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeChar = null
                    handler.post { listener?.onDisconnected() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { listener?.onLog("Service discovery failed ($status)") }
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                handler.post { listener?.onLog("Service fff0 not found on this device") }
                return
            }
            val characteristic = service.getCharacteristic(CHAR_UUID)
            if (characteristic == null) {
                handler.post { listener?.onLog("Characteristic fff3 not found on this device") }
                return
            }
            writeChar = characteristic
            handler.post { listener?.onReady() }
        }
    }

    /** Returns true if the write was accepted for transmission, false if the OS BLE
     * stack rejected it outright (e.g. internal buffer full). A true return does not
     * guarantee the peripheral received it — WRITE_TYPE_NO_RESPONSE has no ACK. */
    @SuppressWarnings("MissingPermission")
    fun send(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: run {
            listener?.onLog("Not ready yet (characteristic not resolved)")
            return false
        }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    fun isConnected(): Boolean = gatt != null && writeChar != null

    @SuppressWarnings("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
    }
}
