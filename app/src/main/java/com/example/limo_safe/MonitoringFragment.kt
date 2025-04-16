package com.example.limo_safe

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.example.limo_safe.utils.BiometricManager
import com.example.limo_safe.utils.DeviceNotificationManager
import com.example.limo_safe.utils.DialogManager
import com.example.limo_safe.utils.PasswordConfirmationDialog
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

class MonitoringFragment : Fragment() {
    private lateinit var deviceListRecyclerView: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var backButton: MaterialButton
    private lateinit var tabLayout: TabLayout
    private lateinit var logsFragment: LogsFragment
    private lateinit var dialogManager: DialogManager
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var menuIcon: ImageView
    private lateinit var accountTextView: TextView
    private lateinit var logoutButton: Button
    private lateinit var biometricSetupButton: Button
    private lateinit var biometricManager: BiometricManager
    private lateinit var notificationManager: DeviceNotificationManager
    private val previousDeviceStates = mutableMapOf<String, Triple<Boolean, Boolean, Boolean>>()

    private lateinit var database: DatabaseReference
    private var deviceListListener: ValueEventListener? = null
    private val deviceStatusListeners = mutableMapOf<String, ValueEventListener>()
    private val deviceUsersListeners = mutableMapOf<String, ValueEventListener>()
    private val connectedDevices = mutableSetOf<String>()

    private val devices = mutableListOf<Device>()
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    fun isDeviceConnected(device: Device): Boolean {
        return connectedDevices.contains(device.id)
    }

    private fun updateDeviceConnectionStatus(deviceId: String, isConnected: Boolean) {
        if (isConnected) {
            connectedDevices.add(deviceId)
        } else {
            connectedDevices.remove(deviceId)
        }
        deviceAdapter.notifyDataSetChanged()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_monitoring, container, false)

        // Initialize managers first to ensure they're available for all UI operations
        dialogManager = DialogManager(requireContext())
        biometricManager = BiometricManager(requireContext())
        notificationManager = DeviceNotificationManager(requireContext())

        // Initialize views with correct IDs
        deviceListRecyclerView = view.findViewById(R.id.deviceListRecyclerView)
        backButton = view.findViewById(R.id.backButton)
        tabLayout = view.findViewById(R.id.tabLayout)
        drawerLayout = view.findViewById(R.id.drawerLayout)
        menuIcon = view.findViewById(R.id.menuIcon)
        
