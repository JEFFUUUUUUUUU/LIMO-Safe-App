package com.example.limo_safe

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val deviceName: String = "",
    val timestamp: Long = 0,
    val status: String = "",
    val userName: String = "",
    val eventType: String = "",
    val uniqueId: String = ""
)

class LogsFragment : Fragment() {
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var dialogManager: DialogManager
    private lateinit var database: DatabaseReference
    private lateinit var logsListener: ValueEventListener
    private val logs = mutableListOf<LogEntry>()
    private val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    companion object {
        fun newInstance() = LogsFragment()
        private val dateFormat = SimpleDateFormat("MM/dd/yy", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialogManager = DialogManager(requireContext())
        database = Firebase.database.reference

        initializeViews(view)
        setupRecyclerView()
        setupSearchBar()
        setupListeners()
    }

    private fun initializeViews(view: View) {
        logsRecyclerView = view.findViewById(R.id.logsRecyclerView)
        searchInput = view.findViewById(R.id.searchInput)
    }

    private fun setupRecyclerView() {
        logsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = LogsAdapter(logs)
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
        val filteredLogs = if (query.isEmpty()) {
            logs
        } else {
            logs.filter { log ->
                log.deviceName.contains(query, ignoreCase = true) ||
                log.status.contains(query, ignoreCase = true) ||
                log.userName.contains(query, ignoreCase = true) ||
                log.eventType.contains(query, ignoreCase = true) ||
                dateFormat.format(Date(log.timestamp)).contains(query, ignoreCase = true) ||
                timeFormat.format(Date(log.timestamp)).contains(query, ignoreCase = true)
            }
        }
        (logsRecyclerView.adapter as LogsAdapter).updateLogs(filteredLogs)
    }

    private fun setupListeners() {
        logsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val updatedLogs = mutableListOf<LogEntry>()
                snapshot.children.forEach { logSnapshot ->
                    val logData = logSnapshot.getValue(LogEntry::class.java)
                    logData?.let { updatedLogs.add(it) }
                }
                logs.clear()
                logs.addAll(updatedLogs.sortedByDescending { it.timestamp })
                logsRecyclerView.adapter?.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                dialogManager.showErrorDialog(
                    title = "Error",
                    message = "Error loading logs: ${error.message}"
                )
            }
        }

        database.child("users").child(currentUserId).child("logs")
            .addValueEventListener(logsListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        database.child("users").child(currentUserId).child("logs")
            .removeEventListener(logsListener)
    }

    inner class LogsAdapter(private var logs: List<LogEntry>) : 
        RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

        fun updateLogs(newLogs: List<LogEntry>) {
            logs = newLogs
            notifyDataSetChanged()
        }

        inner class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dateText: TextView = view.findViewById(R.id.dateText)
            val timeText: TextView = view.findViewById(R.id.timeText)
            val userText: TextView = view.findViewById(R.id.userText)
            val deviceNameText: TextView = view.findViewById(R.id.deviceNameText)
            val statusText: TextView = view.findViewById(R.id.statusText)
            val eventTypeText: TextView = view.findViewById(R.id.eventTypeText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val log = logs[position]
            val date = Date(log.timestamp)
            
            holder.dateText.text = dateFormat.format(date)
            holder.timeText.text = timeFormat.format(date)
            holder.userText.text = log.userName
            holder.deviceNameText.text = log.deviceName
            holder.statusText.text = log.status
            holder.eventTypeText.text = log.eventType

            // Set alternating background colors
            holder.itemView.setBackgroundColor(
                if (position % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF5F5F5.toInt()
            )

            // Set status color based on value
            holder.statusText.setTextColor(
                when {
                    log.status.contains("Online", true) -> 0xFF00FF00.toInt()
                    log.status.contains("Offline", true) -> 0xFFFF0000.toInt()
                    log.status.contains("Locked", true) -> 0xFF00FF00.toInt()
                    log.status.contains("Unlocked", true) -> 0xFFFF0000.toInt()
                    log.status.contains("Secure", true) -> 0xFF00FF00.toInt()
                    log.status.contains("Unsecure", true) -> 0xFFFF0000.toInt()
                    else -> 0xFF800000.toInt()
                }
            )
        }

        override fun getItemCount() = logs.size
    }
}