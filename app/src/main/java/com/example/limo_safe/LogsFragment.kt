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
import com.example.limo_safe.Object.SessionManager
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
    private lateinit var sessionManager: SessionManager
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

        sessionManager = SessionManager(requireActivity()) {
            navigateToLogin()
        }

        dialogManager = DialogManager(requireContext())

        database = FirebaseDatabase.getInstance().reference
        setupSearchBar()
        setupRecyclerView()
        fetchLogs()

        view.setOnTouchListener { _, _ ->
            sessionManager.userActivityDetected()
            false
        }
        logsRecyclerView.setOnTouchListener { _, _ ->
            sessionManager.userActivityDetected()
            false
        }
        searchInput.setOnTouchListener { _, _ ->
            sessionManager.userActivityDetected()
            false
        }

        return view
    }

    private fun navigateToLogin() {
        val loginFragment = LoginFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        removeAllListeners()
        processedLogTimestamps.clear()
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
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

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
                        val log = convertSnapshotToLogEntry(logSnapshot.key ?: "", logData, "user")

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
                    val isAdminDevice = deviceSnapshot.getValue(String::class.java) == "admin"

                    if (isAdminDevice) {
                        // Create a new listener for this device's logs
                        val deviceLogListener = createDeviceLogListener(deviceId)

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

    private fun createDeviceLogListener(deviceId: String): ValueEventListener {
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
                        val log = convertSnapshotToLogEntry(logSnapshot.key ?: "", logData, deviceId)

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
    private fun convertSnapshotToLogEntry(key: String, logData: Map<String, Any>, deviceId: String): LogEntry {
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
            return LogEntry(
                deviceName = resolvedDeviceId,
                timestamp = timestamp,
                status = if (isLocked) "Device Locked" else "Device Unlocked",
                userName = "System",
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
            "otp" -> {
                val userData = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = "System",  // Keep using "System" for system-wide events
                    timestamp = timestamp,
                    status = "OTP Generated",
                    userName = userData["email"] as? String ?: "Unknown",
                    eventType = "Authentication",
                    uniqueId = key
                )
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