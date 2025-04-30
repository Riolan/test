package com.example.sampleviewer // Adjust package name

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast // Import Toast
// import androidx.lifecycle.ViewModelProvider // Keep commented for now
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R // Adjust R import
import com.example.sampleviewer.database.DatabaseHelper // Import DatabaseHelper
// Remove ViewBinding import
// import com.example.myapplication.databinding.FragmentImageGalleryBinding
import kotlinx.coroutines.Dispatchers // Import Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext // Import withContext

class ImageGalleryFragment : Fragment() {

    // Declare views - initialize in onViewCreated
    private lateinit var galleryRecyclerView: RecyclerView
    private lateinit var galleryEmptyText: TextView
    private lateinit var imageAdapter: ImageGalleryAdapter
    private lateinit var databaseHelper: DatabaseHelper // Add DatabaseHelper instance
    // private lateinit var galleryViewModel: GalleryViewModel // TODO: Introduce a ViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize DatabaseHelper here, requires context
        databaseHelper = DatabaseHelper(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? { // Return nullable View
        // Inflate the layout the traditional way
        return inflater.inflate(R.layout.fragment_image_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // --- Initialize views using findViewById ---
        galleryRecyclerView = view.findViewById(R.id.gallery_recycler_view)
        galleryEmptyText = view.findViewById(R.id.gallery_empty_text)
        // --- End Initialize views ---

        // TODO: Initialize ViewModel
        // galleryViewModel = ViewModelProvider(this).get(GalleryViewModel::class.java)

        setupRecyclerView()
        // setupObservers() // Keep commented until ViewModel is used

        // --- Load data from Database ---
        loadEventsFromDatabase()
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageGalleryAdapter { event ->
            // Handle click on an image item
            Log.d("ImageGalleryFragment", "Image clicked: ${event.id}, Path: ${event.imagePath}")
            // TODO: Implement action on click (e.g., show full screen)
            Toast.makeText(requireContext(), "Clicked: ${event.description}", Toast.LENGTH_SHORT).show()
        }

        galleryRecyclerView.apply {
            adapter = imageAdapter
            layoutManager = GridLayoutManager(requireContext(), 3) // Adjust spanCount
        }
    }

    // --- REMOVED setupObservers() for now, as we load directly ---
    // private fun setupObservers() { ... }

    // --- Function to load events from the database ---
    private fun loadEventsFromDatabase() {
        // Use lifecycleScope to launch coroutine tied to fragment's lifecycle
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d("ImageGalleryFragment", "Starting to load events from database...")
            galleryEmptyText.text = "Loading Images..."//getString(R.string.loading_images) // Show loading state
            galleryEmptyText.visibility = View.VISIBLE
            galleryRecyclerView.visibility = View.GONE

            // Perform database query on IO dispatcher
            val eventListResult: Result<List<Event>> = withContext(Dispatchers.IO) {
                try {
                    Result.success(databaseHelper.getAllDetections())
                } catch (e: Exception) {
                    Log.e("ImageGalleryFragment", "Error loading events from DB", e)
                    Result.failure(e)
                }
            }

            // Back on Main thread to update UI
            if (eventListResult.isSuccess) {
                val eventList = eventListResult.getOrNull() ?: emptyList()
                Log.d("ImageGalleryFragment", "Loaded ${eventList.size} events from database.")
                imageAdapter.submitList(eventList) // Update the adapter
                updateEmptyState(eventList.isEmpty()) // Update empty text visibility
            } else {
                Log.e("ImageGalleryFragment", "Failed to load events.")
                Toast.makeText(requireContext(), "Error loading events", Toast.LENGTH_SHORT).show()
                updateEmptyState(true) // Show empty state on error
            }
        }
    }

    // --- Helper to manage empty state visibility ---
    private fun updateEmptyState(isEmpty: Boolean) {
        if (view == null) return // Check if view is available
        if (isEmpty) {
            galleryEmptyText.text = "No images found!" //getString(R.string.no_images_found) // Set appropriate text
            galleryEmptyText.visibility = View.VISIBLE
            galleryRecyclerView.visibility = View.GONE
        } else {
            galleryEmptyText.visibility = View.GONE
            galleryRecyclerView.visibility = View.VISIBLE
        }
    }

    // --- Remove Dummy Data Function ---
    // private fun loadDummyDataForNow() { ... }

}
