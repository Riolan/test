package com.example.sampleviewer // Adjust package name

import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R // Adjust R import
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.* // Import for basic background loading example

class ImageGalleryAdapter(
    private val onItemClicked: (Event) -> Unit // Lambda for click events
) : ListAdapter<Event, ImageGalleryAdapter.EventImageViewHolder>(EventDiffCallback()) {

    // Coroutine scope tied to the adapter lifecycle (or pass one in)
    // Be careful with scopes tied directly to Adapters if not managed well.
    // private val adapterScope = CoroutineScope(Dispatchers.Main + Job()) // Example scope

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventImageViewHolder {
        // Inflate layout the traditional way
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_event_image, parent, false)
        return EventImageViewHolder(view, onItemClicked) // Pass the inflated view
    }

    override fun onBindViewHolder(holder: EventImageViewHolder, position: Int) {
        val event = getItem(position)
        holder.bind(event)
    }

    // ViewHolder class using findViewById
    class EventImageViewHolder(
        itemView: View, // Pass the inflated item view
        private val onItemClicked: (Event) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        // Find views within the item layout
        private val imageView: ImageView = itemView.findViewById(R.id.item_image_view)
        private val timestampText: TextView = itemView.findViewById(R.id.item_timestamp_text)
        private var currentEvent: Event? = null
        private var imageLoadingJob: Job? = null // To manage background loading coroutine

        init {
            itemView.setOnClickListener {
                currentEvent?.let { event ->
                    onItemClicked(event)
                }
            }
        }

        fun bind(event: Event) {
            currentEvent = event
            timestampText.text = event.timestamp

            // Cancel any previous loading job for this ViewHolder
            imageLoadingJob?.cancel()

            // --- Manual Image Loading ---
            if (event.imagePath != null) {
                // **IMPORTANT**: Loading/decoding bitmaps directly on the main thread
                // can cause UI freezes (ANRs). Use a background thread for real apps.
                // Example using Coroutines (basic):
                imageLoadingJob = CoroutineScope(Dispatchers.IO).launch { // Launch background task
                    try {
                        val imageFile = File(event.imagePath)
                        var bitmapToShow: android.graphics.Bitmap? = null
                        if (imageFile.exists()) {
                            // Decode bitmap from file in background
                            // Consider adding BitmapFactory.Options for downsampling large images
                            // val options = BitmapFactory.Options().apply { inSampleSize = 4 } // Example downsampling
                            bitmapToShow = BitmapFactory.decodeStream(FileInputStream(imageFile)) // Use stream
                        } else {
                            Log.w("Adapter", "Image file not found: ${event.imagePath}")
                        }

                        withContext(Dispatchers.Main) { // Switch back to Main thread for UI update
                            if (bitmapToShow != null) {
                                imageView.setImageBitmap(bitmapToShow)
                            } else {
                                // File not found or decode failed, show placeholder/error
                                imageView.setImageResource(event.fallbackIconResId)
                                // Optionally set error drawable: imageView.setImageResource(R.drawable.ic_error)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Adapter", "Error loading image: ${event.imagePath}", e)
                        withContext(Dispatchers.Main) {
                            // Show error drawable on main thread
                            imageView.setImageResource(R.drawable.stc_logo) // Define ic_error
                        }
                    }
                }
                // Set placeholder immediately while background loads
                imageView.setImageResource(event.fallbackIconResId)

            } else {
                // No image path, just set placeholder
                imageView.setImageResource(event.fallbackIconResId)
            }
            // --- End Manual Image Loading ---
        }
    }

    // DiffUtil Callback (same as before)
    class EventDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem == newItem
        }
    }
}