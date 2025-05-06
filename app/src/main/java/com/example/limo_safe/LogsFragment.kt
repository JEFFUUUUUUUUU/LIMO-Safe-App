package com.example.limo_safe

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.example.limo_safe.utils.DialogManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.GenericTypeIndicator
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var logsAdapter: LogsAdapter
    private lateinit var searchInput: EditText
    private lateinit var database: DatabaseReference

    private lateinit var dialogManager: DialogManager
    private var allLogs: MutableList<LogEntry> = mutableListOf()
    private var logsListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)
        logsRecyclerView = view.findViewById(R.id.logsRecyclerView)
        searchInput = view.findViewById(R.id.searchInput)

        // Initialize dialog manager
        dialogManager = DialogManager(requireContext())
        database = FirebaseDatabase.getInstance().reference

        setupSearchBar()
        setupRecyclerView()
        fetchLogs()

        // Simple touch listener (no session management)
        val touchListener = View.OnTouchListener { _, _ -> false
        }

        view.setOnTouchListener(touchListener)
        logsRecyclerView.setOnTouchListener(touchListener)
        searchInput.setOnTouchListener(touchListener)

        return view
    }

    override fun onDestroyView() {
        try {
            // Clean up Firebase listeners
            removeAllListeners()

            // Clean up RecyclerView
            logsRecyclerView.adapter = null

            // Remove touch listeners
            view?.setOnTouchListener(null)
            logsRecyclerView.setOnTouchListener(null)
            searchInput.setOnTouchListener(null)

            // Clear data
            allLogs.clear()
            processedLogTimestamps.clear()
        } catch (e: Exception) {
            Log.e("LogsFragment", "Error in onDestroyView: ${e.message}")
        }
        super.onDestroyView()
    }

    private fun setupSearchBar() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterLogs(s?.toString() ?: "")
            }
        })
    }

    private fun filterLogs(query: String) {
        if (query.isEmpty()) {
            logsAdapter.updateLogs(allLogs)
        } else {
            val filteredLogs = allLogs.filter { log ->
                log.deviceName.contains(query, ignoreCase = true) ||
                        log.status.contains(query, ignoreCase = true) ||
                        log.userName.contains(query, ignoreCase = true) ||
                        log.eventType.contains(query, ignoreCase = true) ||
                        dateFormat.format(log.timestamp).contains(query, ignoreCase = true) ||
                        timeFormat.format(log.timestamp).contains(query, ignoreCase = true)
            }
            logsAdapter.updateLogs(filteredLogs)
        }
    }

    private fun setupRecyclerView() {
        logsAdapter = LogsAdapter(allLogs)
        logsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logsAdapter
        }
    }

    private val processedLogTimestamps = mutableSetOf<Long>()

    private lateinit var userLogsListener: ValueEventListener
    private val deviceLogsListeners = mutableMapOf<String, ValueEventListener>()

    private fun fetchLogs() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        val currentUserId = currentUser?.uid

        if (currentUser == null || currentUserId == null) {
            // Update UI to show no permission
            view?.findViewById<TextView>(R.id.emptyStateText)?.apply {
                visibility = View.VISIBLE
                text = "You must be logged in to view logs"
                setTextColor(resources.getColor(R.color.maroon, null))
            }
            logsRecyclerView.visibility = View.GONE
            return
        }

        // Reference to user's own logs and registered devices
        val userLogsRef = database.child("users").child(currentUserId).child("logs")
        val registeredDevicesRef = database.child("users").child(currentUserId).child("registeredDevices")

        // Clear any existing logs
        allLogs.clear()
        processedLogTimestamps.clear()

        // Remove any existing listeners
        removeAllListeners()

        // Setup real-time listener for user's logs
        userLogsListener = object : ValueEventListener {
            override fun onDataChange(userLogsSnapshot: DataSnapshot) {
                // Use GenericTypeIndicator for Map retrieval
                val logMapType = object : GenericTypeIndicator<Map<String, Any>>() {}

                // Create a list to collect new logs
                val newUserLogs = mutableListOf<LogEntry>()

                // Process user's own logs
                for (logSnapshot in userLogsSnapshot.children) {
                    val logData = logSnapshot.getValue(logMapType)

                    if (logData != null) {
                        val log = convertSnapshotToLogEntry(logSnapshot.key ?: "", logData, "user", currentUserId)

                        // Prevent duplicate logs using timestamp
                        if (!processedLogTimestamps.contains(log.timestamp)) {
                            newUserLogs.add(log)
                            processedLogTimestamps.add(log.timestamp)
                        }
                    }
                }

                // Add the new logs to our collection
                updateLogsList(newUserLogs)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LogsFragment", "Error fetching user logs", error.toException())
            }
        }
        userLogsRef.addValueEventListener(userLogsListener)

        // Setup listener for registered devices
        registeredDevicesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(registeredDevicesSnapshot: DataSnapshot) {
                // Remove existing device listeners
                for (deviceId in deviceLogsListeners.keys) {
                    database.child("devices").child(deviceId).child("logs")
                        .removeEventListener(deviceLogsListeners[deviceId]!!)
                }
                deviceLogsListeners.clear()

                // Iterate through registered devices
                for (deviceSnapshot in registeredDevicesSnapshot.children) {
                    val deviceId = deviceSnapshot.key ?: continue

                    // Check if device is admin by checking its value
                    val deviceData = deviceSnapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})
                    val isAdminDevice = deviceData?.get("role") == "admin"

                    // In fetchLogs() method, where it creates the device log listener:
                    if (isAdminDevice) {
                        // Create a new listener for this device's logs, passing the currentUserId
                        val deviceLogListener = createDeviceLogListener(deviceId, currentUserId)

                        // Store reference to listener
                        deviceLogsListeners[deviceId] = deviceLogListener

                        // Add the listener
                        database.child("devices").child(deviceId).child("logs")
                            .addValueEventListener(deviceLogListener)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LogsFragment", "Error fetching registered devices", error.toException())
            }
        })
    }

    private fun createDeviceLogListener(deviceId: String, currentUserId: String): ValueEventListener {
        return object : ValueEventListener {
            override fun onDataChange(deviceLogsSnapshot: DataSnapshot) {
                // Use GenericTypeIndicator for Map retrieval
                val deviceLogMapType = object : GenericTypeIndicator<Map<String, Any>>() {}

                // Create a list to collect new logs
                val newDeviceLogs = mutableListOf<LogEntry>()

                // Process device logs
                for (logSnapshot in deviceLogsSnapshot.children) {
                    val logData = logSnapshot.getValue(deviceLogMapType)

                    if (logData != null) {
                        val log = convertSnapshotToLogEntry(
                            logSnapshot.key ?: "",
                            logData,
                            deviceId,
                            currentUserId
                        )

                        // Prevent duplicate logs using timestamp
                        if (!processedLogTimestamps.contains(log.timestamp)) {
                            newDeviceLogs.add(log)
                            processedLogTimestamps.add(log.timestamp)
                        }
                    }
                }

                // Add the new logs to our collection
                updateLogsList(newDeviceLogs)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LogsFragment", "Error fetching logs for device $deviceId", error.toException())
            }
        }
    }

    private fun updateLogsList(newLogs: List<LogEntry>) {
        // Add new logs
        allLogs.addAll(newLogs)

        // Sort all logs by timestamp (newest first)
        allLogs.sortByDescending { it.timestamp }

        // Update the adapter with the sorted list
        logsAdapter.updateLogs(allLogs)

        // Also update the filtered view if search is active
        if (searchInput.text.isNotEmpty()) {
            filterLogs(searchInput.text.toString())
        }
    }

    private fun removeAllListeners() {
        // Remove user logs listener
        if (::userLogsListener.isInitialized) {
            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            database.child("users").child(currentUserId).child("logs")
                .removeEventListener(userLogsListener)
        }

        // Remove all device logs listeners
        for (deviceId in deviceLogsListeners.keys) {
            database.child("devices").child(deviceId).child("logs")
                .removeEventListener(deviceLogsListeners[deviceId]!!)
        }
        deviceLogsListeners.clear()
    }

    // Modified to accept a deviceId parameter
    private fun convertSnapshotToLogEntry(
        key: String,
        logData: Map<String, Any>,
        deviceId: String,
        currentUserId: String? = null
    ): LogEntry {
        // Extract timestamp safely
        val timestamp = (logData["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()

        // Use the deviceId from the path for device logs
        val resolvedDeviceId = if (deviceId == "user") {
            // For user logs, try to get device from log data or use "System"
            logData["device"] as? String ?: "System"
        } else {
            // For device logs, use the deviceId from the path
            deviceId
        }

        // Check for specific fields first to handle events without an "event" field
        if (logData.containsKey("locked")) {
            val isLocked = logData["locked"] as? Boolean ?: false
            val userName = if (logData.containsKey("user")) {
                val userData = logData["user"] as? Map<String, Any>
                userData?.get("email") as? String ?: "System"
            } else "System"

            return LogEntry(
                deviceName = resolvedDeviceId,
                timestamp = timestamp,
                status = if (isLocked) "Device Locked" else "Device Unlocked",
                userName = userName,
                eventType = "Security",
                uniqueId = key
            )
        }

        if (logData.containsKey("secure")) {
            val isSecure = logData["secure"] as? Boolean ?: false
            return LogEntry(
                deviceName = resolvedDeviceId,
                timestamp = timestamp,
                status = if (isSecure) "Device Secured" else "Tamper Detected",
                userName = "System",
                eventType = "Security",
                uniqueId = key
            )
        }

        // Determine event type with more precise matching
        val event = logData["event"] as? String ?: "Unknown Event"

        // Handle events with an "event" field
        return when (event) {
            // Lock state events
            "lock" -> {
                val isLocked = logData["locked"] as? Boolean ?: false
                val userName = if (logData.containsKey("user")) {
                    val userData = logData["user"] as? Map<String, Any>
                    userData?.get("email") as? String ?: "System"
                } else "System"

                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = if (isLocked) "Device Locked" else "Device Unlocked",
                    userName = userName,
                    eventType = "Security",
                    uniqueId = key
                )
            }
            "security" -> {
                val isSecure = logData["secure"] as? Boolean ?: false
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = if (isSecure) "Device Secured" else "Tamper Detected",
                    userName = "System",
                    eventType = "Security",
                    uniqueId = key
                )
            }
            "unauthorized_user_attempt" -> {
                val userTag = logData["user_tag"] as? String ?: "Unknown"
                val userId = logData["user_id"] as? String ?: ""

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Unauthorized User Attempt",
                    userName = userTag,
                    eventType = "Security",
                    uniqueId = key
                )

                if (userId.isNotEmpty()) {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "otp" -> {
                // Create log entry with placeholder, then fetch email
                val logEntry = LogEntry(
                    deviceName = "System",
                    timestamp = timestamp,
                    status = "OTP Generated",
                    userName = "Loading...", // Placeholder while loading
                    eventType = "Authentication",
                    uniqueId = key
                )

                // Start email fetch if we have the current user ID
                if (currentUserId != null) {
                    fetchUserEmail(currentUserId) { email ->
                        // Find and update this log entry in the list
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: currentUserId)
                            allLogs[index] = updatedLog
                            // Notify adapter on UI thread
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                return logEntry
            }
            "otp_verified" -> {
                // Create log entry with placeholder, then fetch email
                val userId = logData["user"] as? String ?: "Unknown"
                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "OTP Verified",
                    userName = "Loading...", // Placeholder while loading
                    eventType = "Authentication",
                    uniqueId = key
                )

                // Start email fetch if we have a valid userId
                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        // Find and update this log entry in the list
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            // Notify adapter on UI thread
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                return logEntry
            }
            "otp_format_invalid" -> {
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Invalid OTP Format",
                    userName = "System",
                    eventType = "Authentication",
                    uniqueId = key
                )
            }
            "otp_verification_failed" -> {
                val userTag = logData["user_tag"] as? String ?: "Unknown"
                
                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "OTP Verification Failed",
                    userName = userTag, // Initial placeholder showing the tag
                    eventType = "Authentication",
                    uniqueId = key
                )
                
                // Lookup userId from tag, then fetch email
                if (userTag != "Unknown") {
                    fetchUserIdFromTag(resolvedDeviceId, userTag) { userId ->
                        if (userId != null) {
                            fetchUserEmail(userId) { email ->
                                val index = allLogs.indexOfFirst { it.uniqueId == key }
                                if (index != -1) {
                                    val updatedLog = logEntry.copy(userName = email ?: userTag)
                                    allLogs[index] = updatedLog
                                    Handler(Looper.getMainLooper()).post {
                                        logsAdapter.notifyItemChanged(index)
                                    }
                                }
                            }
                        }
                    }
                }
                
                logEntry
            }
            "user_verification_failed" -> {
                val userTag = logData["user_tag"] as? String ?: "Unknown"
                
                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "User Verification Failed",
                    userName = userTag,
                    eventType = "Authentication",
                    uniqueId = key
                )

                // Lookup userId from tag, then fetch email
                if (userTag != "Unknown") {
                    fetchUserIdFromTag(resolvedDeviceId, userTag) { userId ->
                        if (userId != null) {
                            fetchUserEmail(userId) { email ->
                                val index = allLogs.indexOfFirst { it.uniqueId == key }
                                if (index != -1) {
                                    val updatedLog = logEntry.copy(userName = email ?: userTag)
                                    allLogs[index] = updatedLog
                                    Handler(Looper.getMainLooper()).post {
                                        logsAdapter.notifyItemChanged(index)
                                    }
                                }
                            }
                        }
                    }
                }

                logEntry
            }
            "fingerprint_enrolled" -> {
                // Handle fingerprint enrollment logs
                val userId = logData["userId"] as? String ?: "Unknown"
                val fingerprintId = (logData["fingerprintId"] as? Number)?.toInt() ?: 0

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Fingerprint #$fingerprintId Enrolled",
                    userName = "Loading...", // Placeholder while loading
                    eventType = "Biometric",
                    uniqueId = key
                )

                // Start email fetch if we have a valid userId
                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        // Find and update this log entry in the list
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            // Notify adapter on UI thread
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                return logEntry
            }
            "fingerprint_enrollment_failed" -> {
                // Handle fingerprint enrollment failure logs
                val userId = logData["userId"] as? String ?: "Unknown"
                val reason = logData["reason"] as? String ?: "Unknown"

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Fingerprint Enrollment Failed: ${reason.replace("_", " ").capitalize()}",
                    userName = "Loading...", // Placeholder while loading
                    eventType = "Biometric",
                    uniqueId = key
                )

                // Start email fetch if we have a valid userId
                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        // Find and update this log entry in the list
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            // Notify adapter on UI thread
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                return logEntry
            }
            "fingerprint_authentication_success" -> {
                val fingerprintId = (logData["fingerprintId"] as? Number)?.toInt() ?: 0
                val userId = logData["userId"] as? String ?: ""

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Fingerprint Authentication Success (#$fingerprintId)",
                    userName = "System",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId.isNotEmpty()) {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "fingerprint_authentication_failed" -> {
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Fingerprint Authentication Failed",
                    userName = "System",
                    eventType = "Biometric",
                    uniqueId = key
                )
            }
            "multiple_fingerprints_deleted" -> {
                // Handle fingerprint delete logs
                val userId = logData["userId"] as? String ?: "Unknown"
                val total = logData["total"] as? String ?: "Unknown"

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Fingerprint Deleted",
                    userName = "Loading...", // Placeholder while loading
                    eventType = "Biometric",
                    uniqueId = key
                )

                // Start email fetch if we have a valid userId
                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        // Find and update this log entry in the list
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            // Notify adapter on UI thread
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                return logEntry
            }
            "all_fingerprints_deleted" -> {
                val userId = logData["userId"] as? String ?: "Unknown"
                val success = logData["success"] as? Boolean ?: false

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = if (success) "All Fingerprints Deleted" else "Failed to Delete All Fingerprints",
                    userName = "Loading...",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "user_fingerprints_deleted" -> {
                val userId = logData["userId"] as? String ?: "Unknown"
                val total = (logData["total"] as? Number)?.toInt() ?: 0
                val success = (logData["success"] as? Number)?.toInt() ?: 0

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "All User Fingerprints Deleted ($success/$total)",
                    userName = "Loading...",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "user_fingerprints_delete_partial" -> {
                val userId = logData["userId"] as? String ?: "Unknown"
                val total = (logData["total"] as? Number)?.toInt() ?: 0
                val success = (logData["success"] as? Number)?.toInt() ?: 0

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Partial Fingerprint Deletion ($success/$total)",
                    userName = "Loading...",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "fingerprint_deleted" -> {
                val userId = logData["userId"] as? String ?: "Unknown"
                val fingerprintId = (logData["fingerprintId"] as? Number)?.toInt() ?: 0

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Fingerprint #$fingerprintId Deleted",
                    userName = "Loading...",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "fingerprint_delete_failed" -> {
                val userId = logData["userId"] as? String ?: "Unknown"
                val fingerprintId = (logData["fingerprintId"] as? Number)?.toInt() ?: 0

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Failed to Delete Fingerprint #$fingerprintId",
                    userName = "Loading...",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "fingerprint_delete_no_fingerprints" -> {
                val userId = logData["userId"] as? String ?: "Unknown"

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "No Fingerprints Found to Delete",
                    userName = "Loading...",
                    eventType = "Biometric",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "user_role_updated" -> {
                val updatedUser = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Role: ${logData["newRole"] as? String ?: "Unknown"}",
                    userName = updatedUser["email"] as? String ?: "Unknown",
                    eventType = "User Role Update",
                    uniqueId = key
                )
            }
            "user_added_to_device" -> {
                val userData = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Added to Device",
                    userName = userData["email"] as? String ?: "Unknown",
                    eventType = "User Management",
                    uniqueId = key
                )
            }
            "user_removed_from_device" -> {
                val userData = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Removed from Device",
                    userName = userData["email"] as? String ?: "Unknown",
                    eventType = "User Management",
                    uniqueId = key
                )
            }
            "first_user_registration_failed" -> {
                val userId = logData["user_id"] as? String ?: "Unknown"
                val userTag = logData["user_tag"] as? String ?: "Unknown"

                val logEntry = LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "First User Registration Failed",
                    userName = "Loading...",
                    eventType = "User Management",
                    uniqueId = key
                )

                if (userId != "Unknown") {
                    fetchUserEmail(userId) { email ->
                        val index = allLogs.indexOfFirst { it.uniqueId == key }
                        if (index != -1) {
                            val updatedLog = logEntry.copy(userName = email ?: userId)
                            allLogs[index] = updatedLog
                            Handler(Looper.getMainLooper()).post {
                                logsAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                logEntry
            }
            "device_online" -> {
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "Device Online",
                    userName = logData["ip_address"] as? String ?: "Unknown IP",
                    eventType = "Network",
                    uniqueId = key
                )
            }
            "wifi_connected" -> {
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = "WiFi Connected: ${logData["ssid"] as? String ?: "Unknown Network"}",
                    userName = logData["ip_address"] as? String ?: "N/A",
                    eventType = "Network",
                    uniqueId = key
                )
            }
            else -> {
                LogEntry(
                    deviceName = resolvedDeviceId,
                    timestamp = timestamp,
                    status = event,
                    userName = "System",
                    eventType = "Unknown Event",
                    uniqueId = key
                )
            }
        }
    }

    private fun fetchUserEmail(userId: String, callback: (String?) -> Unit) {
        FirebaseDatabase.getInstance().reference
            .child("users")
            .child(userId)
            .child("email")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val email = snapshot.getValue(String::class.java)
                    callback(email)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LogsFragment", "Error fetching user email", error.toException())
                    callback(null)
                }
            })
    }

    private fun fetchUserIdFromTag(deviceId: String, tagId: String, callback: (String?) -> Unit) {
        // Query the users to find the one with this tag
        val usersRef = database.child("users")
        
        usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var foundUserId: String? = null
                
                // Iterate through all users to find which one has this tag
                for (userSnapshot in snapshot.children) {
                    val userId = userSnapshot.key
                    val userTag = userSnapshot.child("tag").getValue(String::class.java)
                    
                    if (userTag == tagId) {
                        foundUserId = userId
                        break
                    }
                }
                
                callback(foundUserId)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e("LogsFragment", "Error fetching userId from tag", error.toException())
                callback(null)
            }
        })
    }

    companion object {
        private val lastLoggedDeviceRoles = mutableMapOf<String, String>()

        private val dateFormat = SimpleDateFormat("MM/dd/yy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun newInstance(): LogsFragment {
            return LogsFragment()
        }
    }

}

