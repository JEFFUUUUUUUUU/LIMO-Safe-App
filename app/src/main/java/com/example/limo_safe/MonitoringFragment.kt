package com.example.limo_safe

import android.app.Dialog
import android.os.Bundle
import android.util.Log
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

        // Initialize views with correct IDs
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
            onUserDeleted = { deviceId, userInfo -> deleteUserFromDevice(deviceId, userInfo) },
            onUserPromoted = { deviceId, userInfo -> promoteUser(deviceId, userInfo) },
            onUserDemoted = { deviceId, userInfo -> demoteUser(deviceId, userInfo) }
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
                    val deviceId = deviceSnapshot.getValue(String::class.java) ?: continue
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
        Log.d("UserDebug", "Starting to fetch users for device: $deviceId")

        deviceUsersListeners[deviceId]?.let { listener ->
            database.child("devices").child(deviceId).child("registeredUsers")
                .removeEventListener(listener)
        }

        val usersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d("UserDebug", "Got registeredUsers snapshot for device $deviceId: ${snapshot.value}")

                // Check if snapshot exists and has children
                if (!snapshot.exists()) {
                    Log.d("UserDebug", "No registered users found for device $deviceId")
                    updateDeviceWithUsers(deviceId, emptyList())
                    return
                }

                val userList = mutableListOf<String>()
                var pendingFetches = snapshot.childrenCount.toInt()

                Log.d("UserDebug", "Found ${pendingFetches} registered users for device $deviceId")

                if (pendingFetches == 0) {
                    updateDeviceWithUsers(deviceId, emptyList())
                    return
                }

                for (userSnapshot in snapshot.children) {
                    val tag = userSnapshot.key ?: continue
                    val userId = userSnapshot.getValue(String::class.java) ?: continue

                    Log.d("UserDebug", "Processing user with tag=$tag, userId=$userId")

                    // Get email directly
                    database.child("users").child(userId).child("email")
                        .get()
                        .addOnSuccessListener { emailSnapshot ->
                            val email = emailSnapshot.getValue(String::class.java)
                            Log.d("UserDebug", "Retrieved email for userId=$userId: $email")

                            if (email != null) {
                                val userEntry = "$tag: $email"
                                Log.d("UserDebug", "Adding user to list: $userEntry")
                                userList.add(userEntry)
                            } else {
                                Log.d("UserDebug", "Email was null for userId=$userId")
                            }

                            pendingFetches--
                            Log.d("UserDebug", "Remaining fetches: $pendingFetches")

                            if (pendingFetches <= 0) {
                                Log.d("UserDebug", "All user fetches complete for device $deviceId. User list: $userList")
                                updateDeviceWithUsers(deviceId, userList)
                            }
                        }
                        .addOnFailureListener { error ->
                            Log.e("UserDebug", "Failed to get email for userId=$userId: ${error.message}")
                            pendingFetches--

                            if (pendingFetches <= 0) {
                                Log.d("UserDebug", "All user fetches complete (with failures) for device $deviceId. User list: $userList")
                                updateDeviceWithUsers(deviceId, userList)
                            }
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("UserDebug", "Database error when fetching users for device $deviceId: ${error.message}")
                updateDeviceWithUsers(deviceId, emptyList())
            }
        }

        database.child("devices").child(deviceId).child("registeredUsers")
            .addValueEventListener(usersListener)

        deviceUsersListeners[deviceId] = usersListener
    }

    private fun fetchUserEmailById(userId: String, callback: (String?) -> Unit) {
        database.child("users").child(userId).child("email")
            .get()
            .addOnSuccessListener { snapshot ->
                val email = snapshot.getValue(String::class.java)
                Log.d("FirebaseDebug", "Fetched email for UID $userId: $email") // DEBUG LOG
                callback(email)
            }
            .addOnFailureListener { error ->
                Log.e("FirebaseDebug", "Error fetching email for UID $userId: ${error.message}")
                callback(null)
            }
    }

    private fun updateDeviceWithUsers(deviceId: String, userList: List<String>) {
        Log.d("UserDebug", "Updating device $deviceId with users: $userList")

        val deviceIndex = devices.indexOfFirst { it.id == deviceId }
        if (deviceIndex == -1) {
            Log.e("UserDebug", "Device $deviceId not found in local list")
            return
        }

        val device = devices[deviceIndex]
        devices[deviceIndex] = device.copy(users = userList)

        // Ensure UI update happens on main thread
        activity?.runOnUiThread {
            deviceAdapter.notifyItemChanged(deviceIndex)
            Log.d("UserDebug", "Notified adapter of change for device at position $deviceIndex")
        }
    }

    private fun addUserToDevice(deviceId: String, email: String) {
        database.child("users")
            .orderByChild("email")
            .equalTo(email)
            .limitToFirst(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userId = snapshot.children.first().key ?: return@addOnSuccessListener
                    Log.d("FirebaseDebug", "Found user UID: $userId")

                    // Get the user's existing tag from their profile
                    database.child("users").child(userId).child("tag")
                        .get()
                        .addOnSuccessListener { tagSnapshot ->
                            val existingTag = tagSnapshot.getValue(String::class.java)

                            if (existingTag != null && existingTag.isNotEmpty()) {
                                // Use the existing tag
                                Log.d("FirebaseDebug", "Using existing tag: $existingTag for user: $userId")

                                // Store the user ID under this tag
                                database.child("devices").child(deviceId).child("registeredUsers")
                                    .child(existingTag)
                                    .setValue(userId)
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "User added successfully", Toast.LENGTH_SHORT).show()
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(context, "Failed to add user: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            } else {
                                // If user doesn't have a tag, generate a new one as before
                                database.child("devices").child(deviceId).child("registeredUsers")
                                    .get()
                                    .addOnSuccessListener { usersSnapshot ->
                                        // Find the highest existing tag number
                                        var highestTag = 0
                                        for (userSnapshot in usersSnapshot.children) {
                                            val tag = userSnapshot.key?.toIntOrNull() ?: continue
                                            if (tag > highestTag) {
                                                highestTag = tag
                                            }
                                        }

                                        // Use the next available tag
                                        val newTag = (highestTag + 1).toString()

                                        // Save this new tag to the user's profile
                                        database.child("users").child(userId).child("tag")
                                            .setValue(newTag)

                                        // Store the user ID under this tag
                                        database.child("devices").child(deviceId).child("registeredUsers")
                                            .child(newTag)
                                            .setValue(userId)
                                            .addOnSuccessListener {
                                                Toast.makeText(context, "User added successfully", Toast.LENGTH_SHORT).show()
                                            }
                                            .addOnFailureListener { e ->
                                                Toast.makeText(context, "Failed to add user: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                    }
                            }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(context, "Error checking user tag: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(context, "User does not exist", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseDebug", "Error fetching user: ${e.message}")
                Toast.makeText(context, "Error checking user existence: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteUserFromDevice(deviceId: String, userInfo: String) {
        // Parse the userInfo which should be in format "tag: email"
        val parts = userInfo.split(":", limit = 2)
        if (parts.size != 2) {
            Toast.makeText(context, "Invalid user format", Toast.LENGTH_SHORT).show()
            return
        }

        val tag = parts[0].trim()

        // Remove user from device's registered users using the tag
        database.child("devices").child(deviceId).child("registeredUsers")
            .child(tag)
            .removeValue()
            .addOnSuccessListener {
                Toast.makeText(context, "User removed successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to remove user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun promoteUser(deviceId: String, userInfo: String) {
        // Parse the userInfo which should be in format "tag: email"
        val parts = userInfo.split(":", limit = 2)
        if (parts.size != 2) {
            Toast.makeText(context, "Invalid user format", Toast.LENGTH_SHORT).show()
            return
        }

        val tag = parts[0].trim()

        // Get the user ID first
        database.child("devices").child(deviceId).child("registeredUsers")
            .child(tag)
            .get()
            .addOnSuccessListener { snapshot ->
                val userId = snapshot.getValue(String::class.java) ?: return@addOnSuccessListener

                // Store the user ID with "admin" tag
                database.child("devices").child(deviceId).child("registeredUsers")
                    .child("admin")
                    .setValue(userId)
                    .addOnSuccessListener {
                        // Remove the regular user entry
                        database.child("devices").child(deviceId).child("registeredUsers")
                            .child(tag)
                            .removeValue()
                            .addOnSuccessListener {
                                Toast.makeText(context, "User promoted to admin", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, "Failed to promote user: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
    }

    private fun demoteUser(deviceId: String, userInfo: String) {
        // Parse the userInfo which should be in format "admin: email" (or similar)
        val parts = userInfo.split(":", limit = 2)
        if (parts.size != 2) {
            Toast.makeText(context, "Invalid user format", Toast.LENGTH_SHORT).show()
            return
        }

        val tag = parts[0].trim()
        if (tag != "admin") {
            Toast.makeText(context, "Only admins can be demoted", Toast.LENGTH_SHORT).show()
            return
        }

        // Get the admin user ID first
        database.child("devices").child(deviceId).child("registeredUsers")
            .child("admin")
            .get()
            .addOnSuccessListener { snapshot ->
                val userId = snapshot.getValue(String::class.java) ?: return@addOnSuccessListener

                // Get the next available tag number
                database.child("devices").child(deviceId).child("registeredUsers")
                    .get()
                    .addOnSuccessListener { usersSnapshot ->
                        // Find the highest existing numeric tag
                        var highestTag = 0
                        for (userSnapshot in usersSnapshot.children) {
                            val existingTag = userSnapshot.key?.toIntOrNull() ?: continue
                            if (existingTag > highestTag) {
                                highestTag = existingTag
                            }
                        }

                        // Use the next available tag
                        val newTag = (highestTag + 1).toString()

                        // Store the user ID under the new numeric tag
                        database.child("devices").child(deviceId).child("registeredUsers")
                            .child(newTag)
                            .setValue(userId)
                            .addOnSuccessListener {
                                // Remove the admin entry
                                database.child("devices").child(deviceId).child("registeredUsers")
                                    .child("admin")
                                    .removeValue()
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "User demoted from admin", Toast.LENGTH_SHORT).show()
                                    }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(context, "Failed to demote user: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
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

        // Clear existing users before adding new ones
        holder.usersContainer.removeAllViews()

        // Add users with options buttons
        device.users.forEach { userEmail ->
            Log.d("DeviceAdapter", "Adding user to view: $userEmail") // DEBUG LOG

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

            // Add user email - Parse to remove tag
            val email = if (userEmail.contains(":")) {
                userEmail.split(":", limit = 2)[1].trim()
            } else {
                userEmail
            }

            val userTextView = TextView(holder.itemView.context).apply {
                text = "• $email"
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