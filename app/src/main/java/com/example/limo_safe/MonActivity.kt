package com.example.limo_safe

import android.os.Bundle
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.os.CountDownTimer
import android.content.Intent
import com.example.limo_safe.Object.SessionManager
import android.widget.Toast

class MonActivity : AppCompatActivity() {
    private lateinit var backButton: Button
    private lateinit var monitoringTable: TableLayout
    private lateinit var sessionManager: SessionManager
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_monitoring)

        // Initialize SessionManager with timeout callback
        sessionManager = SessionManager(this) {
            // This will be called when session times out
            Toast.makeText(this, "Logging out due to inactivity", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        initializeViews()
        continueCountdownIfRunning()
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.backButton)
        monitoringTable = findViewById(R.id.monitoringTable)

        addTableRow("Date", "Time", "Status", true)
        addTableRow("Date", "Time", "Status", false)
        addTableRow("Date", "Time", "Status", false)
        addTableRow("Date", "Time", "Status", false)

        backButton.setOnClickListener {
            navigateBackToMC()
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

    private fun navigateBackToMC() {
        val intent = Intent(this, MCActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
        finish()
    }

    private fun addTableRow(date: String, time: String, status: String, isHeader: Boolean = false) {
        val tableRow = TableRow(this).apply {
            setPadding(8, 8, 8, 8)
            setBackgroundColor(if (isHeader) Color.parseColor("#FFA500") else Color.WHITE)
        }

        // Create TextViews with common properties
        fun createTextView(text: String) = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(if (isHeader) Color.WHITE else Color.BLACK)
            if (isHeader) {
                setTypeface(null, Typeface.BOLD)
            }
            setPadding(4, 4, 4, 4)
        }

        // Add TextViews to the row
        tableRow.addView(createTextView(date))
        tableRow.addView(createTextView(time))
        tableRow.addView(createTextView(status))

        monitoringTable.addView(tableRow)
    }

    override fun onBackPressed() {
        navigateBackToMC()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        sessionManager.endSession()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.userActivityDetected()
    }
}
