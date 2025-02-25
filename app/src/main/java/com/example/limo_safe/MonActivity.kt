package com.example.limo_safe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.limo_safe.Object.SessionManager

class MonActivity : AppCompatActivity() {
    private lateinit var monitoringTable: TableLayout
    private lateinit var backButton: Button
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_monitoring)

        sessionManager = SessionManager(this) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginFragment::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        initializeViews()
        setupClickListeners()
        createTableHeader()
        loadMonitoringData()
        applyAnimations()
    }

    private fun initializeViews() {
        monitoringTable = findViewById(R.id.monitoringTable)
        backButton = findViewById(R.id.backButton)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            navigateBack()
        }
    }

    private fun navigateBack() {
        val intent = Intent(this, MCFragment::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onBackPressed() {
        navigateBack()
    }

    private fun createTableHeader() {
        val headerRow = TableRow(this)
        headerRow.layoutParams = TableRow.LayoutParams(
            TableRow.LayoutParams.MATCH_PARENT,
            TableRow.LayoutParams.WRAP_CONTENT
        )
        headerRow.setBackgroundResource(R.drawable.table_header_bg)

        val headers = arrayOf("Time", "Code", "Status")
        headers.forEach { headerText ->
            TextView(this).apply {
                text = headerText
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }.also { headerRow.addView(it) }
        }

        monitoringTable.addView(headerRow)
    }

    private fun loadMonitoringData() {
        // Simulated monitoring data
        val monitoringData = listOf(
            Triple("10:30 AM", "ABC123", "Success"),
            Triple("10:15 AM", "XYZ789", "Failed"),
            Triple("10:00 AM", "DEF456", "Success")
        )

        monitoringData.forEachIndexed { index, (time, code, status) ->
            addTableRow(time, code, status, index)
        }
    }

    private fun addTableRow(time: String, code: String, status: String, index: Int) {
        val tableRow = TableRow(this)
        tableRow.layoutParams = TableRow.LayoutParams(
            TableRow.LayoutParams.MATCH_PARENT,
            TableRow.LayoutParams.WRAP_CONTENT
        )
        
        // Alternate row background
        tableRow.setBackgroundColor(
            if (index % 2 == 0) 
                Color.parseColor("#33FFFFFF") 
            else 
                Color.TRANSPARENT
        )

        // Create and add cells
        arrayOf(time, code, status).forEach { text ->
            TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                // Add special styling for status
                if (text == status) {
                    setTextColor(if (status == "Success") Color.GREEN else Color.RED)
                    typeface = Typeface.DEFAULT_BOLD
                }
            }.also { tableRow.addView(it) }
        }

        // Add row with animation
        monitoringTable.addView(tableRow)
        val animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        animation.startOffset = (index * 100).toLong() // Stagger the animations
        tableRow.startAnimation(animation)
    }

    private fun applyAnimations() {
        // Title animations
        titleText.alpha = 0f
        titleText.translationY = -50f
        titleText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1000)
            .start()

        // Subtitle animations
        subtitleText.alpha = 0f
        subtitleText.translationY = -30f
        subtitleText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(300)
            .start()

        // Table fade in
        monitoringTable.alpha = 0f
        monitoringTable.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(500)
            .start()

        // Back button animation
        backButton.alpha = 0f
        backButton.scaleX = 0.8f
        backButton.scaleY = 0.8f
        backButton.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(1000)
            .start()
    }
}
