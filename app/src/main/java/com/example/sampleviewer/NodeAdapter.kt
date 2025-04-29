package com.example.sampleviewer


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

class NodeAdapter(
    private val onItemClicked: (Node, Int) -> Unit
) : ListAdapter<Node, NodeAdapter.NodeViewHolder>(NodeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_node, parent, false)
        return NodeViewHolder(view, onItemClicked)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    class NodeViewHolder(
        itemView: View,
        private val onItemClicked: (Node, Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        // Collapsed views
        private val nodeNameTextView: TextView = itemView.findViewById(R.id.node_name)
        private val nodeStatusTextView: TextView = itemView.findViewById(R.id.node_status)
        private val expandButton: ImageButton = itemView.findViewById(R.id.expand_button)

        // Expanded views
        private val expandedDetailsView: LinearLayout = itemView.findViewById(R.id.expanded_details_view)
        private val nodeDetailBattery: TextView = itemView.findViewById(R.id.node_detail_battery)
        private val nodeDetailSignal: TextView = itemView.findViewById(R.id.node_detail_signal)
        private val nodeDetailLastEvent: TextView = itemView.findViewById(R.id.node_detail_last_event)
        // Checkboxes
        private val detectCatCheckBox: MaterialCheckBox = itemView.findViewById(R.id.checkbox_detect_cat)
        private val detectDogCheckBox: MaterialCheckBox = itemView.findViewById(R.id.checkbox_detect_dog)
        private val detectSquirrelCheckBox: MaterialCheckBox = itemView.findViewById(R.id.checkbox_detect_squirrel)
        private val detectBirdCheckBox: MaterialCheckBox = itemView.findViewById(R.id.checkbox_detect_bird)
        // Update button
        private val updateNodeButton: MaterialButton = itemView.findViewById(R.id.button_update_node)


        private var currentNode: Node? = null
        private var currentPosition: Int = -1

        init {
            // Click listener for expanding/collapsing
            expandButton.setOnClickListener {
                currentNode?.let { node ->
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onItemClicked(node, currentPosition)
                    }
                }
            }
            itemView.setOnClickListener { // Also allow clicking the whole item
                currentNode?.let { node ->
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onItemClicked(node, currentPosition)
                    }
                }
            }

            // Click listener for the update button (inside expanded view)
            updateNodeButton.setOnClickListener {
                currentNode?.let { node ->
                    // Get current checkbox states (important!)
                    val cat = detectCatCheckBox.isChecked
                    val dog = detectDogCheckBox.isChecked
                    val squirrel = detectSquirrelCheckBox.isChecked
                    val bird = detectBirdCheckBox.isChecked

                    // Simulate sending update
                    val message = "Updating ${node.name}: Cat=$cat, Dog=$dog, Squirrel=$squirrel, Bird=$bird"
                    Toast.makeText(itemView.context, message, Toast.LENGTH_SHORT).show()

                    // In a real app, you'd send these values to the node via LoRa/backend
                    // and likely update the 'currentNode' object and potentially save locally.
                    // For this mockup, we just show the toast.
                }
            }
        }

        fun bind(node: Node, position: Int) {
            currentNode = node
            currentPosition = position

            // Bind collapsed view
            nodeNameTextView.text = node.name
            val statusText = "Status: ${node.status}, Last Seen: ${node.lastSeen}"
            nodeStatusTextView.text = statusText

            // Bind expanded basic details
            nodeDetailBattery.text = node.batteryLevel?.let { "Battery: $it%" } ?: "Battery: N/A"
            nodeDetailSignal.text = node.signalStrength?.let { "Signal: $it dBm" } ?: "Signal: N/A"
            nodeDetailLastEvent.text = "Last Event: Motion" // Mock detail

            // Bind checkbox states
            detectCatCheckBox.isChecked = node.detectCat
            detectDogCheckBox.isChecked = node.detectDog
            detectSquirrelCheckBox.isChecked = node.detectSquirrel
            detectBirdCheckBox.isChecked = node.detectBird

            // Set visibility and icon based on expanded state
            if (node.isExpanded) {
                expandedDetailsView.visibility = View.VISIBLE
                expandButton.setImageResource(android.R.drawable.arrow_up_float)
            } else {
                expandedDetailsView.visibility = View.GONE
                expandButton.setImageResource(android.R.drawable.ic_menu_more)
            }
        }
    }

    class NodeDiffCallback : DiffUtil.ItemCallback<Node>() {
        override fun areItemsTheSame(oldItem: Node, newItem: Node): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: Node, newItem: Node): Boolean {
            return oldItem == newItem // Compares all fields including isExpanded and detection flags
        }
    }
}