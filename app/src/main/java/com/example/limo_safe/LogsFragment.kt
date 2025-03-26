package com.example.limo_safe

import android.os.Bundle
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
import com.google.firebase.database.ServerValue
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
        processedLogTimestamps.clear()
        logsListener?.let {
            database.child("logs").removeEventListener(it)
        }
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

    private fun saveLogToFirebase(log: LogEntry) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return // ✅ Retrieve user ID inside function

        val logRef = database.child("users").child(currentUserId).child("logs").push()
        logRef.setValue(log)
    }

    private val processedLogTimestamps = mutableSetOf<Long>()

    private fun fetchLogs() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        database.child("users").child(currentUserId).child("registeredDevices")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val adminDevices = snapshot.children
                        .filter { it.getValue(String::class.java) == "admin" }
                        .mapNotNull { it.key }

                    val userDevices = snapshot.children.mapNotNull { it.key }

                    if (userDevices.isNotEmpty()) {
                        // ✅ Clear logs before adding new ones to prevent duplicates
                        allLogs.clear()
                        processedLogTimestamps.clear()

                        database.child("users").child(currentUserId).child("logs")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    val retrievedLogs = mutableListOf<LogEntry>()

                                    for (logSnapshot in snapshot.children) {
                                        logSnapshot.getValue(LogEntry::class.java)?.let { newLog ->
                                            // ✅ Prevent duplicate logs using timestamp
                                            if (!processedLogTimestamps.contains(newLog.timestamp)) {
                                                retrievedLogs.add(newLog)
                                                processedLogTimestamps.add(newLog.timestamp)
                                            }
                                        }
                                    }

                                    // ✅ Replace logs instead of appending
                                    allLogs.clear()
                                    allLogs.addAll(retrievedLogs.sortedByDescending { it.timestamp })
                                    logsAdapter.updateLogs(allLogs)
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    Log.e("LogsFragment", "Error fetching logs", error.toException())
                                }
                            })

                        val firstDeviceId = userDevices.firstOrNull()
                        if (firstDeviceId != null) {
                            fetchUserEvents(firstDeviceId)
                        } else {
                            Log.e("fetchLogs", "No valid device ID found for user: $currentUserId")
                        }

                        fetchDeviceEvents(adminDevices)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LogsFragment", "Error fetching registered devices", error.toException())
                }
            })
    }

    private val lastLoggedRolesPerDevice = mutableMapOf<String, MutableMap<String, String>>() // 🔥 Stores last known roles per device & user

    private fun fetchUserEvents(deviceId: String) {
        val usersReference = database.child("devices").child(deviceId).child("registeredUsers")

        usersReference.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(usersSnapshot: DataSnapshot) {
                if (!usersSnapshot.exists()) {
                    Log.e("fetchUserEvents", "No registered users found for device: $deviceId")
                    return
                }

                val userEvents = mutableListOf<LogEntry>()
                val lastRolesForDevice = lastLoggedRolesPerDevice.getOrPut(deviceId) { mutableMapOf() }

                usersSnapshot.children.forEach { userEntry ->
                    val userId = userEntry.getValue(String::class.java) ?: return@forEach

                    val userDeviceRef = database.child("users").child(userId).child("registeredDevices").child(deviceId)

                    userDeviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(userSnapshot: DataSnapshot) {
                            val deviceRole = userSnapshot.getValue(String::class.java) ?: "Unknown"

                            database.child("users").child(userId).child("email")
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(emailSnapshot: DataSnapshot) {
                                        val email = emailSnapshot.getValue(String::class.java) ?: "Unknown Email"

                                        // Check if role has changed
                                        if (lastRolesForDevice[userId] != deviceRole) {
                                            lastRolesForDevice[userId] = deviceRole

                                            val logEntry = LogEntry(
                                                deviceName = deviceId,
                                                timestamp = System.currentTimeMillis(),
                                                status = "Role: $deviceRole",
                                                userName = email,
                                                eventType = "User Device Role Change"
                                            )

                                            // Duplicate prevention
                                            if (!processedLogTimestamps.contains(logEntry.timestamp)) {
                                                userEvents.add(logEntry)
                                                processedLogTimestamps.add(logEntry.timestamp)
                                                saveLogToFirebase(logEntry)
                                            }
                                        }
                                    }

                                    override fun onCancelled(error: DatabaseError) {
                                        Log.e("fetchUserEvents", "Error fetching email", error.toException())
                                    }
                                })
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e("fetchUserEvents", "Error fetching user role", error.toException())
                        }
                    })
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("fetchUserEvents", "Error fetching registered users", error.toException())
            }
        })
    }

    private val lastDeviceStates = mutableMapOf<String, DeviceState>() // Stores last known states

    private fun fetchDeviceEvents(adminDevices: List<String>) {
        database.child("devices").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val deviceEvents = mutableListOf<LogEntry>()

                snapshot.children.forEach { deviceSnapshot ->
                    val deviceId = deviceSnapshot.key ?: return@forEach

                    if (deviceId !in adminDevices) return@forEach

                    val deviceStatus = deviceSnapshot.child("status")

                    val currentState = DeviceState(
                        online = deviceStatus.child("online").getValue(Boolean::class.java) ?: false,
                        locked = deviceStatus.child("locked").getValue(Boolean::class.java) ?: false,
                        secure = deviceStatus.child("secure").getValue(Boolean::class.java) ?: false
                    )

                    val lastState = lastDeviceStates[deviceId]

                    // Online Status Log
                    if (lastState == null || lastState.online != currentState.online) {
                        val logEntry = LogEntry(
                            deviceId,
                            System.currentTimeMillis(),
                            if (currentState.online) "Online" else "Offline",
                            "System",
                            "Device Online Status"
                        )

                        // Duplicate prevention
                        if (!processedLogTimestamps.contains(logEntry.timestamp)) {
                            deviceEvents.add(logEntry)
                            processedLogTimestamps.add(logEntry.timestamp)
                            saveLogToFirebase(logEntry)
                        }
                    }
                    if (lastState == null || lastState.locked != currentState.locked) {
                        val logEntry = LogEntry(
                            deviceId,
                            System.currentTimeMillis(),
                            if (currentState.locked) "Locked" else "Unlocked",
                            "System",
                            "Device Lock Status"
                        )

                        // Duplicate prevention
                        if (!processedLogTimestamps.contains(logEntry.timestamp)) {
                            deviceEvents.add(logEntry)
                            processedLogTimestamps.add(logEntry.timestamp)
                            saveLogToFirebase(logEntry)
                        }
                    }
                    if (lastState == null || lastState.secure != currentState.secure) {
                        val logEntry = LogEntry(
                            deviceId,
                            System.currentTimeMillis(),
                            if (currentState.secure) "Secure" else "Unsecure",
                            "System",
                            "Device Security Status"
                        )

                        // Duplicate prevention
                        if (!processedLogTimestamps.contains(logEntry.timestamp)) {
                            deviceEvents.add(logEntry)
                            processedLogTimestamps.add(logEntry.timestamp)
                            saveLogToFirebase(logEntry)
                        }
                    }

                    // Update last known device state
                    lastDeviceStates[deviceId] = currentState
                }

                // Add new device events to logs
                allLogs.addAll(deviceEvents)
                allLogs.sortByDescending { it.timestamp }
                logsAdapter.updateLogs(allLogs)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LogsFragment", "Device events query cancelled", error.toException())
            }
        })
    }

    companion object {
        private val lastLoggedDeviceRoles = mutableMapOf<String, String>()

        private fun getLastLoggedRoleForDevice(deviceId: String): String? {
            return lastLoggedDeviceRoles[deviceId]
        }

        private fun updateLastLoggedRoleForDevice(deviceId: String, role: String) {
            lastLoggedDeviceRoles[deviceId] = role
        }
        private val dateFormat = SimpleDateFormat("MM/dd/yy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun newInstance(): LogsFragment {
            return LogsFragment()
        }
    }

    data class DeviceState(
        val online: Boolean,
        val locked: Boolean,
        val secure: Boolean
    )
}

data class LogEntry(
    val deviceName: String = "",
    val timestamp: Long = 0,
    val status: String = "",
    val userName: String = "",
    val eventType: String = "",
    val uniqueId: String = "" // New field
){
    // Firebase requires an empty constructor
    constructor() : this("", 0, "", "", "Unknown")

    // Helper function to convert timestamp back to Date when needed
    fun getDate(): Date {
        return Date(timestamp)
    }
}

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