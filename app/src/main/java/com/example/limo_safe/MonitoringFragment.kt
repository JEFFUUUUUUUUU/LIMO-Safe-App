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
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.*

class MonitoringFragment : Fragment() {
    private lateinit var backButton: Button
    private lateinit var monitoringTable: TableLayout
    private lateinit var sessionManager: SessionManager
    private lateinit var tabLayout: TabLayout
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
        setupTabLayout()
        loadSafeMonitoringData() // Load initial data
        continueCountdownIfRunning()
    }

    private fun initializeViews(view: View) {
        backButton = view.findViewById(R.id.backButton)
        monitoringTable = view.findViewById(R.id.monitoringTable)
        tabLayout = view.findViewById(R.id.tabLayout)

        backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupTabLayout() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        monitoringTable.removeAllViews()
                        loadSafeMonitoringData()
                    }
                    1 -> {
                        monitoringTable.removeAllViews()
                        loadDeviceMonitoringData()
                    }
                }
                // Apply fade-in animation to the table
                monitoringTable.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(context, R.anim.fade_in)
                )
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadSafeMonitoringData() {
        // Add table header
        addTableRow("Date", "Time", "Status", true, isSafeMonitoring = true)
        
        // Add sample data for Safe Monitoring (reduced to 3 rows)
        val currentTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        for (i in 0..2) {
            calendar.timeInMillis = currentTime - (i * 3600000)
            val date = dateFormat.format(calendar.time)
            val time = timeFormat.format(calendar.time)
            val status = when (i % 3) {
                0 -> "Active"
                1 -> "Warning"
                else -> "Inactive"
            }
            addTableRow(date, time, status, isSafeMonitoring = true)
        }
    }

    private fun loadDeviceMonitoringData() {
        // Add table header for device monitoring
        addTableRow("Device Name", "Status", "Registered Acc", true, isSafeMonitoring = false)
        
        // Sample device data (3 rows)
        val devices = listOf(
            Triple("LIMO Safe Device 1", "Online", "john.doe@email.com"),
            Triple("LIMO Safe Device 2", "Low Battery", "jane.smith@email.com"),
            Triple("LIMO Safe Device 3", "Offline", "admin@limosafe.com")
        )
        
        devices.forEach { (deviceName, status, account) ->
            addTableRow(deviceName, status, account, isSafeMonitoring = false)
        }
    }

    private fun addTableRow(col1: String, col2: String, col3: String, isHeader: Boolean = false, isSafeMonitoring: Boolean) {
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
                isStatus && isSafeMonitoring -> getStatusColor(text)
                isStatus && !isSafeMonitoring -> getDeviceStatusColor(text)
                else -> Color.BLACK
            })
            if (isHeader) {
                setTypeface(null, Typeface.BOLD)
            }
            setPadding(4, 4, 4, 4)
        }

        tableRow.addView(createTextView(col1))
        tableRow.addView(createTextView(col2, isStatus = true))
        tableRow.addView(createTextView(col3))

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

    private fun getDeviceStatusColor(status: String): Int {
        return when (status.toLowerCase(Locale.getDefault())) {
            "online" -> Color.parseColor("#4CAF50")  // Green
            "low battery" -> Color.parseColor("#FFA500")  // Orange
            "offline" -> Color.parseColor("#F44336")  // Red
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
