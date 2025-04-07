package com.example.limo_safe

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.utils.DialogManager
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.example.limo_safe.LoginFragment

class MonitoringFragment : Fragment() {
    private val TAG = "MonitoringFragment"

    private lateinit var deviceListRecyclerView: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var backButton: Button
    private lateinit var tabLayout: TabLayout
    private var logsFragment: LogsFragment? = null
    private lateinit var dialogManager: DialogManager
    private lateinit var sessionManager: SessionManager
    private lateinit var fragmentContainer: View

    private lateinit var database: DatabaseReference
    private lateinit var deviceListListener: ValueEventListener
    private val deviceStatusListeners = mutableMapOf<String, ValueEventListener>()
    private val deviceUsersListeners = mutableMapOf<String, ValueEventListener>()
    private val connectedDevices = mutableSetOf<String>()

    private var devices = mutableListOf<Device>()
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Ensure fragment container is visible
        requireActivity().findViewById<View>(R.id.fragmentContainer).visibility = View.VISIBLE

        val view = inflater.inflate(R.layout.fragment_monitoring, container, false)

        // Initialize views with correct IDs
        deviceListRecyclerView = view.findViewById(R.id.deviceListRecyclerView)
        backButton = view.findViewById(R.id.backButton)
        tabLayout = view.findViewById(R.id.tabLayout)
        fragmentContainer = view.findViewById(R.id.fragmentContainer)

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference

        // Initialize managers
        dialogManager = DialogManager(requireContext())
        sessionManager = SessionManager(requireActivity()) {
            // Session timeout callback
            activity?.runOnUiThread {
                Toast.makeText(
                    requireContext(),
                    "Session expired. Please log in again.",
                    Toast.LENGTH_LONG
                ).show()
                handleLogout()
            }
        }

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference

        // Set up view visibility
        view.visibility = View.VISIBLE
        deviceListRecyclerView.visibility = View.VISIBLE
        fragmentContainer.visibility = View.GONE  // Initially hide the logs container

