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

    private val processedLogTimestamps = mutableSetOf<Long>()

    private fun fetchLogs() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        database.child("users").child(currentUserId).child("logs")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val retrievedLogs = mutableListOf<LogEntry>()

                    // Use GenericTypeIndicator for Map retrieval
                    val logMapType = object : GenericTypeIndicator<Map<String, Any>>() {}

                    for (logSnapshot in snapshot.children) {
                        val logData = logSnapshot.getValue(logMapType)

                        if (logData != null) {
                            val log = convertSnapshotToLogEntry(logSnapshot.key ?: "", logData)

                            // Prevent duplicate logs using timestamp
                            if (!processedLogTimestamps.contains(log.timestamp)) {
                                retrievedLogs.add(log)
                                processedLogTimestamps.add(log.timestamp)
                            }
                        }
                    }

                    // Replace logs instead of appending
                    allLogs.clear()
                    allLogs.addAll(retrievedLogs.sortedByDescending { it.timestamp })
                    logsAdapter.updateLogs(allLogs)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LogsFragment", "Error fetching logs", error.toException())
                }
            })
    }

    enum class EventType {
        USER_ROLE_UPDATE,
        OTP_GENERATION,
        USER_ADDED_TO_DEVICE,
        USER_REMOVED_FROM_DEVICE,
        UNKNOWN
    }

    private fun convertSnapshotToLogEntry(key: String, logData: Map<String, Any>): LogEntry {
        // Determine event type with more precise matching
        val event = logData["event"] as? String ?: "Unknown Event"
        val eventType = when (event) {
            "user_role_updated" -> EventType.USER_ROLE_UPDATE
            "otp" -> EventType.OTP_GENERATION
            "user_added_to_device" -> EventType.USER_ADDED_TO_DEVICE
            "user_removed_from_device" -> EventType.USER_REMOVED_FROM_DEVICE
            else -> EventType.UNKNOWN
        }

        // Extract timestamp safely
        val timestamp = (logData["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()

        // Comprehensive event parsing
        return when (eventType) {
            EventType.USER_ROLE_UPDATE -> {
                val updatedUser = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = logData["device"] as? String ?: "",
                    timestamp = timestamp,
                    status = "Role: ${logData["newRole"] as? String ?: "Unknown"}",
                    userName = updatedUser["email"] as? String ?: "Unknown",
                    eventType = "User Role Update",
                    uniqueId = key
                )
            }
            EventType.OTP_GENERATION -> {
                val userData = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = "System",
                    timestamp = timestamp,
                    status = "OTP Generated",
                    userName = userData["email"] as? String ?: "Unknown",
                    eventType = "Authentication",
                    uniqueId = key
                )
            }
            EventType.USER_ADDED_TO_DEVICE -> {
                val userData = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = logData["device"] as? String ?: "Unknown",
                    timestamp = timestamp,
                    status = "Added to Device",
                    userName = userData["email"] as? String ?: "Unknown",
                    eventType = "User Management",
                    uniqueId = key
                )
            }
            EventType.USER_REMOVED_FROM_DEVICE -> {
                val userData = logData["user"] as? Map<String, Any> ?: emptyMap()
                LogEntry(
                    deviceName = logData["device"] as? String ?: "Unknown",
                    timestamp = timestamp,
                    status = "Removed from Device",
                    userName = userData["email"] as? String ?: "Unknown",
                    eventType = "User Management",
                    uniqueId = key
                )
            }
            EventType.UNKNOWN -> {
                LogEntry(
                    deviceName = "System",
                    timestamp = timestamp,
                    status = event,
                    userName = "System",
                    eventType = "Unknown Event",
                    uniqueId = key
                )
            }
        }
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