        // Set up account info and logout button in the navigation drawer
        val navView = view.findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)
        val navHeader = navView.findViewById<View>(R.id.nav_header_layout)
        if (navHeader != null) {
            accountTextView = navHeader.findViewById(R.id.accountTextView)
            logoutButton = navHeader.findViewById(R.id.logoutButton)
            biometricSetupButton = navHeader.findViewById(R.id.biometricSetupButton)
            
            // Set up user account info
            val currentUser = FirebaseAuth.getInstance().currentUser
            accountTextView.text = currentUser?.email ?: "account."
            
            // Update biometric button text based on current state
            updateBiometricButtonText()
            
            // Set up biometric login button
            biometricSetupButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                
                // Check if biometric is available
                if (!biometricManager.isBiometricAvailable()) {
                    Toast.makeText(requireContext(), 
                        "Biometric authentication is not available on this device", 
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Get current user email
                val currentUser = FirebaseAuth.getInstance().currentUser
                val email = currentUser?.email
                
                if (email == null) {
                    Toast.makeText(requireContext(), "Unable to get user email", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Check if biometric is already enabled
                if (biometricManager.isBiometricEnabled()) {
                    // Show dialog to disable biometric
                    showDisableBiometricDialog()
                } else {
                    // Show password confirmation dialog before enabling biometric
                    val passwordConfirmationDialog = PasswordConfirmationDialog(requireContext(), dialogManager)
                    passwordConfirmationDialog.showPasswordConfirmationDialog(
                        email = email,
                        biometricManager = biometricManager,
                        fragment = this,
                        onSuccess = {
                            updateBiometricButtonText()
                        },
                        onCancel = {}
                    )
                }
            }
            
            // Set up logout button
            logoutButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                
                // Show confirmation dialog using DialogManager for consistency
                dialogManager.showLogoutConfirmationDialog {
                    try {
                        // First remove all listeners to prevent memory leaks
                        removeAllListeners()
                        
                        // Sign out from Firebase
                        FirebaseAuth.getInstance().signOut()
                        
                        // Use the MainActivity's method to handle navigation
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && !mainActivity.isFinishing) {
                            // Use a handler to post the navigation after the current operation completes
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    // First show the main screen to reset the UI state
                                    mainActivity.showMainScreen()
                                    
                                    // Then navigate to login after a short delay
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        try {
                                            mainActivity.navigateToLogin()
                                        } catch (e: Exception) {
                                            Log.e("MonitoringFragment", "Error in delayed navigation: ${e.message}")
                                        }
                                    }, 300)
                                } catch (e: Exception) {
                                    Log.e("MonitoringFragment", "Error showing main screen: ${e.message}")
                                }
                            }
                        } else {
                            Log.e("MonitoringFragment", "MainActivity is null or finishing")
                        }
                    } catch (e: Exception) {
                        Log.e("MonitoringFragment", "Error during logout: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference
        
        // Set up hamburger menu click listener directly
        menuIcon.setOnClickListener {
            Log.d("MonitoringFragment", "Menu icon clicked")
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        
        // Add drawer listener to dim background when drawer is opened
        val mainContent = view.findViewById<LinearLayout>(R.id.mainContent)
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Apply dim effect based on how far the drawer is open
                val dimAmount = slideOffset * 0.3f // Max 30% dim when fully open (reduced from 60%)
                mainContent?.alpha = 1f - dimAmount // Reduce alpha for dimming
            }

            override fun onDrawerOpened(drawerView: View) {
                // Drawer fully opened
                mainContent?.alpha = 0.7f // 30% dim when fully open (changed from 0.4f)
            }

            override fun onDrawerClosed(drawerView: View) {
                // Drawer fully closed
                mainContent?.alpha = 1.0f // No dim when closed
            }

            override fun onDrawerStateChanged(newState: Int) {
                // Not needed for dimming effect
            }
        })

        // Setup back button with consistent behavior
        backButton.setOnClickListener {
            try {
                // Remove all listeners first
                removeAllListeners()
                
                // Get main activity and update session safely
                if (isAdded && activity != null && !requireActivity().isFinishing) {
                    // Use proper fragment transaction with animations
                    parentFragmentManager.popBackStack()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    Toast.makeText(context, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            // Initialize Firebase Database
            database = FirebaseDatabase.getInstance().getReference()
            
            // Create logs fragment instance
            logsFragment = LogsFragment()
            // Setup recycler view
            setupRecyclerView()
            // Setup tabs
            setupTabs()
            // Setup navigation drawer
            setupNavigationDrawer()
            // Fetch user devices
            fetchUserDevices()
        } catch (e: Exception) {
            Log.e("MonitoringFragment", "Error in onViewCreated: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupNavigationDrawer() {
        try {
            // Set up hamburger menu click listener
            menuIcon.setOnClickListener {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            }
            
            // Set up user account info
            val currentUser = FirebaseAuth.getInstance().currentUser
            accountTextView.text = currentUser?.email ?: "account."
            
            // Update button text based on whether biometric is already enabled
            updateBiometricButtonText()
            
            biometricSetupButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                
                // Check if biometric is available
                if (!biometricManager.isBiometricAvailable()) {
                    Toast.makeText(requireContext(), 
                        "Biometric authentication is not available on this device", 
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Get current user email
                val currentUser = FirebaseAuth.getInstance().currentUser
                val email = currentUser?.email
                
                if (email == null) {
                    Toast.makeText(requireContext(), "Unable to get user email", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Check if biometric is already enabled
                if (biometricManager.isBiometricEnabled()) {
                    // Show dialog to disable biometric
                    showDisableBiometricDialog()
                } else {
                    // Show password confirmation dialog before enabling biometric
                    val passwordConfirmationDialog = PasswordConfirmationDialog(requireContext(), dialogManager)
                    passwordConfirmationDialog.showPasswordConfirmationDialog(
                        email = email,
                        biometricManager = biometricManager,
                        fragment = this,
                        onSuccess = {
                            updateBiometricButtonText()
                        },
                        onCancel = {}
                    )
                }
            }
            
            // Set up logout button
            logoutButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                
                // Show confirmation dialog using DialogManager for consistency
                dialogManager.showLogoutConfirmationDialog {
                    try {
                        // First remove all listeners to prevent memory leaks
                        removeAllListeners()
                        
                        // Sign out from Firebase
                        FirebaseAuth.getInstance().signOut()
                        
                        // Navigate to login fragment directly
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && !mainActivity.isFinishing) {
                            try {
                                // Create and show login fragment directly
                                val loginFragment = LoginFragment()
                                mainActivity.supportFragmentManager.beginTransaction()
                                    .setCustomAnimations(
                                        android.R.anim.fade_in,
                                        android.R.anim.fade_out
                                    )
                                    .replace(R.id.fragmentContainer, loginFragment)
                                    .commit()
                                
                                // Update UI visibility
                                mainActivity.findViewById<View>(R.id.mainContent)?.visibility = View.GONE
                                mainActivity.findViewById<View>(R.id.pressToEnterButton)?.visibility = View.GONE
                                mainActivity.findViewById<View>(R.id.fragmentContainer)?.visibility = View.VISIBLE
                            } catch (e: Exception) {
                                Log.e("MonitoringFragment", "Error navigating to login: ${e.message}")
                            }
                        } else {
                            Log.e("MonitoringFragment", "MainActivity is null or finishing")
                        }
                    } catch (e: Exception) {
                        Log.e("MonitoringFragment", "Error during logout: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MonitoringFragment", "Error setting up navigation drawer: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupTabs() {
        // Create logs fragment instance
        logsFragment = LogsFragment()

        // Handle tab selection
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Device List
                        deviceListRecyclerView.visibility = View.VISIBLE
                        view?.findViewById<FrameLayout>(R.id.logsContainer)?.visibility = View.GONE
                    }
                    1 -> { // Logs
                        deviceListRecyclerView.visibility = View.GONE
                        if (!logsFragment.isAdded) {
                            childFragmentManager.beginTransaction()
                                .add(R.id.logsContainer, logsFragment)
                                .commit()
                        }
                        view?.findViewById<FrameLayout>(R.id.logsContainer)?.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    override fun onPause() {
        super.onPause()
        removeAllListeners()
    }

    override fun onResume() {
        super.onResume()
        // Reset session timeout and update activity

        // Re-fetch data if needed
        fetchUserDevices()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up all listeners
        removeAllListeners()
    }

    private fun removeAllListeners() {
        // Remove device list listener
        deviceListListener?.let { listener ->
            database.child("devices").removeEventListener(listener)
        }

        // Remove all device status listeners
        deviceStatusListeners.forEach { (deviceId, listener) ->
            database.child("devices").child(deviceId).child("status")
                .removeEventListener(listener)
        }
        deviceStatusListeners.clear()

        // Remove all device users listeners
        deviceUsersListeners.forEach { (deviceId, listener) ->
            database.child("devices").child(deviceId).child("registeredUsers")
                .removeEventListener(listener)
        }
        deviceUsersListeners.clear()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(
            devices,
            onUserAdded = { deviceId, email -> addUserToDevice(deviceId, email) },
            onUserDeleted = { deviceId, userInfo -> deleteUserFromDevice(deviceId, userInfo) },
            onUserPromoted = { deviceId, userInfo -> promoteUser(deviceId, userInfo) },
            onUserDemoted = { deviceId, userInfo -> demoteUser(deviceId, userInfo) },
            onWifiClicked = { deviceId -> showWifiDialog(deviceId) },
            context = requireContext(),

        )
        deviceListRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceAdapter
        }
    }

    private fun removeDeviceListeners(deviceId: String) {
        // Remove device status listener if it exists
        deviceStatusListeners[deviceId]?.let { listener ->
            database.child("devices").child(deviceId).child("status")
                .removeEventListener(listener)
            deviceStatusListeners.remove(deviceId)
        }

        // Remove device users listener if it exists
        deviceUsersListeners[deviceId]?.let { listener ->
            database.child("devices").child(deviceId).child("registeredUsers")
                .removeEventListener(listener)
            deviceUsersListeners.remove(deviceId)
        }
    }

    private fun fetchUserDevices() {
        // Remove previous listener if exists
        deviceListListener?.let { listener ->
            database.child("users").child(currentUserId).child("registeredDevices")
                .removeEventListener(listener)
        }

        // Create and set new listener
        val newListener = object : ValueEventListener {
            // Initial setup to get list of devices user has access to
            override fun onDataChange(snapshot: DataSnapshot) {
                // First, note which devices we had before
                val previousDeviceIds = devices.map { it.id }.toSet()
                val currentDeviceIds = mutableSetOf<String>()

                // Process all devices user has access to
                for (deviceSnapshot in snapshot.children) {
                    val deviceId = deviceSnapshot.key ?: continue
                    val role = deviceSnapshot.getValue(String::class.java) ?: "user"
                    currentDeviceIds.add(deviceId)

                    // Only process devices where the user is an admin
                    if (role == "admin") {
                        // Check if device is already in our list
                        val existingDeviceIndex = devices.indexOfFirst { it.id == deviceId }

                        if (existingDeviceIndex == -1) {
                            // New device - add to list
                            val device = Device(
                                id = deviceId,
                                name = deviceId, // Temporary name until we get details
                                isOnline = false,
                                isLocked = false,
                                isSecure = false,
                                users = emptyList()
                            )
                            devices.add(device)

                            // Set up listeners for this device
                            updateDeviceStatus(deviceId)
                            fetchDeviceUsers(deviceId)
                        }
                        // Existing devices keep their current status and listeners
                    }
                }

                // Remove devices user no longer has access to
                val devicesToRemove = previousDeviceIds.minus(currentDeviceIds)
                if (devicesToRemove.isNotEmpty()) {
                    devices.removeAll { it.id in devicesToRemove }
                    // Also remove listeners for these devices
                    for (deviceId in devicesToRemove) {
                        removeDeviceListeners(deviceId)
                    }
                }

                deviceAdapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                // Don't show toast if we're in the process of logging out
                if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) {
                    Toast.makeText(context, "Failed to load devices: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Store the listener before adding it
        deviceListListener = newListener

        // Add the listener to the database reference
        database.child("users").child(currentUserId).child("registeredDevices")
            .addValueEventListener(newListener)
    }

    private fun updateDeviceStatus(deviceId: String) {
        // Remove existing listener if any
        removeDeviceListeners(deviceId)

        // Create new listener for device status
        val deviceStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val deviceIndex = devices.indexOfFirst { it.id == deviceId }
                if (deviceIndex < 0) return

                // Get current status values
                val actualOnlineStatus = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val actualLockStatus = snapshot.child("locked").getValue(Boolean::class.java) ?: false
                val actualSecureStatus = snapshot.child("secure").getValue(Boolean::class.java) ?: false

                // Get device name
                val deviceName = devices[deviceIndex].name ?: deviceId

                // Current states as a triple
                val currentStates = Triple(actualOnlineStatus, actualLockStatus, actualSecureStatus)

                // Check for status changes and update previous states
                val previousState = previousDeviceStates[deviceId]
                previousDeviceStates[deviceId] = notificationManager.notifyChanges(
                    deviceId,
                    deviceName,
                    previousState,
                    currentStates
                )

                // Update device status in list
                val updatedDevice = devices[deviceIndex].copy(
                    isOnline = actualOnlineStatus,
                    isLocked = actualLockStatus,
                    isSecure = actualSecureStatus
                )
                devices[deviceIndex] = updatedDevice

                // Only notify the adapter about this specific item
                deviceAdapter.notifyItemChanged(deviceIndex)

                // Update device connection status
                updateDeviceConnectionStatus(deviceId, actualOnlineStatus)
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }

        // Add listener to database - listen to the entire device node to get all status properties
        database.child("devices").child(deviceId).child("status")
            .addValueEventListener(deviceStatusListener)

        // Store listener reference
        deviceStatusListeners[deviceId] = deviceStatusListener
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

                for (userSnapshot in snapshot.children) {
                    val tag = userSnapshot.key ?: continue
                    val userId = userSnapshot.getValue(String::class.java) ?: continue

                    // Get user's email from their profile
                    database.child("users").child(userId).child("email")
                        .get()
                        .addOnSuccessListener { emailSnapshot ->
                            val email = emailSnapshot.getValue(String::class.java)

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
                                        pendingFetches--
                                        if (pendingFetches == 0) {
                                            updateDeviceWithUsers(deviceId, userList)
                                        }
                                    }
                            } else {
                                pendingFetches--
                                if (pendingFetches == 0) {
                                    updateDeviceWithUsers(deviceId, userList)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
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
                    // Get the user's existing tag from their profile
                    database.child("users").child(removedUserId).child("tag")
                        .get()
                        .addOnSuccessListener { tagSnapshot ->
                            val existingTag = tagSnapshot.getValue(String::class.java)

                            if (!existingTag.isNullOrEmpty()) {
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

    private fun showWifiDialog(deviceId: String) {
        val dialog = Dialog(requireContext()) // Ensures a non-null context
        dialog.setContentView(R.layout.dialog_wifi_connection)
        dialog.setCancelable(true)


        val ssidInput = dialog.findViewById<EditText>(R.id.ssidInput)
        val passwordInput = dialog.findViewById<EditText>(R.id.passwordInput)
        val connectButton = dialog.findViewById<Button>(R.id.connectButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)



        connectButton?.setOnClickListener {
    
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
    
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateBiometricButtonText() {
        if (biometricManager.isBiometricEnabled()) {
            biometricSetupButton.text = "Disable Biometric Login"
        } else {
            biometricSetupButton.text = "Set Up Biometric Login"
        }
    }

    private fun showDisableBiometricDialog() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Disable Biometric Login")
            .setMessage("Are you sure you want to disable biometric login?")
            .setPositiveButton("Yes") { _, _ ->
                biometricManager.disableBiometric()
                Toast.makeText(requireContext(), "Biometric login disabled", Toast.LENGTH_SHORT).show()
                updateBiometricButtonText()
            }
            .setNegativeButton("No", null)
            .create()
            
        dialog.show()
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
    private val context: Context,

) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

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
        val fingerprintButton: ImageButton = itemView.findViewById(R.id.fingerprintButton)

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

            // In the DeviceViewHolder inner class init block
            fingerprintButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val deviceId = devices[position].id

                    // Get the current user's ID
                    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnClickListener

                    // Update Firebase with the current user ID for enrollment
                    FirebaseDatabase.getInstance().reference
                        .child("devices").child(deviceId).child("fingerprint")
                        .child(currentUserId).setValue("enroll")
                        .addOnSuccessListener {
                            // Show toast notification
                            Toast.makeText(context, "Enrolling Fingerprint", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(context, "Failed to enroll fingerprint: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
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
    
            val email = emailInput?.text.toString().trim()
            if (email.isNotEmpty()) {
                onUserAdded(deviceId, email)
                dialog.dismiss()
            } else {
                Toast.makeText(context, "Please enter an email", Toast.LENGTH_SHORT).show()
            }
        }

        cancelButton?.setOnClickListener {
    
            dialog.dismiss()
        }

        // Simple touch listener (no session management)
        dialog.window?.decorView?.setOnTouchListener { _, _ -> false }

        dialog.show()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun getItemCount() = devices.size

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        try {
            if (position >= 0 && position < devices.size) {
                val device = devices[position]
                holder.deviceNameText.text = device.name ?: "Unknown Device"
                
                // Set status texts with null safety
                holder.onlineStatusText.text = "• ${if (device.isOnline) "Online" else "Offline"}"
                try {
                    holder.onlineStatusText.setTextColor(holder.itemView.context.resources.getColor(if (device.isOnline) android.R.color.holo_green_dark else R.color.maroon))
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                holder.lockStatusText.text = "• ${if (device.isLocked) "Locked" else "Unlocked"}"
                try {
                    holder.lockStatusText.setTextColor(holder.itemView.context.resources.getColor(if (device.isLocked) android.R.color.holo_green_dark else R.color.maroon))
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                holder.secureStatusText.text = "• ${if (device.isSecure) "Secure" else "Tamper Detected"}"
                try {
                    holder.secureStatusText.setTextColor(holder.itemView.context.resources.getColor(if (device.isSecure) android.R.color.holo_green_dark else R.color.maroon))
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Update WiFi button color based on connection status - safely
                var isConnected = false
                try {
                    val activity = holder.itemView.context as? FragmentActivity
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        val fragment = activity.supportFragmentManager.fragments.firstOrNull { it is MonitoringFragment }
                        if (fragment != null && fragment.isAdded) {
                            isConnected = (fragment as MonitoringFragment).isDeviceConnected(device)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    holder.wifiButton.setColorFilter(
                        holder.itemView.context.resources.getColor(if (isConnected) android.R.color.holo_green_light else android.R.color.white)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Clear previous users
                try {
                    holder.usersContainer.removeAllViews()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Add user views safely
                device.users.forEach { userInfo ->
                    try {
                        val userView = LayoutInflater.from(holder.itemView.context)
                            .inflate(R.layout.item_user, holder.usersContainer, false)

                        val userText = userView.findViewById<TextView>(R.id.userText)
                        val userOptionsButton = userView.findViewById<ImageButton>(R.id.userOptionsButton)

                        userText.text = "• ${userInfo.email ?: "Unknown User"}"
                        userText.setTextColor(holder.itemView.context.resources.getColor(R.color.maroon))

                        userOptionsButton.setOnClickListener {
                            try {
                                showUserOptionsMenu(it, device.id, userInfo)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        holder.usersContainer.addView(userView)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showUserOptionsMenu(view: View, deviceId: String, userInfo: UserInfo) {
        try {
            val context = view.context ?: return
            
            val popupMenu = PopupMenu(context, view).apply {
                try {
                    inflate(R.menu.user_options_menu)

                    // Set text color for all menu items
                    for (i in 0 until menu.size()) {
                        try {
                            val item = menu.getItem(i)
                            val spanString = android.text.SpannableString(item.title.toString())
                            spanString.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#800000")), 0, spanString.length, 0)
                            item.title = spanString
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Add touch listener for session activity
            popupMenu.setOnMenuItemClickListener { item ->
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            try {
                popupMenu.show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}