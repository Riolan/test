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
import com.example.sampleviewer.NotificationHelper
import com.example.sampleviewer.database.DatabaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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

    // --- *** NEW: StateFlow to signal DB updates *** ---
    // We use AtomicLong to ensure the value changes even if updates are rapid.
    // Fragments will observe this; when it changes, they reload from DB.
    private val _databaseUpdatedSignal = MutableStateFlow(AtomicLong(System.currentTimeMillis()))
    val databaseUpdatedSignal: StateFlow<AtomicLong> = _databaseUpdatedSignal.asStateFlow()


    private val _nodeListState = MutableStateFlow<List<Node>>(emptyList())
    // Public immutable StateFlow that the UI can observe
    val nodeListState: StateFlow<List<Node>> = _nodeListState.asStateFlow()


    private val _isImageDownloadActive = MutableStateFlow(false)
    val isImageDownloadActive: StateFlow<Boolean> = _isImageDownloadActive.asStateFlow()
    private var currentDownloadingImageUUID: String? = null
    private val downloadMutex = Mutex() // To prevent race conditions when starting download



    // --- *** NEW: Function to Request Image Download *** ---
    /**
     * Requests the image for a specific event from the corresponding Edge Node.
     * Checks if download is already active or image already exists locally.
     *
     * @param nodeId The LoRa Node ID of the Edge device.
     * @param eventUUID The unique ID of the detection event/image.
     * @return Triple<Boolean, Boolean, String> - Indicates:
     * 1. Boolean: If the request was successfully *sent* via BLE (true) or not (false).
     * 2. Boolean: If the request was *attempted* (true) or skipped due to busy/exists (false).
     * 3. String: A user-friendly status message.
     */
    suspend fun requestImageForEvent(nodeId: String, eventUUID: String): Triple<Boolean, Boolean, String> {
        if (!isConnected) {
            return Triple(false, false, "Not connected to device.")
        }
        if (eventUUID.isBlank()) {
            return Triple(false, false, "Invalid Event ID.")
        }

        // Use mutex to prevent multiple requests starting simultaneously
        downloadMutex.withLock {
            if (_isImageDownloadActive.value) {
                val currentUUID = currentDownloadingImageUUID
                val message = if (currentUUID == eventUUID) "Download already in progress for this image." else "Another image download is currently active."
                Log.w("BleManager", message)
                return Triple(false, false, message) // Indicate skipped, not attempted
            }

            // Check DB if image path already exists *before* starting request
            val existingPath = withContext(Dispatchers.IO) { // DB access off main thread
                dbHelper.getImagePathForEvent(eventUUID)
            }

            if (existingPath != null) {
                // Check if file actually exists at that path (might have been deleted)
                val fileExists = File(existingPath).exists()
                if (fileExists) {
                    Log.i("BleManager", "Image for $eventUUID already exists locally at: $existingPath")
                    return Triple(false, false, "Image already downloaded.") // Indicate skipped, not attempted
                } else {
                    Log.w("BleManager", "Image path exists in DB for $eventUUID, but file missing. Proceeding with request.")
                    // Optional: Update DB to clear the missing path?
                    // withContext(Dispatchers.IO) { dbHelper.updateImagePathForEvent(eventUUID, null) }
                }
            }

            // --- Checks passed, proceed with request ---
            val command = "REQUEST_IMAGE;$nodeId;$eventUUID\n" // Include Node ID and UUID
            Log.i("BleManager", "Requesting image: $command")

            // Set state *before* sending command
            _isImageDownloadActive.value = true
            currentDownloadingImageUUID = eventUUID

            val success = sendData(command.toByteArray(Charsets.UTF_8))

            if (!success) {
                Log.e("BleManager", "Failed to initiate REQUEST_IMAGE send.")
                // Reset state if send initiation failed
                _isImageDownloadActive.value = false
                currentDownloadingImageUUID = null
                return Triple(false, true, "Failed to send request.") // Indicate attempted but failed send
            } else {
                Log.d("BleManager", "REQUEST_IMAGE command sent successfully.")
                return Triple(true, true, "Image request sent.") // Indicate attempted and sent successfully
            }
        } // End mutex lock
    }
    // --- *** End New Function *** ---





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

        private val IMAGE_DATA_END_MARKER = "\n" // Newline terminates the Base64 string
        private val DETECTION_ALERT_START_MARKER = "LoRaDetect;" // New marker for detection alerts
        private val DETECTION_ALERT_END_MARKER = "\n" // Assume newline terminates alerts too
        // --- *** UPDATED onCharacteristicChanged *** ---
        // --- *** REVISED onCharacteristicChanged - Robust Parsing *** ---

        // --- onCharacteristicChanged (Handles Notifications) ---
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(characteristic.value)
        }
        // Newer callback for API 33+
        // override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleCharacteristicChanged(value) }

        // --- Central handler for characteristic changes ---
        private fun handleCharacteristicChanged(value: ByteArray?) {
            val data = value ?: return
            val chunk = String(data, Charsets.UTF_8)
            synchronized(bleNotificationBuffer) { bleNotificationBuffer.append(chunk) }
            synchronized(bleNotificationBuffer) { processBufferedNotifications() }
        }

        // --- Process Buffer Logic ---
        private fun processBufferedNotifications() {
            var processedMessageInLoop = true
            while(processedMessageInLoop) {
                processedMessageInLoop = false
                val bufferContent = bleNotificationBuffer.toString()

                // Priority: Check for specific message types first

                // 1. Check for complete Detection Alert message
                val detectionStartIndex = bufferContent.indexOf(DETECTION_ALERT_START_MARKER)
                if (detectionStartIndex != -1) {
                    val detectionEndIndex = bufferContent.indexOf(DETECTION_ALERT_END_MARKER, detectionStartIndex + DETECTION_ALERT_START_MARKER.length)
                    if (detectionEndIndex != -1) {
                        Log.d("BleManager", "Complete Detection Alert message found.")
                        val messageContent = bufferContent.substring(
                            detectionStartIndex + DETECTION_ALERT_START_MARKER.length,
                            detectionEndIndex
                        )
                        processAndSaveDetectionAlert(messageContent) // Process the alert

                        // Remove processed message from buffer
                        val endOfMessage = detectionEndIndex + DETECTION_ALERT_END_MARKER.length
                        bleNotificationBuffer.delete(0, endOfMessage)
                        Log.d("BleManager", "Processed and removed Detection Alert message. Buffer size: ${bleNotificationBuffer.length}")
                        processedMessageInLoop = true
                        continue // Re-check buffer
                    }
                }

                // 2. Check for complete Image Data message
                val imageStartIndex = bufferContent.indexOf(IMAGE_DATA_START_MARKER)
                if (imageStartIndex != -1) {
                    val imageEndIndex = bufferContent.indexOf(IMAGE_DATA_END_MARKER, imageStartIndex + IMAGE_DATA_START_MARKER.length)
                    if (imageEndIndex != -1) {
                        Log.d("BleManager", "Complete Image Data message found.")
                        val messageContent = bufferContent.substring(
                            imageStartIndex + IMAGE_DATA_START_MARKER.length,
                            imageEndIndex
                        )
                        processImageData(messageContent) // Process extracted Base64

                        // Remove processed message from buffer
                        val endOfMessage = imageEndIndex + IMAGE_DATA_END_MARKER.length
                        bleNotificationBuffer.delete(0, endOfMessage)
                        Log.d("BleManager", "Processed and removed Image Data message. Buffer size: ${bleNotificationBuffer.length}")
                        processedMessageInLoop = true
                        continue // Re-check buffer
                    }
                }

                // 3. Check for complete Node List message
                val nodeListStartIndex = bufferContent.indexOf(NODE_LIST_START_MARKER)
                if (nodeListStartIndex != -1) {
                    val nodeListEndIndex = bufferContent.indexOf(NODE_LIST_END_MARKER, nodeListStartIndex)
                    if (nodeListEndIndex != -1) {
                        Log.d("BleManager", "Complete Node List message found.")
                        val messageContent = bufferContent.substring(
                            nodeListStartIndex + NODE_LIST_START_MARKER.length,
                            nodeListEndIndex
                        )
                        val parsedNodes = parseNodeInfoString(messageContent)
                        _nodeListState.value = parsedNodes // Update node list state

                        // Remove processed message from buffer
                        val endOfMessage = nodeListEndIndex + NODE_LIST_END_MARKER.length
                        bleNotificationBuffer.delete(0, endOfMessage)
                        Log.d("BleManager", "Processed and removed Node List message. Buffer size: ${bleNotificationBuffer.length}")
                        processedMessageInLoop = true
                        continue // Re-check buffer
                    }
                }

                // 4. Check for other message types if needed...

            } // End while

            // Safety buffer clear
            if (bleNotificationBuffer.length > 15000) {
                Log.w("BleManager", "BLE buffer large (${bleNotificationBuffer.length}), clearing.")
                bleNotificationBuffer.clear()
            }
        } // End processBufferedNotifications

    } // End gattCallback


    // Inside BleManager.kt

    // --- Image Processing Functions ---
    private fun processImageData(imageDataString: String) {
        // *** ADD LOGGING HERE ***
        Log.d("BleManager_ParseDebug", "--- Processing Image Data ---")
        Log.d("BleManager_ParseDebug", "Input String (length ${imageDataString.length}): [$imageDataString]")
        // *** END LOGGING ***

        val parts = imageDataString.split(';')
        val dataMap = mutableMapOf<String, String>()
        parts.forEach { part ->
            val keyValue = part.split(':', limit = 2)
            if (keyValue.size == 2) {
                val key = keyValue[0].trim()
                val value = keyValue[1].trim()
                dataMap[key] = value
                // *** ADD LOGGING HERE ***
                Log.v("BleManager_ParseDebug", "Parsed Pair: Key='${key}', Value='${value}'")
                // *** END LOGGING ***
            } else {
                // *** ADD LOGGING HERE ***
                Log.w("BleManager_ParseDebug", "Skipping invalid pair: '$part'")
                // *** END LOGGING ***
            }
        }

        // *** ADD LOGGING HERE ***
        Log.d("BleManager_ParseDebug", "Resulting dataMap: $dataMap")
        // *** END LOGGING ***

        // Now extract values using the map
        val nodeId = dataMap["Node"]
        val eventUUID = dataMap["EventUUID"]
        val format = dataMap["Format"]
        val encoding = dataMap["Encoding"]
        val base64Data = dataMap["Data"]

        // *** ADD LOGGING HERE ***
        Log.d("BleManager_ParseDebug", "Extracted Values: Node=$nodeId, EventUUID=$eventUUID, Format=$format, Encoding=$encoding, Data Length=${base64Data?.length ?: "null"}")
        // *** END LOGGING ***


        if (nodeId != null && eventUUID != null && format == "jpeg" && encoding == "base64" && base64Data != null) {
            Log.d("BleManager", "Image metadata successfully parsed: Node=$nodeId, EventUUID=$eventUUID") // Use parsed values in log
            CoroutineScope(Dispatchers.IO).launch {
                decodeAndSaveImage(nodeId, eventUUID, base64Data)
            }
        } else {
            Log.e("BleManager", "Failed to parse image data string. Format incorrect or missing required keys.")
            Log.e("BleManager", "Received Map for failed parse: $dataMap") // Log map on failure too
        }
    }


    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeAndSaveImage(nodeId: String, eventUUID: String, base64Data: String) {
        try {
            Log.d("BleManager", "Decoding Base64 image data (length: ${base64Data.length})...")
            val imageBytes = Base64.decode(base64Data)
            Log.d("BleManager", "Decoded ${imageBytes.size} bytes.")
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                Log.d("BleManager", "Bitmap created successfully.")
                // Use EventUUID in filename for uniqueness
                val filename = "img_${nodeId}_${eventUUID}_${System.currentTimeMillis()}.jpg"
                val filePath = saveBitmapToInternalStorage(bitmap, filename)
                //if (filePath != null) {
                Log.d("BleManager", "updateImagePathForEvent")

                val updatedRows = dbHelper.updateImagePathForEvent(eventUUID, filePath)

                Log.d("BleManager", "updateImagePathForEvent: " + updatedRows)

                if (updatedRows > 0) {
                        Log.d("BleManager", "Updated DB record for event $eventUUID with image path.")
                        // Signal DB update AFTER successful update
                        _databaseUpdatedSignal.value = AtomicLong(System.currentTimeMillis())
                        Log.d("BleManager", "Signalled DB update (image path).")
                    } else {
                        // This might happen if the alert didn't arrive or wasn't saved first.
                        Log.w("BleManager", "Could not find DB record for event $eventUUID to update image path.")
                        // Option: Insert if not found? Or just log?
                    }

                    Log.d("BleManager", "Image saved to: $filePath")
                    val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()); val currentTimestamp = timestampFormat.format(Date())
                    val description = "Detection from Node $nodeId (Event: $eventUUID)" // Include UUID
                    val newEvent = Event(id = eventUUID, description = description, timestamp = currentTimestamp, imagePath = filePath, fallbackIconResId = R.drawable.stc_logo_nobg)
                    // Optional: DB Insert
                    // val success = dbHelper.insertDetectionRecord(...)
                    // Update StateFlow
                    _latestEventState.value = newEvent // Emit the event object
                    Log.d("BleManager", "Updated latestEventState StateFlow.")
               // } else {
                    Log.e("BleManager", "Failed to save bitmap to storage.")
                //}
            } else { Log.e("BleManager", "Failed to create bitmap from decoded bytes.") }
        } catch (e: IllegalArgumentException) { Log.e("BleManager", "Error decoding Base64 string: ${e.message}")
        } catch (e: Exception) { Log.e("BleManager", "Error processing image data: ${e.message}", e) }
    }
    private fun saveBitmapToInternalStorage(bitmap: Bitmap, filename: String): String? {
        return try {
            val directory = applicationContext.filesDir; if (!directory.exists()) directory.mkdirs()
            val file = File(directory, filename); val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream); stream.flush(); stream.close()
            Log.i("BleManager", "Bitmap saved to ${file.absolutePath}"); file.absolutePath
        } catch (e: Exception) { Log.e("BleManager", "Error saving bitmap: ${e.message}", e); null }
    }

    // --- Node List Parsing Function ---
    fun parseNodeInfoString(nodeData: String): List<Node> {
        val nodes = mutableListOf<Node>()
        val lines = nodeData.split('\n').filter { it.isNotBlank() }
        for (line in lines) {
            if (line.contains("No nodes currently tracked.")) { break }
            val nodeMap = mutableMapOf<String, String>()
            val pairs = line.split(';'); for (pair in pairs) { val parts = pair.split(':', limit = 2); if (parts.size == 2) nodeMap[parts[0].trim()] = parts[1].trim() }
            val nodeIdStr = nodeMap["NodeID"]; val status = nodeMap["Status"] ?: "Unknown"; val lastSeen = nodeMap["LastSeen"] ?: "N/A"; val devId = nodeMap["DevID"]; val batteryStr = nodeMap["Battery"]
            if (nodeIdStr != null) {
                try {
                    val nodeId = nodeIdStr; val batteryLevel = batteryStr?.filter { it.isDigit() }?.toIntOrNull()
                    val node = Node(id = devId ?: nodeId, name = "Node $nodeId" + (devId?.let { " ($it)" } ?: ""), status = status, lastSeen = lastSeen, batteryLevel = batteryLevel, signalStrength = null)
                    nodes.add(node)
                } catch (e: Exception) { Log.e("NodeParser", "Error parsing line: '$line'", e) }
            } else { Log.w("NodeParser", "Skipping line due to missing NodeID: '$line'") }
        }
        return nodes
    }

    // Inside BleManager.kt

    // --- Database Helper Instance ---
    // Ensure you have this initialized (e.g., using lazy delegate)
    private val dbHelper: DatabaseHelper by lazy { DatabaseHelper(applicationContext) }

    // --- UPDATED Function to parse, SAVE, and notify detection alerts ---
    private fun processAndSaveDetectionAlert(alertDataString: String) {
        Log.d("BleManager", "Processing and saving detection alert: $alertDataString")
        // Expected format: Node:ID;EventUUID:ID;Mask:MASK;Conf:CONF;Time:TIME;BBox:X,Y,W,H
        val parts = alertDataString.split(';')
        val dataMap = mutableMapOf<String, String>()
        parts.forEach { part ->
            val keyValue = part.split(':', limit = 2)
            if (keyValue.size == 2) dataMap[keyValue[0].trim()] = keyValue[1].trim()
        }

        val nodeId = dataMap["Node"]
        val eventUUID = dataMap["EventUUID"] // Use this as the unique ID for the event
        val maskStr = dataMap["Mask"]
        val confStr = dataMap["Conf"]
        val timeStr = dataMap["Time"] // Raw timestamp string from ESP32 (e.g., seconds or millis)
        // val bboxStr = dataMap["BBox"] // Optional: Parse if needed

        // *** Ensure essential fields are present ***
        if (nodeId != null && eventUUID != null && maskStr != null && confStr != null && timeStr != null) {
            try {
                val mask = maskStr.toIntOrNull() ?: 0
                val confidence = confStr.toIntOrNull() ?: 0
                val cameraName = "Node $nodeId" // Construct camera name

                // Convert mask to primary animal name(s) for DB/notification
                val animals = mutableListOf<String>()
                // Use constants if defined, otherwise use magic numbers carefully
                if (mask and 0x01 != 0) animals.add("Squirrel") // ANIMAL_MASK_SQUIRREL
                if (mask and 0x02 != 0) animals.add("Bird")     // ANIMAL_MASK_BIRD
                if (mask and 0x04 != 0) animals.add("Cat")      // ANIMAL_MASK_CAT
                if (mask and 0x08 != 0) animals.add("Dog")      // ANIMAL_MASK_DOG
                // Add other masks as needed...
                val animalString = if (animals.isEmpty()) "Unknown" else animals.joinToString(", ")

                // --- *** SAVE TO DATABASE *** ---
                // Call the insert function from DatabaseHelper shown in the Canvas
                val dbSuccess = dbHelper.insertDetectionRecord(
                    eventUUID = eventUUID,      // Pass UUID as the key
                    animal = animalString,      // Store the derived animal name(s)
                    timestamp = timeStr,        // Store raw timestamp string from ESP32
                    camera = cameraName,
                    imagePath = null            // Image path is null for an alert
                )

                if (!dbSuccess) {
                    Log.e("BleManager", "Failed to insert detection alert (UUID: $eventUUID) into database!")
                    // Consider how to handle DB insertion failure
                    _databaseUpdatedSignal.value = AtomicLong(System.currentTimeMillis())

                } else {
                    Log.d("BleManager", "Detection alert (UUID: $eventUUID) saved to database.")
                }
                // --- *** END SAVE TO DATABASE *** ---


                // --- Trigger System Notification ---
                val title = "Animal Detected!"
                val message = "$animalString detected by $cameraName (Conf: $confidence%). Event ID: $eventUUID"
                Log.i("BleManager", "Triggering Notification: $message")
                //NotificationHelper.sendNotification(applicationContext, title, message)
                // --- End Trigger Notification ---


            } catch (e: Exception) {
                Log.e("BleManager", "Error processing/saving detection alert data: $alertDataString", e)
            }
        } else {
            Log.e("BleManager", "Failed to parse detection alert string (Missing fields?). Format incorrect.")
            Log.e("BleManager", "Received Map: $dataMap")
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




}