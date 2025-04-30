package com.example.sampleviewer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.UUID
import android.os.Handler
import android.os.Looper
import com.example.myapplication.R
import com.example.sampleviewer.Event
import com.example.sampleviewer.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface BleConnectionListener {
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
}

interface BleDataListener {
    fun onBleDataReceived(data: ByteArray)
}


class BleManager private constructor(private val applicationContext: Context) {
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

    // --- StateFlow for the latest received Event ---
    // This will hold the event data including the path to the saved image
    private val _latestEventState = MutableStateFlow<Event?>(null)
    val latestEventState: StateFlow<Event?> = _latestEventState.asStateFlow()


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
        private var isReceivingImage = false // State flag for image reassembly
        private val NODE_LIST_START_MARKER = "NODES_INFO_START\n"
        private val NODE_LIST_END_MARKER = "NODES_INFO_END\n"
        private val IMAGE_DATA_START_MARKER = "LoRaImage;"
        private val MESSAGE_TERMINATOR = "\n" // Assuming newline terminates all message types
        // --- *** UPDATED onCharacteristicChanged *** ---
        // --- *** REVISED onCharacteristicChanged - Robust Parsing *** ---
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            val chunk = String(value, Charsets.UTF_8)
            // Log.d("BleManager", "Received chunk: $chunk") // Verbose

            bleNotificationBuffer.append(chunk) // Append new chunk

            // --- Process Buffer for Complete Messages (Loop until no more complete messages found) ---
            var processedMessageInLoop = true // Flag to re-check buffer after processing one message
            while(processedMessageInLoop) {
                processedMessageInLoop = false // Reset flag for this iteration

                // 1. Check for complete Node List message
                val nodeListStartIndex = bleNotificationBuffer.indexOf(NODE_LIST_START_MARKER)
                if (nodeListStartIndex != -1) {
                    val nodeListEndIndex = bleNotificationBuffer.indexOf(NODE_LIST_END_MARKER, nodeListStartIndex)
                    if (nodeListEndIndex != -1) {
                        // Found complete node list message
                        Log.d("BleManager", "Complete Node List message found in buffer.")
                        val messageContent = bleNotificationBuffer.substring(
                            nodeListStartIndex + NODE_LIST_START_MARKER.length,
                            nodeListEndIndex
                        )
                        val parsedNodes = parseNodeInfoString(messageContent)
                        Log.d("BleManager", "Parsed ${parsedNodes.size} nodes. Updating StateFlow.")
                        _nodeListState.value = parsedNodes // Update state

                        // Remove the processed message (including markers) from the buffer
                        val endOfMessage = nodeListEndIndex + NODE_LIST_END_MARKER.length
                        bleNotificationBuffer.delete(0, endOfMessage) // Assume message is at the start
                        Log.d("BleManager", "Processed and removed Node List message. Buffer size: ${bleNotificationBuffer.length}")
                        processedMessageInLoop = true // Signal to check buffer again
                        continue // Restart while loop to check for more messages
                    }
                    // Else: Start marker found, but not end marker yet. Keep buffering.
                }

                // 2. Check for complete Image Data message
                val imageStartIndex = bleNotificationBuffer.indexOf(IMAGE_DATA_START_MARKER)
                if (imageStartIndex != -1) {
                    // Look for the newline terminator *after* the start marker
                    val imageEndIndex = bleNotificationBuffer.indexOf(MESSAGE_TERMINATOR, imageStartIndex + IMAGE_DATA_START_MARKER.length)
                    if (imageEndIndex != -1) {
                        // Found complete image message
                        Log.d("BleManager", "Complete Image Data message found in buffer.")

                        // Extract the content between the start marker and the end marker (newline)
                        val messageContent = bleNotificationBuffer.substring(
                            imageStartIndex + IMAGE_DATA_START_MARKER.length,
                            imageEndIndex // Extract content up to (but not including) the newline
                        )
                        processImageData(messageContent) // Process the extracted Base64 data

                        // Remove the processed message (including start marker and terminator) from the buffer
                        val endOfMessage = imageEndIndex + MESSAGE_TERMINATOR.length
                        bleNotificationBuffer.delete(0, endOfMessage) // Assume message is at the start
                        Log.d("BleManager", "Processed and removed Image Data message. Buffer size: ${bleNotificationBuffer.length}")
                        processedMessageInLoop = true // Signal to check buffer again
                        continue // Restart while loop to check for more messages
                    }
                    // Else: Start marker found, but not end marker yet. Keep buffering.
                }

                // 3. Check for other message types if needed...

            } // End while(processedMessageInLoop)

