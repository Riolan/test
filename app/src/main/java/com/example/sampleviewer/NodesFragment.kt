package com.example.sampleviewer

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.sampleviewer.bt.BleConnectionListener
import com.example.sampleviewer.bt.BleManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.nio.charset.Charset

class NodesFragment : Fragment() {

    private lateinit var nodesRecyclerView: RecyclerView
    private lateinit var disconnectedMessage: TextView
    private lateinit var nodeAdapter: NodeAdapter
    private lateinit var refreshButton: Button

    // *** Store UI state, including expansion, in a map (Node ID -> Node object) ***
    private val nodeUiStateMap = mutableMapOf<String, Node>()

    private val connectionListener = object : BleConnectionListener {
        override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
            activity?.runOnUiThread {
                Log.d("NodesFragment", "Connection Listener: Connected")
                updateConnectionStatusDisplay(true)
                requestNodeDataFromDevice()
            }
        }
        override fun onDeviceDisconnected(device: android.bluetooth.BluetoothDevice) {
            activity?.runOnUiThread {
                Log.d("NodesFragment", "Connection Listener: Disconnected")
                updateConnectionStatusDisplay(false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_nodes, container, false)
        nodesRecyclerView = view.findViewById(R.id.nodes_recycler_view)
        disconnectedMessage = view.findViewById(R.id.disconnected_message)
        refreshButton = view.findViewById(R.id.button_sync)
        setupRecyclerView()
        setupObservers()
        setupListeners()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val safeContext = requireContext()

        BleManager.getInstance(safeContext).registerConnectionListener(connectionListener)
        updateConnectionStatusDisplay(BleManager.getInstance(safeContext).isConnected())
        if (BleManager.getInstance(safeContext).isConnected()) {
            requestNodeDataFromDevice()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val safeContext = requireContext()

        BleManager.getInstance(safeContext).unregisterConnectionListener(connectionListener)
    }

    private fun setupRecyclerView() {
        // Click listener now modifies the state map in the Fragment
        nodeAdapter = NodeAdapter { clickedNode, _ -> // Position might not be reliable if list order changes
            Log.d("NodesFragment", "Clicked on node: ${clickedNode.name} (ID: ${clickedNode.id})")
            // Find the node in our state map
            val currentState = nodeUiStateMap[clickedNode.id]
            if (currentState != null) {
                // Create updated state with toggled expansion
                val updatedState = currentState.copy(isExpanded = !currentState.isExpanded)
                // Update the map
                nodeUiStateMap[clickedNode.id] = updatedState
                // Resubmit the list derived from the map values to the adapter
                nodeAdapter.submitList(nodeUiStateMap.values.toList().sortedBy { it.name }) // Sort for consistent order
                Log.d("NodesFragment", "Toggled expansion for ${clickedNode.id} to ${updatedState.isExpanded}")
            } else {
                Log.w("NodesFragment", "Clicked node ID ${clickedNode.id} not found in state map.")
            }
        }

        nodesRecyclerView.apply {
            adapter = nodeAdapter
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
    }

    private fun setupListeners() {
        refreshButton.setOnClickListener {
            Log.d("NodesFragment", "Refresh button clicked.")
            requestNodeDataFromDevice()
        }
    }

    private fun requestNodeDataFromDevice() {
        val safeContext = requireContext()

        if (BleManager.getInstance(safeContext).isConnected()) {
            Log.d("NodesFragment", "Sending REQUEST_NODES command...")
            val command = "REQUEST_NODES\n"
            val success = BleManager.getInstance(safeContext).sendData(command.toByteArray(Charsets.UTF_8))
            if (!success) {
                Log.e("NodesFragment", "Failed to send REQUEST_NODES command.")
            }
        } else {
            Log.w("NodesFragment", "Cannot request nodes, not connected.")
            updateConnectionStatusDisplay(false)
        }
    }

    // Observe the StateFlow from BleManager
    private fun setupObservers() {
        val safeContext = requireContext()

        viewLifecycleOwner.lifecycleScope.launch {
            BleManager.getInstance(safeContext).nodeListState.collectLatest { nodesFromBle ->
                // This block executes when BleManager provides a new list
                Log.d("NodesFragment", "Observer received ${nodesFromBle.size} nodes from BleManager.")

                // --- Reconcile BLE data with existing UI state (expansion) ---
                val newMapState = mutableMapOf<String, Node>()
                for (bleNode in nodesFromBle) {
                    // Get the previous UI state for this node ID, if it exists
                    val existingUiNode = nodeUiStateMap[bleNode.id]
                    // Create the new state, copying data from BLE, but preserving expansion state
                    val updatedNode = bleNode.copy(
                        isExpanded = existingUiNode?.isExpanded ?: false // Keep old expansion state or default to false
                    )
                    newMapState[updatedNode.id] = updatedNode
                }

                // Update the Fragment's state map
                nodeUiStateMap.clear()
                nodeUiStateMap.putAll(newMapState)

                // Submit the reconciled list (values from the map) to the adapter
                // Sorting ensures a consistent order for DiffUtil
                nodeAdapter.submitList(nodeUiStateMap.values.toList().sortedBy { it.name })
                Log.d("NodesFragment", "Reconciled state and submitted list to adapter.")
            }
        }
        // Optional: Add observer for connection state
    }

    // Helper to update UI based on connection status
    private fun updateConnectionStatusDisplay(isConnected: Boolean) {
        if (view == null) return
        if (isConnected) {
            disconnectedMessage.visibility = View.GONE
            nodesRecyclerView.visibility = View.VISIBLE
            refreshButton.isEnabled = true
        } else {
            disconnectedMessage.visibility = View.VISIBLE
            nodesRecyclerView.visibility = View.GONE
            refreshButton.isEnabled = false
            nodeUiStateMap.clear() // Clear local state when disconnected
            nodeAdapter.submitList(emptyList())
        }
        Log.d("NodesFragment", "UI Updated: Connected = $isConnected")
    }

}