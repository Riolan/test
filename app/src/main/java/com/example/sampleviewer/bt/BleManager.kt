package com.example.sampleviewer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import java.util.UUID
import android.os.Handler
import android.os.Looper
import com.example.sampleviewer.Node
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface BleConnectionListener {
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
}

interface BleDataListener {
    fun onBleDataReceived(data: ByteArray)
}


class BleManager private constructor() {
    private var bluetoothGatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var currentDevice: BluetoothDevice? = null
    // UUIDs for BLE Service and Characteristic
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")
    private val CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-4321-4321-210987654321")

    // Store all discovered devices
    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()

    // Store device-specific information
    private val deviceInfoMap = mutableMapOf<String, DeviceInfo>()
    private val connectionListeners = mutableListOf<BleConnectionListener>()
    private val dataListeners = mutableListOf<BleDataListener>()


    private val _nodeListState = MutableStateFlow<List<Node>>(emptyList())
    // Public immutable StateFlow that the UI can observe
    val nodeListState: StateFlow<List<Node>> = _nodeListState.asStateFlow()


    fun registerDataListener(listener: BleDataListener) {
        if (!dataListeners.contains(listener)) {
            dataListeners.add(listener)
        }
    }

    fun unregisterDataListener(listener: BleDataListener) {
        dataListeners.remove(listener)
    }

    fun notifyDataReceived(data: ByteArray) {
        dataListeners.forEach { it.onBleDataReceived(data) }
    }

    fun registerConnectionListener(listener: BleConnectionListener) {
        if (!connectionListeners.contains(listener)) {
            connectionListeners.add(listener)
        }
    }

    fun unregisterConnectionListener(listener: BleConnectionListener) {
        connectionListeners.remove(listener)
    }

    // Call these methods when connection state changes
    private fun notifyDeviceConnected(device: BluetoothDevice) {
        connectionListeners.forEach { it.onDeviceConnected(device) }
    }

    private fun notifyDeviceDisconnected(device: BluetoothDevice) {
        connectionListeners.forEach { it.onDeviceDisconnected(device) }
    }

    data class DeviceInfo(
        val deviceAddress: String,
        val deviceName: String?,
        var animalSettings: ByteArray = byteArrayOf(0, 0, 0, 0),
        var lastImageTimestamp: Long = 0
    ) {
        // Override equals and hashCode for proper ByteArray comparison
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DeviceInfo

            if (deviceAddress != other.deviceAddress) return false
            if (deviceName != other.deviceName) return false
            if (!animalSettings.contentEquals(other.animalSettings)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = deviceAddress.hashCode()
            result = 31 * result + (deviceName?.hashCode() ?: 0)
            result = 31 * result + animalSettings.contentHashCode()
            return result
        }
    }

    // Add a new device to our tracked devices
    @SuppressLint("MissingPermission")
    fun addDevice(device: BluetoothDevice) {
        val address = device.address
        discoveredDevices[address] = device

        if (!deviceInfoMap.containsKey(address)) {
            deviceInfoMap[address] = DeviceInfo(
                deviceAddress = address,
                deviceName = device.name ?: "Unknown Device"
            )
        }
        Log.d("BleManager", "Added device: ${device.name} (${device.address})")
    }

    // Get all discovered devices
    fun getDiscoveredDevices(): List<BluetoothDevice> {
        return discoveredDevices.values.toList()
    }

    // Get device info for all devices
    fun getAllDeviceInfo(): List<DeviceInfo> {
        return deviceInfoMap.values.toList()
    }

    // Get a specific device by address
    fun getDevice(address: String): BluetoothDevice? {
        return discoveredDevices[address]
    }

    // Get a specific device info by address
    fun getDeviceInfo(address: String): DeviceInfo? {
        return deviceInfoMap[address]
    }

    // Set current device and GATT when connecting
    fun setCurrentDevice(device: BluetoothDevice?) {
        currentDevice = device
        isConnected = true
        if (device != null) {
            notifyDeviceConnected(device)
        } else {
            isConnected = false
            Log.e("BLE", "ERROR setCurrentDevice  device == null notifyDeviceConnected")

        }

        if (device != null) {
            addDevice(device)
        } else {
            isConnected = false
            Log.e("BLE", "ERROR setCurrentDevice  device == null addDevice")

        }
    }

    // Get the current device
    fun getCurrentDevice(): BluetoothDevice? {
        return currentDevice
    }

    fun setGatt(gatt: BluetoothGatt?) {
        this.bluetoothGatt = gatt
    }

    fun getGatt(): BluetoothGatt? {
        return bluetoothGatt
    }

