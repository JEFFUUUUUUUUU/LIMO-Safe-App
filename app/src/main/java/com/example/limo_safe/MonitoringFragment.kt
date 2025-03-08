package com.example.limo_safe

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

class MonitoringFragment : Fragment() {
    private lateinit var deviceListRecyclerView: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var backButton: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var logsFragment: LogsFragment

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_monitoring, container, false)

        // Initialize views with correct IDs
        deviceListRecyclerView = view.findViewById(R.id.deviceListRecyclerView)
        backButton = view.findViewById(R.id.backButton)
        tabLayout = view.findViewById(R.id.tabLayout)

        setupRecyclerView()
        setupBackButton()
        setupTabs()

        return view
    }

    private fun setupRecyclerView() {
        val devices = listOf(
            Device("Device 1", true, true, true, listOf("example.acc1@email.com", "example.acc2@email.com")),
            Device("Device 2", false, false, false, listOf("example.acc3@email.com")),
            Device("Device 3", true, false, true, listOf("example.acc4@email.com"))
        )

        deviceAdapter = DeviceAdapter(devices)
        deviceListRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceAdapter
        }
    }

    private fun setupBackButton() {
        backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupTabs() {
        // Create logs fragment instance
        logsFragment = LogsFragment.newInstance()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Device List
                        deviceListRecyclerView.visibility = View.VISIBLE
                        if (logsFragment.isAdded) {
                            childFragmentManager.beginTransaction()
                                .remove(logsFragment)
                                .commit()
                        }
                    }
                    1 -> { // Logs
                        deviceListRecyclerView.visibility = View.GONE
                        childFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, logsFragment)
                            .commit()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    companion object {
        fun newInstance(): MonitoringFragment {
            return MonitoringFragment()
        }
    }
}

data class Device(
    val name: String,
    val isOnline: Boolean,
    val isLocked: Boolean,
    val isSecure: Boolean,
    var users: List<String>
)

class DeviceAdapter(private val devices: List<Device>) :
    RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceHeader: LinearLayout = itemView.findViewById(R.id.deviceHeader)
        val deviceNameText: TextView = itemView.findViewById(R.id.deviceNameText)
        val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
        val expandableContent: LinearLayout = itemView.findViewById(R.id.expandableContent)
        val onlineStatusText: TextView = itemView.findViewById(R.id.onlineStatusText)
        val lockStatusText: TextView = itemView.findViewById(R.id.lockStatusText)
        val secureStatusText: TextView = itemView.findViewById(R.id.secureStatusText)
        val usersContainer: LinearLayout = itemView.findViewById(R.id.usersContainer)
        val addUserButton: ImageButton = itemView.findViewById(R.id.addUserButton)

        init {
            deviceHeader.setOnClickListener {
                val isExpanded = expandableContent.visibility == View.VISIBLE
                expandableContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
                expandIcon.rotation = if (isExpanded) 0f else 180f
            }

            addUserButton.setOnClickListener {
                showAddUserDialog(itemView, adapterPosition)
            }
        }
    }

    private fun showAddUserDialog(view: View, position: Int) {
        val context = view.context
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_add_user)

        val emailInput = dialog.findViewById<EditText>(R.id.emailInput)
        val enterButton = dialog.findViewById<Button>(R.id.enterButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)

        enterButton.setOnClickListener {
            val email = emailInput.text.toString()
            if (email.isNotEmpty()) {
                // TODO: Implement actual user addition logic
                Toast.makeText(context, "Adding user: $email", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(context, "Please enter an email", Toast.LENGTH_SHORT).show()
            }
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun getItemCount() = devices.size

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.deviceNameText.text = device.name

        // Set status texts with bullet points
        holder.onlineStatusText.text = "• ${if (device.isOnline) "Online" else "Offline"}"
        holder.onlineStatusText.setTextColor(if (device.isOnline) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())

        holder.lockStatusText.text = "• ${if (device.isLocked) "Locked" else "Unlocked"}"
        holder.lockStatusText.setTextColor(if (device.isLocked) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())

        holder.secureStatusText.text = "• ${if (device.isSecure) "Secure" else "Tamper Detected"}"
        holder.secureStatusText.setTextColor(if (device.isSecure) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())

        // Clear existing users
        holder.usersContainer.removeAllViews()

        // Add users with options buttons
        device.users.forEach { userEmail ->
            val userRow = LinearLayout(holder.itemView.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
            }

            val userEmailText = TextView(holder.itemView.context).apply {
                text = userEmail
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setPadding(8, 4, 8, 4)
            }

            val optionsButton = ImageButton(holder.itemView.context).apply {
                setImageResource(R.drawable.ic_more_vert)
                background = null
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    showUserOptionsMenu(this, position, userEmail)
                }
            }

            userRow.addView(userEmailText)
            userRow.addView(optionsButton)
            holder.usersContainer.addView(userRow)
        }
    }

    private fun showUserOptionsMenu(view: View, devicePosition: Int, userEmail: String) {
        val popup = PopupMenu(view.context, view)
        popup.menuInflater.inflate(R.menu.user_options_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_remove_user -> {
                    val device = devices[devicePosition]
                    device.users = device.users.filter { it != userEmail }
                    notifyItemChanged(devicePosition)
                    Toast.makeText(view.context, "User removed: $userEmail", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_edit_permissions -> {
                    showEditPermissionsDialog(view.context, userEmail)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showEditPermissionsDialog(context: Context, userEmail: String) {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_edit_permissions)
        dialog.setTitle("Edit Permissions")

        // Get dialog views
        val adminSwitch = dialog.findViewById<SwitchCompat>(R.id.adminSwitch)
        val viewOnlySwitch = dialog.findViewById<SwitchCompat>(R.id.viewOnlySwitch)
        val saveButton = dialog.findViewById<Button>(R.id.saveButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)

        saveButton.setOnClickListener {
            // TODO: Implement permission saving logic
            Toast.makeText(context, "Permissions updated for: $userEmail", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}