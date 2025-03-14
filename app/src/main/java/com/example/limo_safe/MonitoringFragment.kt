package com.example.limo_safe

import android.app.Dialog
import android.content.Context
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
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.utils.DialogManager
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.example.limo_safe.LoginFragment

class MonitoringFragment : Fragment() {
    private lateinit var deviceListRecyclerView: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var backButton: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var logsFragment: LogsFragment
    private lateinit var dialogManager: DialogManager
    private lateinit var sessionManager: SessionManager

    private lateinit var database: DatabaseReference
    private lateinit var deviceListListener: ValueEventListener
    private val deviceStatusListeners = mutableMapOf<String, ValueEventListener>()
    private val deviceUsersListeners = mutableMapOf<String, ValueEventListener>()
    private val connectedDevices = mutableSetOf<String>()

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
        
        // Initialize managers
        dialogManager = DialogManager(requireContext())
        sessionManager = com.example.limo_safe.Object.SessionManager(requireActivity()) {
            // Logout callback
            Toast.makeText(
                requireContext(),
                "Session expired. Please log in again.",
                Toast.LENGTH_LONG
            ).show()
            navigateToLogin()
        }

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference

        setupRecyclerView()
        setupBackButton()
        setupTabs()
        fetchUserDevices()

        // Add touch listener for session activity
        view.setOnTouchListener { _, _ ->
            sessionManager.userActivityDetected()
            false
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up all listeners
        removeAllListeners()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            devices,
            onUserAdded = { deviceId, email -> addUserToDevice(deviceId, email) },
            onUserDeleted = { deviceId, userInfo -> deleteUserFromDevice(deviceId, userInfo) },
            onUserPromoted = { deviceId, userInfo -> promoteUser(deviceId, userInfo) },
            onUserDemoted = { deviceId, userInfo -> demoteUser(deviceId, userInfo) },
            onWifiClicked = { deviceId -> showWifiDialog(deviceId) },
            context = requireContext()
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
                    // The key is now the deviceId, and the value is the role
                    val deviceId = deviceSnapshot.key ?: continue
                    val role = deviceSnapshot.getValue(String::class.java) ?: "user"

                    // Only process devices where the user is an admin
                    if (role == "admin") {
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
                if (!snapshot.exists()) {
                    updateDeviceWithUsers(deviceId, emptyList())
                    return
                }

                val userList = mutableListOf<UserInfo>()
                var pendingFetches = snapshot.childrenCount.toInt()

                Log.d("UserDebug", "Found ${pendingFetches} registered users for device $deviceId")

                if (pendingFetches == 0) {
                    updateDeviceWithUsers(deviceId, emptyList())
                    return
                }

                for (userSnapshot in snapshot.children) {
                    val tag = userSnapshot.key ?: continue
                    val userId = userSnapshot.getValue(String::class.java) ?: continue

                    // Get user's email from their profile
                    database.child("users").child(userId).child("email")
                        .get()
                        .addOnSuccessListener { emailSnapshot ->
                            val email = emailSnapshot.getValue(String::class.java)
                            Log.d("UserDebug", "Retrieved email for userId=$userId: $email")

                            if (email != null) {
                                // Get user's role from the user's registeredDevices node
                                database.child("users").child(userId).child("registeredDevices")
                                    .child(deviceId)
                                    .get()
                                    .addOnSuccessListener { roleSnapshot ->
                                        val role = roleSnapshot.getValue(String::class.java) ?: "user"
                                        val userInfo = UserInfo(email, role)
                                        userList.add(userInfo)

                                        pendingFetches--
                                        if (pendingFetches == 0) {
                                            updateDeviceWithUsers(deviceId, userList)
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("UserDebug", "Error getting role for user $userId: ${e.message}")
                                        // Fallback to using the registeredUsers value in device node if available
                                        val defaultRole = if (tag in listOf("5", "D", "O")) "admin" else "user"
                                        val userInfo = UserInfo(email, defaultRole)
                                        userList.add(userInfo)

                                        pendingFetches--
                                        if (pendingFetches == 0) {
                                            updateDeviceWithUsers(deviceId, userList)
                                        }
                                    }
                            } else {
                                Log.d("UserDebug", "Email is null for userId=$userId")
                                pendingFetches--
                                if (pendingFetches == 0) {
                                    updateDeviceWithUsers(deviceId, userList)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("UserDebug", "Error getting email for user $userId: ${e.message}")
                            pendingFetches--
                            if (pendingFetches == 0) {
                                updateDeviceWithUsers(deviceId, userList)
                            }
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Failed to load users: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        database.child("devices").child(deviceId).child("registeredUsers")
            .addValueEventListener(usersListener)
        deviceUsersListeners[deviceId] = usersListener
    }

    private fun updateDeviceWithUsers(deviceId: String, userList: List<UserInfo>) {
        Log.d("UserDebug", "Updating device $deviceId with users: $userList")

        val deviceIndex = devices.indexOfFirst { it.id == deviceId }
        if (deviceIndex >= 0) {
            val device = devices[deviceIndex]
            devices[deviceIndex] = device.copy(users = userList)
            deviceAdapter.notifyItemChanged(deviceIndex)
        }
    }

    private fun addUserToDevice(deviceId: String, email: String, role: String = "user") {
        // First find the user ID from the email
        database.child("users")
            .orderByChild("email")
            .equalTo(email)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userId = snapshot.children.first().key
                    if (userId != null) {
                        // Generate a unique tag for this user
                        // Or use the first character of their email as a shorthand tag
                        val tag = email.take(1).uppercase()

                        // Add user to device's registered users
                        database.child("devices").child(deviceId).child("registeredUsers")
                            .child(tag)
                            .setValue(userId)
                            .addOnSuccessListener {
                                // Add device to user's registeredDevices with the role
                                database.child("users").child(userId).child("registeredDevices")
                                    .child(deviceId)
                                    .setValue(role)
                                    .addOnSuccessListener {
                                        // Update user's tag
                                        database.child("users").child(userId).child("tag")
                                            .setValue(tag)
                                            .addOnSuccessListener {
                                                Toast.makeText(context, "User added successfully", Toast.LENGTH_SHORT).show()
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e("UserDebug", "Failed to store user tag: ${e.message}")
                                                Toast.makeText(context, "Failed to store user tag: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("UserDebug", "Failed to add device to user: ${e.message}")
                                        Toast.makeText(context, "Failed to add device to user: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                            .addOnFailureListener { e ->
                                Log.e("UserDebug", "Failed to add user to device: ${e.message}")
                                Toast.makeText(context, "Failed to add user: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        Toast.makeText(context, "User ID is null", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "User with email $email not found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Log.e("UserDebug", "Failed to check user: ${e.message}")
                Toast.makeText(context, "Failed to check user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteUserFromDevice(deviceId: String, userInfo: UserInfo) {
        // Parse the userInfo which should be in format "tag: email"
        val email = userInfo.email

        // Remove user from device's registered users using the tag
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

                                // Remove user from device's registered users using the tag
                                database.child("devices").child(deviceId).child("registeredUsers")
                                    .child(existingTag)
                                    .removeValue()
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "User removed successfully", Toast.LENGTH_SHORT).show()
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(context, "Failed to remove user: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            } else {
                                Toast.makeText(context, "User does not have a tag", Toast.LENGTH_SHORT).show()
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

    private fun updateUserRole(deviceId: String, userInfo: UserInfo, newRole: String) {
        Log.d("UpdateUserRole", "Updating role for deviceId: $deviceId, userInfo: $userInfo, newRole: $newRole")

        database.child("users")
            .orderByChild("email")
            .equalTo(userInfo.email)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userId = snapshot.children.first().key
                    if (userId != null) {
                        val userRef = database.child("users").child(userId).child("registeredDevices").child(deviceId)

                        // Fetch current role before updating
                        userRef.get().addOnSuccessListener { roleSnapshot ->
                            val currentRole = roleSnapshot.getValue(String::class.java)

                            if (currentRole == newRole) {
                                Toast.makeText(context, "User is already $newRole", Toast.LENGTH_SHORT).show()
                                return@addOnSuccessListener
                            }

                            // Proceed with role update
                            userRef.setValue(newRole)
                                .addOnSuccessListener {
                                    updateLocalUserRole(deviceId, userInfo.email, newRole)
                                    Toast.makeText(context, "User role updated to $newRole", Toast.LENGTH_SHORT).show()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(context, "Failed to update role: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        }.addOnFailureListener { e ->
                            Toast.makeText(context, "Failed to fetch current role: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "User ID is null", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "User with email ${userInfo.email} not found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to check user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Helper function to update local state
    private fun updateLocalUserRole(deviceId: String, email: String, newRole: String) {
        val deviceIndex = devices.indexOfFirst { it.id == deviceId }
        if (deviceIndex >= 0) {
            val device = devices[deviceIndex]
            val updatedUsers = device.users.map { user ->
                if (user.email == email) {
                    user.copy(role = newRole)
                } else {
                    user
                }
            }
            devices[deviceIndex] = device.copy(users = updatedUsers)
            deviceAdapter.notifyItemChanged(deviceIndex)
        }
    }

    // Wrapper functions for promotion and demotion
    private fun promoteUser(deviceId: String, userInfo: UserInfo) {
        updateUserRole(deviceId, userInfo, "admin")
    }

    private fun demoteUser(deviceId: String, userInfo: UserInfo) {
        updateUserRole(deviceId, userInfo, "user")
    }

    private fun showWifiDialog(deviceId: String) {
        val dialog = Dialog(requireContext()) // Ensures a non-null context
        dialog.setContentView(R.layout.dialog_wifi_connection)
        dialog.setCancelable(true)


        val ssidInput = dialog.findViewById<EditText>(R.id.ssidInput)
        val passwordInput = dialog.findViewById<EditText>(R.id.passwordInput)
        val connectButton = dialog.findViewById<Button>(R.id.connectButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)



        connectButton?.setOnClickListener {
            sessionManager.userActivityDetected()
            val ssid = ssidInput?.text.toString().trim()
            val password = passwordInput?.text.toString().trim()

            if (ssid.isEmpty()) {
                Toast.makeText(context, "Please enter SSID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Update WiFi credentials in Firebase
            val wifiUpdates = hashMapOf(
                "ssid" to ssid,
                "password" to password,
                "timestamp" to ServerValue.TIMESTAMP
            )

            database.child("devices").child(deviceId).child("wifi")
                .updateChildren(wifiUpdates)
                .addOnSuccessListener {
                    Toast.makeText(context, "WiFi credentials updated", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Failed to update WiFi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        cancelButton?.setOnClickListener {
            sessionManager.userActivityDetected()
            dialog.dismiss()
        }

        dialog.show()
    }

    fun isDeviceConnected(deviceId: String): Boolean {
        return connectedDevices.contains(deviceId)
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

    private fun navigateToLogin() {
        val loginFragment = LoginFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commit()
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
    val users: List<UserInfo>
)

data class UserInfo(
    val email: String,
    val role: String
)

// DeviceAdapter also needs to be updated to handle the callbacks
class DeviceAdapter(
    private val devices: List<Device>,
    private val onUserAdded: (String, String) -> Unit,
    private val onUserDeleted: (String, UserInfo) -> Unit,
    private val onUserPromoted: (String, UserInfo) -> Unit,
    private val onUserDemoted: (String, UserInfo) -> Unit,
    private val onWifiClicked: (String) -> Unit,
    private val context: Context
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val dialogManager = DialogManager(context)
    private val sessionManager = SessionManager(context as FragmentActivity) {
        // Logout callback - will be handled by parent fragment
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceContainer: LinearLayout = itemView.findViewById(R.id.deviceContainer)
        val deviceHeader: LinearLayout = itemView.findViewById(R.id.deviceHeader)
        val deviceNameText: TextView = itemView.findViewById(R.id.deviceNameText)
        val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
        val expandableContent: LinearLayout = itemView.findViewById(R.id.expandableContent)
        val onlineStatusText: TextView = itemView.findViewById(R.id.onlineStatusText)
        val lockStatusText: TextView = itemView.findViewById(R.id.lockStatusText)
        val secureStatusText: TextView = itemView.findViewById(R.id.secureStatusText)
        val usersContainer: LinearLayout = itemView.findViewById(R.id.usersContainer)
        val addUserButton: ImageButton = itemView.findViewById(R.id.addUserButton)
        val wifiButton: ImageButton = itemView.findViewById(R.id.wifiButton)

        init {
            deviceHeader.setOnClickListener {
                expandableContent.visibility = if (expandableContent.visibility == View.VISIBLE) {
                    expandIcon.rotation = 0f
                    View.GONE
                } else {
                    expandIcon.rotation = 180f
                    View.VISIBLE
                }
            }

            addUserButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showAddUserDialog(itemView, position)
                }
            }

            wifiButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onWifiClicked(devices[position].id)
                }
            }
        }
    }

    private fun showAddUserDialog(view: View, position: Int) {
        val deviceId = devices[position].id
        val context = view.context

        val dialog = Dialog(context) // Ensures a non-null context
        dialog.setContentView(R.layout.dialog_add_user)
        dialog.setCancelable(true)

        val emailInput = dialog.findViewById<EditText>(R.id.emailInput)
        val addButton = dialog.findViewById<Button>(R.id.addButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)

        addButton?.setOnClickListener {
            sessionManager.userActivityDetected()
            val email = emailInput?.text.toString().trim()
            if (email.isNotEmpty()) {
                onUserAdded(deviceId, email)
                dialog.dismiss()
            } else {
                Toast.makeText(context, "Please enter an email", Toast.LENGTH_SHORT).show()
            }
        }

        cancelButton?.setOnClickListener {
            sessionManager.userActivityDetected()
            dialog.dismiss()
        }

        // Add touch listener for session activity
        dialog.window?.decorView?.setOnTouchListener { _, _ ->
            sessionManager.userActivityDetected()
            false
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
        holder.onlineStatusText.text = "• ${if (device.isOnline) "Online" else "Offline"}"
        holder.onlineStatusText.setTextColor(holder.itemView.context.resources.getColor(if (device.isOnline) android.R.color.holo_green_dark else R.color.maroon))

        holder.lockStatusText.text = "• ${if (device.isLocked) "Locked" else "Unlocked"}"
        holder.lockStatusText.setTextColor(holder.itemView.context.resources.getColor(if (device.isLocked) android.R.color.holo_green_dark else R.color.maroon))

        holder.secureStatusText.text = "• ${if (device.isSecure) "Secure" else "Tamper Detected"}"
        holder.secureStatusText.setTextColor(holder.itemView.context.resources.getColor(if (device.isSecure) android.R.color.holo_green_dark else R.color.maroon))

        // Update WiFi button color based on connection status
        val isConnected = (holder.itemView.context as? FragmentActivity)?.supportFragmentManager?.fragments?.firstOrNull { it is MonitoringFragment }?.let {
            (it as MonitoringFragment).isDeviceConnected(device.id)
        } ?: false

        holder.wifiButton.setColorFilter(
            holder.itemView.context.resources.getColor(if (isConnected) android.R.color.holo_green_light else android.R.color.white)
        )

        // Clear previous users
        holder.usersContainer.removeAllViews()

        // Add user views
        device.users.forEach { userInfo ->
            val userView = LayoutInflater.from(holder.itemView.context)
                .inflate(R.layout.item_user, holder.usersContainer, false)

            val userText = userView.findViewById<TextView>(R.id.userText)
            val userOptionsButton = userView.findViewById<ImageButton>(R.id.userOptionsButton)

            userText.text = "• ${userInfo.email}"
            userText.setTextColor(holder.itemView.context.resources.getColor(R.color.maroon))

            userOptionsButton.setOnClickListener {
                showUserOptionsMenu(it, device.id, userInfo)
            }

            holder.usersContainer.addView(userView)
        }
    }

    private fun showUserOptionsMenu(view: View, deviceId: String, userInfo: UserInfo) {
        val popupMenu = PopupMenu(view.context, view).apply {
            inflate(R.menu.user_options_menu)

            // Set text color for all menu items
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val spanString = android.text.SpannableString(item.title.toString())
                spanString.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#800000")), 0, spanString.length, 0)
                item.title = spanString
            }
        }

        // Add touch listener for session activity
        popupMenu.setOnMenuItemClickListener { item ->
            sessionManager.userActivityDetected()
            when (item.itemId) {
                R.id.action_delete_user -> {
                    onUserDeleted(deviceId, userInfo)
                    true
                }
                R.id.action_promote_user -> {
                    onUserPromoted(deviceId, userInfo)
                    true
                }
                R.id.action_demote_user -> {
                    onUserDemoted(deviceId, userInfo)
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
    }
}