        // Initialize device list
        devices = mutableListOf()
        deviceAdapter = DeviceAdapter(
            devices,
            onUserAdded = { deviceId, email -> addUserToDevice(deviceId, email) },
            onUserDeleted = { deviceId, userInfo -> deleteUserFromDeviceInternal(deviceId, userInfo) },
            onUserPromoted = { deviceId, userInfo -> promoteUserInternal(deviceId, userInfo) },
            onUserDemoted = { deviceId, userInfo -> demoteUserInternal(deviceId, userInfo) },
            onWifiClicked = { deviceId -> showWifiDialogInternal(deviceId) },
            context = requireContext()
        )
        deviceListRecyclerView.adapter = deviceAdapter
        deviceListRecyclerView.layoutManager = LinearLayoutManager(context)

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
        // Clean up all listeners and fragments
        removeAllListeners()
        logsFragment?.let { fragment ->
            if (fragment.isAdded) {
                childFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss()
            }
        }
        logsFragment = null
    }

    private fun setupRecyclerView() {
        // RecyclerView setup is now done in onCreateView
    }

    private fun setupBackButton() {
        backButton.setOnClickListener {
            sessionManager.userActivityDetected()

            // Clean up any existing fragments and listeners
            removeAllListeners()
            logsFragment?.let { fragment ->
                if (fragment.isAdded) {
                    childFragmentManager.beginTransaction()
                        .remove(fragment)
                        .commitNowAllowingStateLoss()
                }
            }

            // Pop back to MCFragment
            if (parentFragmentManager.backStackEntryCount > 0) {
                // Find the MCFragment entry
                val mcEntry = (0 until parentFragmentManager.backStackEntryCount)
                    .map { parentFragmentManager.getBackStackEntryAt(it) }
                    .firstOrNull { it.name == "monitoring" }

                if (mcEntry != null) {
                    // Pop back to MCFragment
                    parentFragmentManager.popBackStackImmediate("monitoring", FragmentManager.POP_BACK_STACK_INCLUSIVE)
                } else {
                    // Pop the current fragment
                    parentFragmentManager.popBackStackImmediate()
                }
            } else {
                // Fallback: If somehow we lost the back stack, create a new MCFragment
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                    )
                    .replace(R.id.fragmentContainer, MCFragment())
                    .commitNowAllowingStateLoss()
            }
        }
    }

    private fun setupTabs() {
        // Add default tabs
        if (tabLayout.tabCount == 0) {
            tabLayout.addTab(tabLayout.newTab().setText("Devices"))
            tabLayout.addTab(tabLayout.newTab().setText("Logs"))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                sessionManager.userActivityDetected()
                when (tab?.position) {
                    0 -> { // Device List
                        deviceListRecyclerView.visibility = View.VISIBLE
                        fragmentContainer.visibility = View.GONE
                        // Safely remove logs fragment if it exists
                        logsFragment?.let { fragment ->
                            if (fragment.isAdded) {
                                childFragmentManager.beginTransaction()
                                    .remove(fragment)
                                    .commitNowAllowingStateLoss()
                            }
                        }
                        logsFragment = null

                        // Refresh device list
                        devices.clear()
                        deviceAdapter.notifyDataSetChanged()
                        fetchUserDevices()
                    }
                    1 -> { // Logs
                        try {
                            // Only proceed if we're logged in
                            if (FirebaseAuth.getInstance().currentUser != null) {
                                // Hide device list and show fragment container
                                deviceListRecyclerView.visibility = View.GONE
                                fragmentContainer.visibility = View.VISIBLE

                                // Create and store new logs fragment
                                logsFragment = LogsFragment().also { newFragment ->
                                    // Replace with new logs fragment using child fragment manager
                                    childFragmentManager.beginTransaction()
                                        .replace(R.id.fragmentContainer, newFragment)
                                        .commitNowAllowingStateLoss()
                                }
                            } else {
                                // If not logged in, stay on devices tab
                                tabLayout.getTabAt(0)?.select()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error switching to logs tab: ${e.message}")
                            e.printStackTrace()
                            // Show device list on error
                            deviceListRecyclerView.visibility = View.VISIBLE
                            fragmentContainer.visibility = View.GONE
                            tabLayout.getTabAt(0)?.select()
                        }
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                sessionManager.userActivityDetected()
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                sessionManager.userActivityDetected()
            }
        })
    }

    private fun deleteUserFromDeviceInternal(deviceId: String, userInfo: UserInfo) {
        database.child("users").orderByChild("email").equalTo(userInfo.email)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userId = snapshot.children.first().key!!
                    // Get user's tag
                    database.child("users").child(userId).child("tag")
                        .get()
                        .addOnSuccessListener { tagSnapshot ->
                            val tag = tagSnapshot.getValue(String::class.java)
                            if (tag != null) {
                                // Remove from device's registered users
                                database.child("devices").child(deviceId).child("registeredUsers")
                                    .child(tag)
                                    .removeValue()
                                // Remove from user's registered devices
                                database.child("users").child(userId).child("registeredDevices")
                                    .child(deviceId)
                                    .removeValue()
                                    .addOnSuccessListener {
                                        Toast.makeText(context, "User removed successfully", Toast.LENGTH_SHORT).show()
                                        fetchUserDevices()  // Refresh the list
                                    }
                            }
                        }
                }
            }
    }

    private fun promoteUserInternal(deviceId: String, userInfo: UserInfo) {
        database.child("users").orderByChild("email").equalTo(userInfo.email)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userId = snapshot.children.first().key!!
                    // Update role to admin
                    database.child("users").child(userId).child("registeredDevices")
                        .child(deviceId)
                        .setValue("admin")
                        .addOnSuccessListener {
                            Toast.makeText(context, "User promoted to admin", Toast.LENGTH_SHORT).show()
                            fetchUserDevices()  // Refresh the list
                        }
                }
            }
    }

    private fun demoteUserInternal(deviceId: String, userInfo: UserInfo) {
        database.child("users").orderByChild("email").equalTo(userInfo.email)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userId = snapshot.children.first().key!!
                    // Update role to user
                    database.child("users").child(userId).child("registeredDevices")
                        .child(deviceId)
                        .setValue("user")
                        .addOnSuccessListener {
                            Toast.makeText(context, "User demoted to regular user", Toast.LENGTH_SHORT).show()
                            fetchUserDevices()  // Refresh the list
                        }
                }
            }
    }

    private fun showWifiDialogInternal(deviceId: String) {
        // Request WiFi credentials
        database.child("devices").child(deviceId).child("status").child("wifiRequested")
            .setValue(true)
            .addOnSuccessListener {
                Toast.makeText(context, "WiFi credentials requested", Toast.LENGTH_SHORT).show()
            }
    }

    private fun fetchUserDevices() {
        // Debug logging
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        Log.d(TAG, "Current user: ${user?.uid}, Email: ${user?.email}")
        Log.d(TAG, "Current userId from getter: $currentUserId")

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

                    // Process all devices but store the user's role
                    if (true) {  // We'll filter in the adapter based on role
                        // Create placeholder device with role
                        val device = Device(
                            id = deviceId,
                            name = deviceId, // Temporary name until we get details
                            isOnline = false,
                            isLocked = false,
                            isSecure = false,
                            users = emptyList(),
                            userRole = role  // Store the user's role
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
        // Get the current user's ID who is performing the action
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            ?: run {
                Toast.makeText(context, "No authenticated user", Toast.LENGTH_SHORT).show()
                return
            }

        // Find the user ID from the email
        database.child("users")
            .orderByChild("email")
            .equalTo(email)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val addedUserId = snapshot.children.first().key
                    if (addedUserId != null) {
                        // Retrieve the added user's existing tag
                        database.child("users").child(addedUserId).child("tag")
                            .get()
                            .addOnSuccessListener { tagSnapshot ->
                                val existingTag = tagSnapshot.getValue(String::class.java)

                                if (!existingTag.isNullOrEmpty()) {
                                    Log.d("UserDebug", "Using existing tag: $existingTag for user: $addedUserId")

                                    // Prepare log entry
                                    val logEntry = mapOf(
                                        "timestamp" to ServerValue.TIMESTAMP,
                                        "event" to "user_added_to_device",
                                        "user" to mapOf(
                                            "id" to addedUserId,
                                            "email" to email,
                                            "tag" to existingTag
                                        ),
                                        "device" to deviceId,
                                        "role" to role
                                    )

                                    // Add user to device's registered users
                                    val addToDevice = database.child("devices").child(deviceId).child("registeredUsers")
                                        .child(existingTag)
                                        .setValue(addedUserId)

                                    // Add device to added user's registeredDevices with the role
                                    val addToUser = database.child("users").child(addedUserId).child("registeredDevices")
                                        .child(deviceId)
                                        .setValue(role)

                                    // Add log entry under current user's logs
                                    val addLog = database.child("users").child(currentUserId).child("logs").push().setValue(logEntry)

                                    // Execute all operations and wait for completion
                                    Tasks.whenAllComplete(addToDevice, addToUser, addLog)
                                        .addOnSuccessListener {
                                            Toast.makeText(context, "User added successfully", Toast.LENGTH_SHORT).show()
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e("UserDebug", "Failed to add user: ${e.message}")
                                            Toast.makeText(context, "Failed to add user: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                } else {
                                    Toast.makeText(context, "User does not have a tag", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(context, "Error fetching user tag: ${e.message}", Toast.LENGTH_SHORT).show()
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
        // Get the current user's ID who is performing the action
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            ?: run {
                Toast.makeText(context, "No authenticated user", Toast.LENGTH_SHORT).show()
                return
            }

        val email = userInfo.email

        // Find user ID based on email
        database.child("users")
            .orderByChild("email")
            .equalTo(email)
            .limitToFirst(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val removedUserId = snapshot.children.first().key ?: return@addOnSuccessListener
                    Log.d("FirebaseDebug", "Found user UID: $removedUserId")

                    // Get the user's existing tag from their profile
                    database.child("users").child(removedUserId).child("tag")
                        .get()
                        .addOnSuccessListener { tagSnapshot ->
                            val existingTag = tagSnapshot.getValue(String::class.java)

                            if (!existingTag.isNullOrEmpty()) {
                                Log.d("FirebaseDebug", "Using existing tag: $existingTag for user: $removedUserId")

                                // Prepare log entry
                                val logEntry = mapOf(
                                    "timestamp" to ServerValue.TIMESTAMP,
                                    "event" to "user_removed_from_device",
                                    "user" to mapOf(
                                        "id" to removedUserId,
                                        "email" to email,
                                        "tag" to existingTag
                                    ),
                                    "device" to deviceId
                                )

                                // Firebase operations to remove user from both locations
                                val removeFromDevice = database.child("devices").child(deviceId)
                                    .child("registeredUsers").child(existingTag).removeValue()

                                val removeFromUser = database.child("users").child(removedUserId)
                                    .child("registeredDevices").child(deviceId).removeValue()

                                // Add log entry under current user's logs
                                val addLog = database.child("users").child(currentUserId).child("logs").push().setValue(logEntry)

                                // Run all operations in parallel and wait for them to complete
                                Tasks.whenAllComplete(removeFromDevice, removeFromUser, addLog)
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
        // Get the current user's ID who is performing the action
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
            ?: run {
                Toast.makeText(context, "No authenticated user", Toast.LENGTH_SHORT).show()
                return
            }

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

                            // Prepare log entry
                            val logEntry = mapOf(
                                "timestamp" to ServerValue.TIMESTAMP,
                                "event" to "user_role_updated",
                                "user" to mapOf(
                                    "id" to userId,
                                    "email" to userInfo.email
                                ),
                                "device" to deviceId,
                                "oldRole" to currentRole,
                                "newRole" to newRole
                            )

                            // Proceed with role update
                            userRef.setValue(newRole)
                                .addOnSuccessListener {
                                    // Add log entry under current user's logs
                                    database.child("users").child(currentUserId).child("logs").push().setValue(logEntry)

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



    private fun handleLogout() {
        Log.d(TAG, "Handling logout")
        removeAllListeners()
        sessionManager.logout()

        activity?.runOnUiThread {
            Toast.makeText(
                requireContext(),
                "Session expired. Please log in again.",
                Toast.LENGTH_LONG
            ).show()

            // Navigate to login
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, LoginFragment())
                .commitAllowingStateLoss()
        }
    }

    private fun showWifiDialog(deviceId: String) {
        if (!sessionManager.isLoggedIn()) {
            handleLogout()
            return
        }

        val dialog = dialogManager.createCustomDialog(R.layout.dialog_wifi_connection)
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
        fun newInstance() = MonitoringFragment()
    }
}

data class Device(
    val id: String,
    val name: String,
    val isOnline: Boolean,
    val isLocked: Boolean,
    val isSecure: Boolean,
    val users: List<UserInfo>,
    val userRole: String = "user"  // Default to user role
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

        // Only show devices where user is admin
        holder.itemView.visibility = if (device.userRole == "admin") View.VISIBLE else View.GONE
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            if (device.userRole == "admin") RecyclerView.LayoutParams.WRAP_CONTENT else 0
        )
        holder.deviceNameText.text = device.name
        holder.onlineStatusText.text = "• ${if (device.isOnline) "Online" else "Offline"}"
        holder.onlineStatusText.setTextColor(ContextCompat.getColor(context, if (device.isOnline) android.R.color.holo_green_dark else R.color.maroon))

        holder.lockStatusText.text = "• ${if (device.isLocked) "Locked" else "Unlocked"}"
        holder.lockStatusText.setTextColor(ContextCompat.getColor(context, if (device.isLocked) android.R.color.holo_green_dark else R.color.maroon))

        holder.secureStatusText.text = "• ${if (device.isSecure) "Secure" else "Tamper Detected"}"
        holder.secureStatusText.setTextColor(ContextCompat.getColor(context, if (device.isSecure) android.R.color.holo_green_dark else R.color.maroon))

        // Update WiFi button color based on connection status
        val isConnected = (context as? FragmentActivity)?.supportFragmentManager?.fragments?.firstOrNull { it is MonitoringFragment }?.let {
            (it as MonitoringFragment).isDeviceConnected(device.id)
        } ?: false

        holder.wifiButton.setColorFilter(
            ContextCompat.getColor(context, if (isConnected) android.R.color.holo_green_light else android.R.color.white)
        )

        // Clear previous users
        holder.usersContainer.removeAllViews()

        // Add user views
        device.users.forEach { userInfo ->
            val userView = LayoutInflater.from(context)
                .inflate(R.layout.item_user, holder.usersContainer, false)

            val userText = userView.findViewById<TextView>(R.id.userText)
            val userOptionsButton = userView.findViewById<ImageButton>(R.id.userOptionsButton)

            userText.text = "• ${userInfo.email}"
            userText.setTextColor(ContextCompat.getColor(context, R.color.maroon))

            userOptionsButton.setOnClickListener {
                showUserOptionsMenu(it, device.id, userInfo)
            }

            holder.usersContainer.addView(userView)
        }
    }

    private fun showUserOptionsMenu(view: View, deviceId: String, userInfo: UserInfo) {
        val popupMenu = PopupMenu(context, view).apply {
            inflate(R.menu.user_options_menu)

            // Set text color for all menu items
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val spanString = SpannableString(item.title.toString())
                spanString.setSpan(ForegroundColorSpan(Color.parseColor("#800000")), 0, spanString.length, 0)
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