            // If buffer gets excessively large without finding markers, maybe clear it?
            // Arbitrary length based off hoping to fix it if it gets too large.
            if (bleNotificationBuffer.length > 16000) { // Example threshold
                Log.w("BleManager", "BLE buffer large (${bleNotificationBuffer.length}), potentially stuck. Clearing.")
                bleNotificationBuffer.clear()
            }

        } // --- *** End REVISED onCharacteristicChanged *** ---

    } // End gattCallback

    private fun processImageData(imageDataString: String) {
        Log.d("BleManager", "Processing received image data string...")
        // Expected format: Node:ID;ImgID:ID;Format:jpeg;Encoding:base64;Data:BASE64_STRING
        val parts = imageDataString.split(';')
        val dataMap = mutableMapOf<String, String>()
        parts.forEach { part ->
            val keyValue = part.split(':', limit = 2)
            if (keyValue.size == 2) {
                dataMap[keyValue[0]] = keyValue[1]
            }
        }

        val nodeId = dataMap["Node"]
        val imgId = dataMap["ImgID"]
        val format = dataMap["Format"]
        val encoding = dataMap["Encoding"]
        val base64Data = dataMap["Data"]

        if (nodeId != null && imgId != null && format == "jpeg" && encoding == "base64" && base64Data != null) {
            Log.d("BleManager", "Image metadata parsed: Node=$nodeId, ImgID=$imgId")
            // Decode and save the image in a background thread
            CoroutineScope(Dispatchers.IO).launch {
                decodeAndSaveImage(nodeId, imgId, base64Data)
            }
        } else {
            Log.e("BleManager", "Failed to parse image data string. Format incorrect.")
            Log.e("BleManager", "Received: $imageDataString")

        }
    }


    // --- NEW Function to decode Base64, save Bitmap, update DB, and notify UI ---
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeAndSaveImage(nodeId: String, imgId: String, base64Data: String) {
        try {
            Log.d("BleManager", "Decoding Base64 image data (length: ${base64Data.length})...")
            // Decode Base64 string to byte array
            val imageBytes = Base64.decode(base64Data) // Use Android's Base64
            Log.d("BleManager", "Decoded ${imageBytes.size} bytes.")

            // Convert byte array to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            if (bitmap != null) {
                Log.d("BleManager", "Bitmap created successfully.")
                // Save the bitmap to internal storage
                val filePath = saveBitmapToInternalStorage(bitmap, "img_${nodeId}_${imgId}_${System.currentTimeMillis()}.jpg")

                if (filePath != null) {
                    Log.d("BleManager", "Image saved to: $filePath")

                    // --- Create Event Object ---
                    val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val currentTimestamp = timestampFormat.format(Date())

                    // TODO: Get actual animal description based on detection logic later
                    val description = "Detection from Node $nodeId"

                    val newEvent = Event(
                        id = imgId, // Use ImgID from LoRa as a unique ID for this event? Or generate UUID?
                        description = description,
                        timestamp = currentTimestamp,
                        imagePath = filePath, // The path where the image is saved
                        fallbackIconResId = android.R.drawable.ic_menu_camera // Replace with your actual placeholder drawable
                    )

                    // --- Store in Database (Example - Requires dbHelper instance) ---
                    // val success = dbHelper.insertDetectionRecord(
                    //     animal = description, // Or get specific animal later
                    //     timestamp = currentTimestamp,
                    //     camera = "Node $nodeId",
                    //     imagePath = filePath
                    // )
                    // if (success) {
                    //     Log.d("BleManager", "Detection record inserted into DB.")
                    // } else {
                    //     Log.e("BleManager", "Failed to insert detection record into DB.")
                    // }

                    // --- Notify UI by updating StateFlow ---
                    _latestEventState.value = newEvent
                    Log.d("BleManager", "Updated latestEventState StateFlow.")

                } else {
                    Log.e("BleManager", "Failed to save bitmap to storage.")
                }
            } else {
                Log.e("BleManager", "Failed to create bitmap from decoded bytes.")
            }
        } catch (e: IllegalArgumentException) {
            Log.e("BleManager", "Error decoding Base64 string: ${e.message}")
        } catch (e: Exception) {
            Log.e("BleManager", "Error processing image data: ${e.message}", e)
        }
    }

    // --- NEW Helper function to save Bitmap to internal storage ---
    private fun saveBitmapToInternalStorage(bitmap: Bitmap, filename: String): String? {
        return try {
            // Get the directory for the app's private files.
            val directory = applicationContext.filesDir // Or getExternalFilesDir(null) for external?
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, filename)
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream) // Compress as JPEG
            stream.flush()
            stream.close()
            Log.i("BleManager", "Bitmap saved to ${file.absolutePath}")
            file.absolutePath // Return the absolute path
        } catch (e: Exception) {
            Log.e("BleManager", "Error saving bitmap: ${e.message}", e)
            null
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
        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context).also { instance = it }
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