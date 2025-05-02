package com.example.sampleviewer.database

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.myapplication.R
import com.example.sampleviewer.Event
import java.security.MessageDigest

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "users.db"
        private const val DATABASE_VERSION = 2 //


        // Existing user table constants
        private const val TABLE_USERS = "users"
        private const val COLUMN_ID = "id"
        private const val COLUMN_EMAIL = "email"
        private const val COLUMN_PASSWORD = "password"

        // Detections table constants (CHANGED)
        private const val TABLE_DETECTIONS = "detections"
        // *** Use Event UUID as Primary Key ***
        private const val COLUMN_EVENT_UUID = "event_uuid" // TEXT PRIMARY KEY
        private const val COLUMN_DETECTED_ANIMAL = "detected_animal"
        private const val COLUMN_TIMESTAMP = "timestamp" // Store raw string from ESP32 (e.g., seconds or millis)
        private const val COLUMN_CAMERA = "camera" // e.g., "Node 1"
        private const val COLUMN_IMAGE_PATH = "image_path" // TEXT, Nullable
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createUserTable = "CREATE TABLE $TABLE_USERS ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COLUMN_EMAIL TEXT UNIQUE, $COLUMN_PASSWORD TEXT)"
        db.execSQL(createUserTable)

        // *** Create detections table with UUID as TEXT PRIMARY KEY ***
        val createDetectionsTable = "CREATE TABLE $TABLE_DETECTIONS (" +
                "$COLUMN_EVENT_UUID TEXT PRIMARY KEY, " + // Changed
                "$COLUMN_DETECTED_ANIMAL TEXT, " +
                "$COLUMN_TIMESTAMP TEXT, " +
                "$COLUMN_CAMERA TEXT, " +
                "$COLUMN_IMAGE_PATH TEXT)" // Nullable
        db.execSQL(createDetectionsTable)
        Log.i("DatabaseHelper", "Detections table created with UUID as PK.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_USERS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DETECTIONS")
        onCreate(db)
    }

    // Hash function using SHA-256 for passwords
    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // Register a new user (stores hashed password)
    fun registerUser(email: String, password: String): Boolean {
        val db = writableDatabase
        val hashedPassword = hashPassword(password)
        val values = ContentValues().apply {
            put(COLUMN_EMAIL, email)
            put(COLUMN_PASSWORD, hashedPassword)
        }
        val result = db.insert(TABLE_USERS, null, values)
        db.close()
        return result != -1L
    }

    // Validate user login (compares hashed password)
    fun isValidUser(email: String, password: String): Boolean {
        val db = readableDatabase
        val hashedPassword = hashPassword(password)
        val query = "SELECT * FROM $TABLE_USERS WHERE $COLUMN_EMAIL = ? AND $COLUMN_PASSWORD = ?"
        val cursor = db.rawQuery(query, arrayOf(email, hashedPassword))
        val isValid = cursor.count > 0
        cursor.close()
        db.close()
        return isValid
    }

    fun isValidUserHashed(email: String, passwordHash: String): Boolean {
        val db = readableDatabase
        val query = "SELECT * FROM users WHERE email = ? AND password = ?"
        val cursor = db.rawQuery(query, arrayOf(email, passwordHash))
        val isValid = cursor.count > 0
        cursor.close()
        db.close()
        return isValid
    }

    fun getHashedPassword(email: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT password FROM users WHERE email = ?", arrayOf(email))
        val result = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        db.close()
        return result
    }

    /**
     * Inserts a detection record using the Event UUID as the primary key.
     * Replaces existing record on conflict.
     */
    fun insertDetectionRecord(eventUUID: String, animal: String, timestamp: String, camera: String, imagePath: String?): Boolean {
        if (eventUUID.isBlank()) {
            Log.e("DatabaseHelper", "Attempted to insert detection record with blank UUID.")
            return false
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_EVENT_UUID, eventUUID) // Use UUID as key
            put(COLUMN_DETECTED_ANIMAL, animal)
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_CAMERA, camera)
            put(COLUMN_IMAGE_PATH, imagePath) // Can be null initially
        }
        var result = -1L
        try {
            // Use CONFLICT_REPLACE: if a record with this UUID already exists (e.g., alert arrived again),
            // update it. If you only want to insert if new, use CONFLICT_IGNORE or check first.
            result = db.insertWithOnConflict(TABLE_DETECTIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error inserting/replacing detection record UUID $eventUUID", e)
        } finally {
            // db.close() // Don't close helper instance
        }
        if (result == -1L) {
            Log.e("DatabaseHelper", "Failed to insert/replace detection record for UUID $eventUUID")
        } else {
            Log.d("DatabaseHelper", "Successfully inserted/replaced detection record UUID: $eventUUID (Result ID: $result)")
        }
        return result != -1L
    }

    /**
     * Updates the image_path for a specific detection record identified by its event UUID.
     * Returns the number of rows affected (should be 0 or 1).
     */
    fun updateImagePathForEvent(eventUUID: String, imagePath: String?): Int {
        if (eventUUID.isBlank()) {
            Log.e("DatabaseHelper", "Attempted to update image path with blank UUID.")
            return 0
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_IMAGE_PATH, imagePath) // Set new path (or null)
        }
        val selection = "$COLUMN_EVENT_UUID = ?"
        val selectionArgs = arrayOf(eventUUID)
        var rowsAffected = 0
        try {
            rowsAffected = db.update(TABLE_DETECTIONS, values, selection, selectionArgs)
            Log.d("DatabaseHelper", "Updated image path for UUID $eventUUID. Rows affected: $rowsAffected")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error updating image path for UUID $eventUUID", e)
        } finally {
            // db.close() // Don't close helper instance
        }
        return rowsAffected
    }


    /**
     * Retrieves all detection events, ordered by timestamp descending.
     */
    @SuppressLint("Range")
    fun getAllDetectionEvents(): List<Event> {
        val eventList = mutableListOf<Event>()
        val db = readableDatabase
        // Order by timestamp - assuming it's stored as milliseconds or seconds string
        // If stored as seconds, ensure sufficient zero-padding for correct string sort,
        // or cast to INTEGER in query for numeric sort. Millis string usually sorts ok.
        val query = "SELECT * FROM $TABLE_DETECTIONS ORDER BY $COLUMN_TIMESTAMP DESC"
        var cursor: Cursor? = null

        Log.d("DatabaseHelper", "Fetching all detection events...")
        try {
            cursor = db.rawQuery(query, null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    // *** Read UUID as the primary ID ***
                    val eventUUID = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EVENT_UUID))
                    val animal = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETECTED_ANIMAL)) ?: "Unknown"
                    val timestampStr = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)) ?: "0"
                    val camera = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CAMERA)) ?: "Unknown Cam"
                    val imagePathFromDb = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH)) // Can be null

                    val description = "$animal by $camera"
                    val formattedTimestamp = formatDisplayTimestamp(timestampStr) // Format for display

                    val event = Event(
                        id = eventUUID, // Use the UUID from the DB as the Event ID
                        description = description,
                        timestamp = formattedTimestamp,
                        imagePath = imagePathFromDb, // Populate from DB
                        fallbackIconResId = R.drawable.baseline_camera_outdoor_24 // Your placeholder
                    )
                    eventList.add(event)
                } while (cursor.moveToNext())
                Log.d("DatabaseHelper", "Found ${eventList.size} detection records.")
            } else {
                Log.d("DatabaseHelper", "No detection records found.")
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error while getting detections", e)
        } finally {
            cursor?.close()
            // db.close() // Don't close helper instance
        }
        return eventList
    }

    // Optional helper function in DatabaseHelper or a separate Util class
    private fun formatDisplayTimestamp(timestampString: String): String {
        return try {
            // Assuming timestampString is milliseconds stored as TEXT
            val millis = timestampString.toLong()
            // Format it nicely for display (e.g., "Apr 27, 2025 4:14 PM")
            // You'll need SimpleDateFormat or java.time APIs
            android.text.format.DateFormat.format("MMM dd, yyyy h:mm a", millis).toString()
        } catch (e: NumberFormatException) {
            Log.w("DatabaseHelper", "Could not parse timestamp string: $timestampString")
            timestampString // Return original string if parsing fails
        }
    }

    private fun clearTable(tableName: String): Boolean {
        // TODO:  Prevent accidental clearing in release builds if called unexpectedly
        Log.e("DatabaseHelper", "Attempted to clear table '$tableName' in a RELEASE build! Aborting.")
        //return false

        val db = writableDatabase
        var rowsDeleted = 0
        try {
            rowsDeleted = db.delete(tableName, "1", null) // "1" means delete all rows
            Log.w("DatabaseHelper", "Cleared table: $tableName. Rows deleted: $rowsDeleted")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error clearing table $tableName", e)
        } finally {
            db.close()
        }
        return rowsDeleted >= 0 // Return true even if 0 rows were deleted (table was empty)
    }

    /** Clears the Users table. Only works in DEBUG builds. */
    fun clearUsersTable(): Boolean {
        return clearTable(TABLE_USERS)
    }

    /** Clears the Detections table. Only works in DEBUG builds. */
    fun clearDetectionsTable(): Boolean {
        return clearTable(TABLE_DETECTIONS)
    }

    /** Retrieves a list of email addresses for all registered users. */
    @SuppressLint("Range")
    fun getAllUsers(): List<String> {
        val userList = mutableListOf<String>()
        val db = readableDatabase
        val query = "SELECT $COLUMN_EMAIL FROM $TABLE_USERS ORDER BY $COLUMN_EMAIL"
        var cursor: Cursor? = null
        Log.d("DatabaseHelper", "Fetching all user emails...")
        try {
            cursor = db.rawQuery(query, null)
            if (cursor.moveToFirst()) {
                do {
                    val email = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL))
                    userList.add(email)
                } while (cursor.moveToNext())
                Log.d("DatabaseHelper", "Found ${userList.size} users.")
            } else {
                Log.d("DatabaseHelper", "No users found in the database.")
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error getting users", e)
        } finally {
            cursor?.close()
            db.close()
        }
        return userList
    }



    // --- *** NEW: Function to get image path for a specific event *** ---
    /**
     * Retrieves the stored image path for a given event UUID.
     * Returns null if the event is not found or if the path is null/empty in the DB.
     */
    @SuppressLint("Range")
    fun getImagePathForEvent(eventUUID: String): String? {
        if (eventUUID.isBlank()) return null

        val db = readableDatabase
        val query = "SELECT $COLUMN_IMAGE_PATH FROM $TABLE_DETECTIONS WHERE $COLUMN_EVENT_UUID = ?"
        val selectionArgs = arrayOf(eventUUID)
        var cursor: Cursor? = null
        var imagePath: String? = null

        try {
            cursor = db.rawQuery(query, selectionArgs)
            if (cursor != null && cursor.moveToFirst()) {
                // Get path, check if it's null or empty in the database column
                imagePath = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH))
                if (imagePath?.isBlank() == true) { // Treat blank paths as null
                    imagePath = null
                }
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error getting image path for UUID $eventUUID", e)
        } finally {
            cursor?.close()
            // db.close() // Don't close helper instance
        }
        Log.d("DatabaseHelper", "Image path for UUID $eventUUID: $imagePath")
        return imagePath
    }
}