    fun setCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        this.characteristic = characteristic
    }

    fun getCharacteristic(): BluetoothGattCharacteristic? {
        return characteristic
    }

    fun setConnected(connected: Boolean) {
        if (!connected) {
            currentDevice?.let { notifyDeviceDisconnected(it) }
        }
        isConnected = connected
    }

    fun isConnected(): Boolean {
        return isConnected
    }

    // Method for sending data over BLE
    @SuppressLint("MissingPermission")
    fun sendData(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.e("BLE", "Not connected to a device.")
            return false
        }

        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e("BLE", "Service not found: $SERVICE_UUID")
            return false
        }

        val char = service.getCharacteristic(CHARACTERISTIC_UUID) ?: run {
            Log.e("BLE", "Characteristic not found: $CHARACTERISTIC_UUID")
            return false
        }

        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            Log.e("BLE", "Characteristic is not writable.")
            return false
        }

        char.value = data
        val success = gatt.writeCharacteristic(char)
        if (success) {
            Log.d("BLE", "Data sent successfully.")
        } else {
            Log.e("BLE", "Failed to send data.")
        }
        return success
    }

    @SuppressLint("MissingPermission")
    fun sendDataChunks(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.e("BLE", "Not connected to a device.")
            return false
        }

        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e("BLE", "Service not found: $SERVICE_UUID")
            return false
        }

        val char = service.getCharacteristic(CHARACTERISTIC_UUID) ?: run {
            Log.e("BLE", "Characteristic not found: $CHARACTERISTIC_UUID")
            return false
        }

        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            Log.e("BLE", "Characteristic is not writable.")
            return false
        }

        val chunkSize = 20
        var offset = 0

        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)

            char.value = chunk
            val success = gatt.writeCharacteristic(char)

            if (!success) {
                Log.e("BLE", "Failed to send chunk at offset $offset.")
                return false
            }

            Log.d("BLE", "Chunk sent: ${chunk.joinToString(" ") { it.toString(16).padStart(2, '0') }}")

            offset += chunkSize

            // Small delay
            Thread.sleep(100)
        }

        Log.d("BLE", "All data chunks sent successfully.")
        return true
    }




    public val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            val deviceAddress = gatt.device.address

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BLE", "Connected to GATT server.")
                setConnected(true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                /*Log.i("BLE", "Disconnected from GATT server.")

                // Even if we get a disconnect callback, explicitly close the GATT
                try {
                    gatt.close()
                } catch (e: Exception) {
                    Log.e("BLE", "Error closing GATT: ${e.message}")
                }

                // Update internal state
                setConnected(false)
                if (getCurrentDevice()?.address == deviceAddress) {
                    getCurrentDevice()?.let { notifyDeviceDisconnected(it) }
                    setCurrentDevice(null)
                }

                // Clear references
                if (getGatt() == gatt) {
                    setGatt(null)
                    setCharacteristic(null)
                }*/
            }
        }



        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService((SERVICE_UUID))
                if (service != null) {
                    characteristic =
                        service.getCharacteristic((CHARACTERISTIC_UUID))
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic!!.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.i("BLE", "Notifications enabled for ${characteristic!!.uuid}")
                    } else {
                        Log.e("BLE", "Characteristic not found.")
                    }
                } else {
                    Log.e("BLE", "Service not found.")
                }
            }
        }



        private val bleNotificationBuffer = StringBuilder()
        private val END_MARKER = "NODES_INFO_END\n"
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            val chunk = String(value, Charsets.UTF_8)
            bleNotificationBuffer.append(chunk)
            Log.d("BleManager", "value: [${chunk}]")

            if (bleNotificationBuffer.contains(END_MARKER)) {
                val fullMessage = bleNotificationBuffer.toString()
                val startIndex = fullMessage.indexOf("NODES_INFO_START\n")
                val endIndex = fullMessage.indexOf(END_MARKER)

                if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                    val nodeDataString = fullMessage.substring(startIndex + "NODES_INFO_START\n".length, endIndex)
                    val parsedNodes = parseNodeInfoString(nodeDataString)
                    Log.d("BleManager", "Parsed ${parsedNodes.size} nodes. Updating StateFlow.")

                    // *** UPDATE THE STATEFLOW ***
                    _nodeListState.value = parsedNodes // Emit the new list

                } else {
                    Log.w("BleManager", "Could not find start/end markers or markers out of order.")
                    _nodeListState.value = emptyList() // Emit empty list on error? Or keep old?
                }
                bleNotificationBuffer.clear()
            } else {
                // Log.d("BleManager", "Chunk buffered. Waiting for end marker.") // Can be verbose
            }





            notifyDataReceived(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val device = getCurrentDevice()
        Log.d("BLE", "BleManager disconnect() called")

        try {
            if (bluetoothGatt != null) {
                // Disable notifications first
                characteristic?.let { char ->
                    try {
                        bluetoothGatt?.setCharacteristicNotification(char, false)
                        Log.d("BLE", "Disabled notifications for characteristic")
                    } catch (e: Exception) {
                        Log.e("BLE", "Failed to disable notifications", e)
                    }
                }

                // Disconnect from GATT
                Log.d("BLE", "Disconnecting from GATT server...")
                bluetoothGatt?.disconnect()

                // Schedule cleanup
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        bluetoothGatt?.close()
                        Log.d("BLE", "GATT connection closed")
                    } catch (e: Exception) {
                        Log.e("BLE", "Error closing GATT", e)
                    } finally {
                        // Always reset state regardless of success
                        bluetoothGatt = null
                        characteristic = null
                        setConnected(false)
                        device?.let { notifyDeviceDisconnected(it) }
                        currentDevice = null
                        Log.i("BLE", "Disconnected and closed GATT connection")
                    }
                }, 500)
            } else {
                Log.w("BLE", "No GATT connection to disconnect")
                setConnected(false)
                device?.let { notifyDeviceDisconnected(it) }
                currentDevice = null
            }
        } catch (e: Exception) {
            Log.e("BLE", "Error during disconnect: ${e.message}")
            // Force cleanup in case of exception
            bluetoothGatt = null
            characteristic = null
            setConnected(false)
            currentDevice = null
        }
    }



    companion object {
        @Volatile
        private var instance: BleManager? = null

        // Thread-safe singleton implementation using Double-Checked Locking
        fun getInstance(): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager().also { instance = it }
            }
        }
    }



    ///////////////////////////////////////////
    // Add this function inside BleManager.kt or a separate utility class

    /**
     * Parses the node information string received from the Mother Node.
     * Expected format per line:
     * NodeID:1;Status:Online;LastSeen:10s;DevID:0xABCDEF12;Battery:85%
     */
    fun parseNodeInfoString(nodeData: String): List<Node> {
        val nodes = mutableListOf<Node>()
        val lines = nodeData.split('\n').filter { it.isNotBlank() } // Split into lines and remove empty ones

        Log.d("NodeParser", "Parsing ${lines.size} lines.")

        for (line in lines) {
            if (line.contains("No nodes currently tracked.")) {
                Log.d("NodeParser", "No nodes reported by Mother Node.")
                break // Stop processing if no nodes line is found
            }

            val nodeMap = mutableMapOf<String, String>()
            val pairs = line.split(';') // Split line into key-value pairs "Key:Value"

            for (pair in pairs) {
                val parts = pair.split(':', limit = 2) // Split pair into key and value
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    nodeMap[key] = value
                } else {
                    Log.w("NodeParser", "Skipping invalid pair: '$pair' in line: '$line'")
                }
            }

            // --- Extract data using the map, handling potential missing keys ---
            val nodeIdStr = nodeMap["NodeID"]
            val status = nodeMap["Status"] ?: "Unknown" // Default if missing
            val lastSeen = nodeMap["LastSeen"] ?: "N/A"
            val devId = nodeMap["DevID"] // Optional
            val batteryStr = nodeMap["Battery"]

            if (nodeIdStr != null) {
                try {
                    // Attempt to parse Node ID
                    val nodeId = nodeIdStr // Keep Node ID as String matching your Node class? Or parse? Let's assume String for now.

                    // Parse Battery Level (extract number before '%')
                    val batteryLevel = batteryStr?.filter { it.isDigit() }?.toIntOrNull()

                    // TODO: Parse Animal detection flags if they are sent in the future
                    // Example: val animalMask = nodeMap["AnimalMask"]?.toIntOrNull() ?: 0
                    // val detectCat = (animalMask and 0x04) != 0 // Assuming ANIMAL_MASK_CAT = 0x04

                    // Create the Node object
                    val node = Node(
                        id = devId ?: nodeId, // Use DevID if available, else NodeID as unique ID
                        name = "Node $nodeId" + (devId?.let { " ($it)" } ?: ""), // Create a name
                        status = status,
                        lastSeen = lastSeen,
                        batteryLevel = batteryLevel,
                        signalStrength = null // Signal strength not currently sent
                        // Set animal detection flags based on parsed mask if implemented
                    )
                    nodes.add(node)
                    Log.d("NodeParser", "Successfully parsed node: $node")

                } catch (e: NumberFormatException) {
                    Log.e("NodeParser", "Error parsing numeric value in line: '$line'", e)
                } catch (e: Exception) {
                    Log.e("NodeParser", "Error parsing line: '$line'", e)
                }
            } else {
                Log.w("NodeParser", "Skipping line due to missing NodeID: '$line'")
            }
        } // End loop through lines

        return nodes
    }




}