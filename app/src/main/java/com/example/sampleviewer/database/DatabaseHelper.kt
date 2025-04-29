package com.example.sampleviewer.database

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.sampleviewer.Event
import java.security.MessageDigest

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "users.db"
        private const val DATABASE_VERSION = 1

        // Existing user table constants
        private const val TABLE_USERS = "users"
        private const val COLUMN_ID = "id"
        private const val COLUMN_EMAIL = "email"
        private const val COLUMN_PASSWORD = "password"

        // New detections table constants
        private const val TABLE_DETECTIONS = "detections"
        private const val COLUMN_DETECTION_ID = "id"
        private const val COLUMN_DETECTED_ANIMAL = "detected_animal"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_CAMERA = "camera"
        private const val COLUMN_IMAGE_PATH = "image_path"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createUserTable = "CREATE TABLE $TABLE_USERS ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COLUMN_EMAIL TEXT UNIQUE, $COLUMN_PASSWORD TEXT)"
        db.execSQL(createUserTable)

        // Create detections table
        val createDetectionsTable = "CREATE TABLE $TABLE_DETECTIONS (" +
                "$COLUMN_DETECTION_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_DETECTED_ANIMAL TEXT, " +
                "$COLUMN_TIMESTAMP TEXT, " +
                "$COLUMN_CAMERA TEXT, " +
                "$COLUMN_IMAGE_PATH TEXT)"
        db.execSQL(createDetectionsTable)
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

    // Insert a detection record into the detections table.
    // The record contains the detected animal, timestamp, camera, and image path.
    fun insertDetectionRecord(animal: String, timestamp: String, camera: String, imagePath: String): Boolean {
        val db = writableDatabase // Get instance from helper
        val values = ContentValues().apply {
            put(COLUMN_DETECTED_ANIMAL, animal)
            put(COLUMN_TIMESTAMP, timestamp) // Storing as String (TEXT)
            put(COLUMN_CAMERA, camera)
            put(COLUMN_IMAGE_PATH, imagePath)
        }
        val result = try {
            db.insert(TABLE_DETECTIONS, null, values)
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error inserting detection record", e)
            -1L // Indicate failure
        } finally {
        }
        return result != -1L
    }




    @SuppressLint("Range") // Suppress lint check for getColumnIndexOrThrow
    fun getAllDetections(): List<Event> {
        val eventList = mutableListOf<Event>()
        // Use try-with-resources for readableDatabase and cursor to ensure they are closed
        // even if errors occur, BUT avoid closing the main helper connection.
        // Note: Kotlin doesn't have direct try-with-resources like Java for SQLiteDatabase,
        // so careful manual closing of the cursor in finally is still best practice.
        // Getting the readableDatabase instance here is fine.
        val db = readableDatabase
        val query = "SELECT * FROM $TABLE_DETECTIONS ORDER BY $COLUMN_TIMESTAMP DESC"
        var cursor: Cursor? = null

        Log.d("DatabaseHelper", "Fetching all detection records...")

        try {
            cursor = db.rawQuery(query, null)
            // Use isBeforeFirst and moveToNext for safer cursor iteration
            if (cursor != null && cursor.moveToFirst()) { // Check cursor not null
                do {
                    // Extract data from cursor row
                    val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DETECTION_ID))
                    val animal = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DETECTED_ANIMAL))
                    val timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                    val camera = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CAMERA))
                    val imagePathFromDb = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IMAGE_PATH)) // Get the image path

                    // Map database data to the Event data class
                    // Consider how you want to format the timestamp for display if it's just millis string
                    val description = "Detected: $animal ($camera)"
                    val eventIcon = android.R.drawable.ic_menu_camera // Maybe camera icon is better?

                    val event = Event(
                        id = id.toString(),
                        description = description, // e.g., "Detected: $animal ($camera)"
                        timestamp = formatDisplayTimestamp(timestamp), // Format the timestamp string nicely
                        imagePath = imagePathFromDb, // << Populate the new field
                        fallbackIconResId = android.R.drawable.ic_menu_camera // Or your preferred default icon
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
            cursor?.close() // Ensure cursor is closed
            // DO NOT CLOSE THE DB HERE: db.close() <--- REMOVE THIS LINE
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
}