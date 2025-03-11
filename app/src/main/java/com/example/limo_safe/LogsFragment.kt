package com.example.limo_safe

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var logsAdapter: LogsAdapter
    private lateinit var searchInput: EditText
    private lateinit var database: DatabaseReference
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

        database = FirebaseDatabase.getInstance().reference
        setupSearchBar()
        setupRecyclerView()
        fetchLogs()

        // Add sample data for demonstration
        addSampleData()

        return view
    }

    private fun addSampleData() {
        val currentTime = System.currentTimeMillis()
        val sampleLogs = listOf(
            LogEntry(
                deviceName = "Device1",
                timestamp = Date(currentTime),
                status = "Online",
                userName = "user1@email.com"
            ),
            LogEntry(
                deviceName = "Device2",
                timestamp = Date(currentTime - 3600000), // 1 hour ago
                status = "Offline",
                userName = "user2@email.com"
            ),
            LogEntry(
                deviceName = "Device1",
                timestamp = Date(currentTime - 7200000), // 2 hours ago
                status = "Online",
                userName = "user1@email.com"
            ),
            LogEntry(
                deviceName = "Device3",
                timestamp = Date(currentTime - 10800000), // 3 hours ago
                status = "Online",
                userName = "user3@email.com"
            )
        )
        allLogs.addAll(sampleLogs)
        logsAdapter.updateLogs(allLogs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
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

    private fun fetchLogs() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        logsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                allLogs.clear()

                for (logSnapshot in snapshot.children) {
                    val deviceName = logSnapshot.child("deviceName").getValue(String::class.java)?.replace(" ", "") ?: continue
                    val timestamp = logSnapshot.child("timestamp").getValue(Long::class.java)?.let { Date(it) } ?: continue
                    val status = logSnapshot.child("status").getValue(String::class.java) ?: continue
                    val userName = logSnapshot.child("userName").getValue(String::class.java) ?: "Unknown"

                    allLogs.add(LogEntry(deviceName, timestamp, status, userName))
                }

                // Sort logs by timestamp, newest first
                allLogs.sortByDescending { it.timestamp }

                // Apply current search filter
                filterLogs(searchInput.text.toString())
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }

        // Listen to logs for devices where user is admin
        database.child("users").child(currentUserId).child("registeredDevices")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val adminDevices = snapshot.children
                        .filter { it.getValue(String::class.java) == "admin" }
                        .mapNotNull { it.key }

                    if (adminDevices.isNotEmpty()) {
                        database.child("logs")
                            .orderByChild("deviceId")
                            .addValueEventListener(logsListener!!)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Handle error
                }
            })
    }

    companion object {
        private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun newInstance(): LogsFragment {
            return LogsFragment()
        }
    }
}

data class LogEntry(
    val deviceName: String,
    val timestamp: Date,
    val status: String,
    val userName: String
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun getItemCount() = logs.size

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]

        holder.dateText.apply {
            text = dateFormat.format(log.timestamp)
            setTextColor(0xFF800000.toInt()) // Maroon color from theme
        }

        holder.timeText.apply {
            text = timeFormat.format(log.timestamp)
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
            setTextColor(if (log.status == "Online") 0xFF00FF00.toInt() else 0xFFFF0000.toInt())
        }

        holder.itemView.setBackgroundColor(
            if (position % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF5F5F5.toInt()
        )
    }
}