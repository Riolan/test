package com.example.sampleviewer // Adjust package name

import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button // Import Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast // Import Toast
import androidx.lifecycle.findViewTreeLifecycleOwner // Required for lifecycleScope in ViewHolder
import androidx.lifecycle.lifecycleScope // Required for launching coroutine
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R // Adjust R import
import com.example.sampleviewer.bt.BleManager // Import BleManager
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.* // Import coroutines

class ImageGalleryAdapter(
    // Removed onItemClicked lambda, clicks handled by specific buttons now
    // private val onItemClicked: (Event) -> Unit
) : ListAdapter<Event, ImageGalleryAdapter.EventImageViewHolder>(EventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_event_image, parent, false)
        // Pass BleManager instance or context needed to get it
        val bleManager = BleManager.getInstance(parent.context.applicationContext)
        return EventImageViewHolder(view, bleManager) // Pass BleManager
    }

    override fun onBindViewHolder(holder: EventImageViewHolder, position: Int) {
        val event = getItem(position)
        holder.bind(event)
    }

    // ViewHolder class using findViewById
    class EventImageViewHolder(
        itemView: View, // Pass the inflated item view
        private val bleManager: BleManager // Receive BleManager instance
        // Removed onItemClicked parameter
    ) : RecyclerView.ViewHolder(itemView) {

        // Find views within the item layout
        private val imageView: ImageView = itemView.findViewById(R.id.item_image_view)
        private val timestampText: TextView = itemView.findViewById(R.id.item_timestamp_text)
        private val downloadButton: Button = itemView.findViewById(R.id.item_button_download) // Find button
        private var currentEvent: Event? = null
        private var imageLoadingJob: Job? = null

        init {
            // General item click (optional - e.g., for full screen view)
            itemView.setOnClickListener {
                currentEvent?.let { event ->
                    Log.d("Adapter", "Item clicked: ${event.id}")
                    // onItemClicked(event) // Call original listener if needed
                }
            }

            // --- Set listener for the download button ---
            downloadButton.setOnClickListener {
                currentEvent?.let { event ->
                    Log.d("Adapter", "Download button clicked for event: ${event.id}")
                    // Check if image already exists before requesting
                    if (event.imagePath != null && File(event.imagePath).exists()) {
                        Toast.makeText(itemView.context, "Image already downloaded", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener // Exit listener
                    }

                    // Launch coroutine within the ViewHolder's lifecycle scope
                    // Requires the RecyclerView item view to be attached to a window
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        Log.d("Adapter", "Requesting image for Node TODO WE NEED TO ENSURE " +
                                "THAT WE ARE USING DEVICE ID (DEVICE ID IS CONSTANT, NODE IS NOT). " +
                                "$\"0\", Event ${event.id}")
                        // Disable button while request is in progress?
                        // downloadButton.isEnabled = false
                        try {
                            //TODO
                            val (sent, attempted, message) = bleManager.requestImageForEvent("0", event.id)
                            // Show feedback (runs on Main thread because launched from lifecycleScope)
                            Toast.makeText(itemView.context, message, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("Adapter", "Error requesting image", e)
                            Toast.makeText(itemView.context, "Error requesting image", Toast.LENGTH_SHORT).show()
                        } finally {
                            // Re-enable button if needed, maybe based on bleManager.isImageDownloadActive state?
                            // downloadButton.isEnabled = true
                        }
                    } ?: run {
                        // Fallback if lifecycle owner not found (view detached?)
                        Log.e("Adapter", "Could not find LifecycleOwner for coroutine scope.")
                        Toast.makeText(itemView.context, "Error: Cannot request image now", Toast.LENGTH_SHORT).show()
                    }
                } ?: run {
                    Log.w("Adapter", "Download clicked but currentEvent is null")
                }
            }
            // --- End Download Button Listener ---
        }

        fun bind(event: Event) {
            currentEvent = event
            timestampText.text = event.timestamp
            Log.d("Adapter", "Binding event ID: ${event.id}, Path: ${event.imagePath}") // <<< ADD THIS LOG

            // Cancel any previous loading job
            imageLoadingJob?.cancel()

            // --- Button State ---
            // Disable download button if image already exists
            val imageExists = event.imagePath != null && File(event.imagePath).exists()
            downloadButton.isEnabled = !imageExists
            downloadButton.text = if (imageExists) "ALREADY DOWNLOADED" else "DOWNLOAD"
            // TODO: Add logic to reflect ongoing download state from BleManager if needed
            Log.d("Adapter", "Image exists check for ${event.id}: $imageExists") // <<< ADD THIS LOG

            // --- Manual Image Loading ---
            if (imageExists) { // Only load if path exists and file exists
                Log.d("Adapter", "Attempting to load image for ${event.id}...") // <<< ADD THIS LOG
                imageView.setImageResource(event.fallbackIconResId) // Set placeholder FIRST

                imageLoadingJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val imageFile = File(event.imagePath!!) // Path is not null here
                        val bitmapToShow = BitmapFactory.decodeStream(FileInputStream(imageFile))
                        withContext(Dispatchers.Main) {
                            if (bitmapToShow != null) {
                                Log.d("Adapter", "Setting bitmap for ${event.id}") // <<< ADD THIS LOG

                                imageView.setImageBitmap(bitmapToShow)
                            } else {

                                Log.w("Adapter", "Bitmap decode failed for ${event.id}") // <<< ADD THIS LOG

                                imageView.setImageResource(event.fallbackIconResId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Adapter", "Error loading image: ${event.imagePath}", e)
                        withContext(Dispatchers.Main) { imageView.setImageResource(R.drawable.baseline_error_outline_24) }
                    }
                }
            } else {
                Log.d("Adapter", "Setting placeholder for ${event.id} (no image path or file missing)") // <<< ADD THIS LOG

                // No image path or file doesn't exist, set placeholder
                imageView.setImageResource(event.fallbackIconResId)
            }
            // --- End Manual Image Loading ---
        }
    }

    // DiffUtil Callback (same as before)
    class EventDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem.id == newItem.id // Use Event UUID
        }
        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem == newItem // Compare all fields
        }
    }
}