package com.example.limo_safe

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MonitoringFragment : Fragment() {
    private lateinit var deviceListRecyclerView: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var backButton: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var logsFragment: LogsFragment

    private lateinit var database: DatabaseReference
    private lateinit var deviceListListener: ValueEventListener
    private val deviceStatusListeners = mutableMapOf<String, ValueEventListener>()
    private val deviceUsersListeners = mutableMapOf<String, ValueEventListener>()

    private val devices = mutableListOf<Device>()
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_monitoring, container, false)

        // Initialize views
        deviceListRecyclerView = view.findViewById(R.id.deviceListRecyclerView)
        backButton = view.findViewById(R.id.backButton)
        tabLayout = view.findViewById(R.id.tabLayout)

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference

        setupRecyclerView()
        setupBackButton()
        setupTabs()
        fetchUserDevices()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up all listeners
        removeAllListeners()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(devices,
            onUserAdded = { deviceId, email -> addUserToDevice(deviceId, email) },
            onUserDeleted = { deviceId, email -> deleteUserFromDevice(deviceId, email) },
            onUserPromoted = { deviceId, email -> promoteUser(deviceId, email) },
            onUserDemoted = { deviceId, email -> demoteUser(deviceId, email) }
        )
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
                        childFragmentManager.beginTransaction()
                            .remove(logsFragment)
                            .commit()
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

    private fun fetchUserDevices() {
        // Remove previous listener if exists
        if (::deviceListListener.isInitialized) {
            database.child("users").child(currentUserId).child("registeredDevices")
                .removeEventListener(deviceListListener)
        }

        deviceListListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Clear previous device listeners
                removeAllDeviceListeners()
                devices.clear()

                for (deviceSnapshot in snapshot.children) {
                    val deviceId = deviceSnapshot.key ?: continue
                    // Create placeholder device
                    val device = Device(
                        id = deviceId,
                        name = deviceId, // Temporary name until we get details
                        isOnline = false,
                        isLocked = false,
                        isSecure = false,
                        users = emptyList()
                    )
                    devices.add(device)

                    // Fetch device status and users
                    fetchDeviceStatus(deviceId)
                    fetchDeviceUsers(deviceId)
                }

                deviceAdapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Failed to load devices: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        database.child("users").child(currentUserId).child("registeredDevices")
            .addValueEventListener(deviceListListener)
    }

    private fun fetchDeviceStatus(deviceId: String) {
        // Remove previous listener if exists
        deviceStatusListeners[deviceId]?.let { listener ->
            database.child("devices").child(deviceId).child("status")
                .removeEventListener(listener)
        }

        val statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val isLocked = snapshot.child("locked").getValue(Boolean::class.java) ?: false
                val isSecure = snapshot.child("secure").getValue(Boolean::class.java) ?: false
                val name = snapshot.child("name").getValue(String::class.java) ?: deviceId

                // Find and update device in our list
                val deviceIndex = devices.indexOfFirst { it.id == deviceId }
                if (deviceIndex >= 0) {
                    val device = devices[deviceIndex]
                    devices[deviceIndex] = device.copy(
                        name = name,
                        isOnline = isOnline,
                        isLocked = isLocked,
                        isSecure = isSecure
                    )
                    deviceAdapter.notifyItemChanged(deviceIndex)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Failed to load device status: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        database.child("devices").child(deviceId).child("status")
            .addValueEventListener(statusListener)

        deviceStatusListeners[deviceId] = statusListener
    }

    private fun fetchDeviceUsers(deviceId: String) {
        // Remove previous listener if exists
        deviceUsersListeners[deviceId]?.let { listener ->
            database.child("devices").child(deviceId).child("registeredUsers")
                .removeEventListener(listener)
        }

        val usersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val userList = mutableListOf<String>()

                for (userSnapshot in snapshot.children) {
                    val userEmail = userSnapshot.key ?: continue
                    userList.add(userEmail)
                }

                // Find and update device in our list
                val deviceIndex = devices.indexOfFirst { it.id == deviceId }
                if (deviceIndex >= 0) {
                    val device = devices[deviceIndex]
                    devices[deviceIndex] = device.copy(users = userList)
                    deviceAdapter.notifyItemChanged(deviceIndex)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Failed to load device users: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        database.child("devices").child(deviceId).child("registeredUsers")
            .addValueEventListener(usersListener)

        deviceUsersListeners[deviceId] = usersListener
    }

    private fun addUserToDevice(deviceId: String, email: String) {
        // Add user to device's registered users
        database.child("devices").child(deviceId).child("registeredUsers")
            .child(email.replace(".", ",")) // Firebase doesn't allow dots in keys
            .setValue(true)
            .addOnSuccessListener {
                Toast.makeText(context, "User added successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to add user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteUserFromDevice(deviceId: String, email: String) {
        // Remove user from device's registered users
        database.child("devices").child(deviceId).child("registeredUsers")
            .child(email.replace(".", ",")) // Firebase doesn't allow dots in keys
            .removeValue()
            .addOnSuccessListener {
                Toast.makeText(context, "User removed successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to remove user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun promoteUser(deviceId: String, email: String) {
        // Set user as admin
        database.child("devices").child(deviceId).child("registeredUsers")
            .child(email.replace(".", ","))
            .setValue("admin")
            .addOnSuccessListener {
                Toast.makeText(context, "User promoted to admin", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to promote user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun demoteUser(deviceId: String, email: String) {
        // Set user as regular user
        database.child("devices").child(deviceId).child("registeredUsers")
            .child(email.replace(".", ","))
            .setValue(true)
            .addOnSuccessListener {
                Toast.makeText(context, "User demoted to regular user", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to demote user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun removeAllDeviceListeners() {
        // Remove all device status listeners
        for ((deviceId, listener) in deviceStatusListeners) {
            database.child("devices").child(deviceId).child("status")
                .removeEventListener(listener)
        }
        deviceStatusListeners.clear()

        // Remove all device users listeners
        for ((deviceId, listener) in deviceUsersListeners) {
            database.child("devices").child(deviceId).child("registeredUsers")
                .removeEventListener(listener)
        }
        deviceUsersListeners.clear()
    }

    private fun removeAllListeners() {
        // Remove device list listener
        if (::deviceListListener.isInitialized) {
            database.child("users").child(currentUserId).child("registeredDevices")
                .removeEventListener(deviceListListener)
        }

        // Remove all device-specific listeners
        removeAllDeviceListeners()
    }

    companion object {
        fun newInstance(): MonitoringFragment {
            return MonitoringFragment()
        }
    }
}

data class Device(
    val id: String,
    val name: String,
    val isOnline: Boolean,
    val isLocked: Boolean,
    val isSecure: Boolean,
    val users: List<String>
)

// DeviceAdapter also needs to be updated to handle the callbacks
class DeviceAdapter(
    private val devices: List<Device>,
    private val onUserAdded: (String, String) -> Unit,
    private val onUserDeleted: (String, String) -> Unit,
    private val onUserPromoted: (String, String) -> Unit,
    private val onUserDemoted: (String, String) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Same as before
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
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showAddUserDialog(itemView, position)
                }
            }
        }
    }

    private fun showAddUserDialog(view: View, position: Int) {
        val deviceId = devices[position].id
        val context = view.context
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_add_user)

        val emailInput = dialog.findViewById<EditText>(R.id.emailInput)
        val enterButton = dialog.findViewById<Button>(R.id.enterButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)

        enterButton.setOnClickListener {
            val email = emailInput.text.toString()
            if (email.isNotEmpty()) {
                onUserAdded(deviceId, email)
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
            // Create horizontal layout for user row
            val userRow = LinearLayout(holder.itemView.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
            }

            // Add user email
            val userTextView = TextView(holder.itemView.context).apply {
                text = "• $userEmail"
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                textSize = 14f
                setPadding(0, 4, 0, 4)
            }
            userRow.addView(userTextView)

            // Add options button
            val optionsButton = ImageButton(holder.itemView.context).apply {
                setImageResource(R.drawable.ic_more_vert)
                background = null
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { view ->
                    showUserOptionsMenu(view, device.id, userEmail)
                }
            }
            userRow.addView(optionsButton)

            // Add the complete row to the container
            holder.usersContainer.addView(userRow)
        }
    }

    private fun showUserOptionsMenu(view: View, deviceId: String, userEmail: String) {
        PopupMenu(view.context, view).apply {
            inflate(R.menu.user_options_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_delete_user -> {
                        onUserDeleted(deviceId, userEmail)
                        true
                    }
                    R.id.action_promote_user -> {
                        onUserPromoted(deviceId, userEmail)
                        true
                    }
                    R.id.action_demote_user -> {
                        onUserDemoted(deviceId, userEmail)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }
}