package com.example.limo_safe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var logsAdapter: LogsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)
        logsRecyclerView = view.findViewById(R.id.logsRecyclerView)
        setupRecyclerView()
        return view
    }

    private fun setupRecyclerView() {
        // Sample data with more entries
        val logs = listOf(
            LogEntry("Device 1", Date(), "Online"),
            LogEntry("Device 2", Date(), "Offline"),
            LogEntry("Device 3", Date(), "Online"),
            LogEntry("Device 1", Date(System.currentTimeMillis() - 3600000), "Offline"), // 1 hour ago
            LogEntry("Device 2", Date(System.currentTimeMillis() - 7200000), "Online"),  // 2 hours ago
            LogEntry("Device 3", Date(System.currentTimeMillis() - 10800000), "Offline"), // 3 hours ago
            LogEntry("Device 1", Date(System.currentTimeMillis() - 14400000), "Online"), // 4 hours ago
            LogEntry("Device 2", Date(System.currentTimeMillis() - 18000000), "Offline"), // 5 hours ago
            LogEntry("Device 3", Date(System.currentTimeMillis() - 21600000), "Online")  // 6 hours ago
        )

        logsAdapter = LogsAdapter(logs)
        logsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logsAdapter
        }
    }

    companion object {
        fun newInstance(): LogsFragment {
            return LogsFragment()
        }
    }
}

data class LogEntry(
    val deviceName: String,
    val timestamp: Date,
    val status: String
)

class LogsAdapter(private val logs: List<LogEntry>) :
    RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceNameText: TextView = itemView.findViewById(R.id.deviceNameText)
        val dateText: TextView = itemView.findViewById(R.id.dateText)
        val timeText: TextView = itemView.findViewById(R.id.timeText)
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

        // Set text and colors
        holder.deviceNameText.apply {
            text = log.deviceName
            setTextColor(0xFF000000.toInt()) // Black text
        }

        holder.dateText.apply {
            text = dateFormat.format(log.timestamp)
            setTextColor(0xFF000000.toInt())
        }

        holder.timeText.apply {
            text = timeFormat.format(log.timestamp)
            setTextColor(0xFF000000.toInt())
        }

        holder.statusText.apply {
            text = log.status
            setTextColor(if (log.status == "Online") 0xFF00FF00.toInt() else 0xFFFF0000.toInt())
        }

        // Set alternating background colors for rows
        holder.itemView.setBackgroundColor(
            if (position % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF5F5F5.toInt()
        )
    }
}