data class LogEntry(
    val deviceName: String = "",
    val timestamp: Long = 0,
    val status: String = "",
    val userName: String = "",
    val eventType: String = "",
    val uniqueId: String = "" // New field
)

class LogsAdapter(private var logs: List<LogEntry>) :
    RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun updateLogs(newLogs: List<LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dateText: TextView = itemView.findViewById(R.id.dateText)
        val timeText: TextView = itemView.findViewById(R.id.timeText)
        val userText: TextView = itemView.findViewById(R.id.userText)
        val deviceNameText: TextView = itemView.findViewById(R.id.deviceNameText)
        val statusText: TextView = itemView.findViewById(R.id.statusText)
        val eventTypeText: TextView = itemView.findViewById(R.id.eventTypeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun getItemCount() = logs.size

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]
        val logDate = Date(log.timestamp) // ✅ Convert Long to Date

        holder.dateText.apply {
            text = dateFormat.format(logDate) // ✅ Format as Date
            setTextColor(0xFF800000.toInt()) // Maroon color from theme
        }

        holder.timeText.apply {
            text = timeFormat.format(logDate) // ✅ Format as Time
            setTextColor(0xFF800000.toInt()) // Maroon color from theme
        }

        holder.userText.apply {
            text = log.userName
            setTextColor(0xFF800000.toInt()) // Maroon color from theme
        }

        holder.deviceNameText.apply {
            text = log.deviceName
            setTextColor(0xFF800000.toInt()) // Maroon color from theme
        }

        holder.statusText.apply {
            text = log.status
            setTextColor(when {
                log.status.contains("Online", true) -> 0xFF00FF00.toInt()
                log.status.contains("Offline", true) -> 0xFFFF0000.toInt()
                log.status.contains("Locked", true) -> 0xFF00FF00.toInt()
                log.status.contains("Unlocked", true) -> 0xFFFF0000.toInt()
                log.status.contains("Secure", true) -> 0xFF00FF00.toInt()
                log.status.contains("Unsecure", true) -> 0xFFFF0000.toInt()
                else -> 0xFF800000.toInt()
            })
        }

        holder.eventTypeText.apply {
            text = log.eventType
            setTextColor(0xFF800000.toInt()) // Maroon color from theme
        }

        holder.itemView.setBackgroundColor(
            if (position % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF5F5F5.toInt()
        )
    }
}