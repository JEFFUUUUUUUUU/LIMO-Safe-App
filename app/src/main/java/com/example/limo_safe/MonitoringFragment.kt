package com.example.limo_safe

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.limo_safe.Object.SessionManager
import java.text.SimpleDateFormat
import java.util.*

class MonitoringFragment : Fragment() {
    private lateinit var backButton: Button
    private lateinit var monitoringTable: TableLayout
    private lateinit var lastUpdateText: TextView
    private lateinit var sessionManager: SessionManager
    private var countDownTimer: CountDownTimer? = null
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_monitoring, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager(requireActivity()) {
            Toast.makeText(requireContext(), "Logging out due to inactivity", Toast.LENGTH_LONG).show()
            navigateToLogin()
        }

        initializeViews(view)
        loadMonitoringData()
        updateLastUpdateTime()
        continueCountdownIfRunning()
    }

    private fun initializeViews(view: View) {
        backButton = view.findViewById(R.id.backButton)
        monitoringTable = view.findViewById(R.id.monitoringTable)
        lastUpdateText = view.findViewById(R.id.lastUpdateText)

        backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun loadMonitoringData() {
        // Add table header
        addTableRow("Date", "Time", "Status", true)
        
        // Add sample data - in real app, this would come from a database
        val currentTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        // Add some sample entries with different statuses
        for (i in 0..5) {
            calendar.timeInMillis = currentTime - (i * 3600000) // Subtract hours
            val date = dateFormat.format(calendar.time)
            val time = timeFormat.format(calendar.time)
            val status = when (i % 3) {
                0 -> "Active"
                1 -> "Warning"
                else -> "Inactive"
            }
            addTableRow(date, time, status)
        }
    }

    private fun updateLastUpdateTime() {
        val currentTime = System.currentTimeMillis()
        val formattedTime = timeFormat.format(Date(currentTime))
        lastUpdateText.text = "Last Updated: $formattedTime"
    }

    private fun addTableRow(date: String, time: String, status: String, isHeader: Boolean = false) {
        val tableRow = TableRow(requireContext()).apply {
            setPadding(8, 12, 8, 12)
            if (!isHeader) {
                setBackgroundResource(R.drawable.table_row_bg)
            }
        }

        fun createTextView(text: String, isStatus: Boolean = false) = TextView(requireContext()).apply {
            this.text = text
            gravity = Gravity.CENTER
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(when {
                isHeader -> Color.parseColor("#FFA500")
                isStatus -> getStatusColor(text)
                else -> Color.BLACK
            })
            if (isHeader || isStatus) {
                setTypeface(null, Typeface.BOLD)
            }
            setPadding(4, 4, 4, 4)
        }

        tableRow.addView(createTextView(date))
        tableRow.addView(createTextView(time))
        tableRow.addView(createTextView(status, true))

        monitoringTable.addView(tableRow)
        
        // Add a divider after each row except the header
        if (!isHeader) {
            addDivider()
        }
    }

    private fun addDivider() {
        val dividerRow = TableRow(requireContext())
        val dividerView = View(requireContext()).apply {
            layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, 1).apply {
                span = 3
            }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        dividerRow.addView(dividerView)
        monitoringTable.addView(dividerRow)
    }

    private fun getStatusColor(status: String): Int {
        return when (status.toLowerCase(Locale.getDefault())) {
            "active" -> Color.parseColor("#4CAF50")  // Green
            "warning" -> Color.parseColor("#FFA500")  // Orange
            "inactive" -> Color.parseColor("#F44336")  // Red
            else -> Color.BLACK
        }
    }

    private fun continueCountdownIfRunning() {
        if (TimerState.isTimerRunning && TimerState.timeRemaining > 0) {
            startCountdown(TimerState.timeRemaining)
        }
    }

    private fun startCountdown(duration: Long) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                TimerState.timeRemaining = millisUntilFinished
            }

            override fun onFinish() {
                TimerState.isTimerRunning = false
                TimerState.timeRemaining = 0
            }
        }.start()
    }

    private fun navigateToLogin() {
        val loginFragment = LoginFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        sessionManager.endSession()
    }

    override fun onResume() {
        super.onResume()
        sessionManager.userActivityDetected()
    }

    companion object {
        fun newInstance() = MonitoringFragment()
    }
}
