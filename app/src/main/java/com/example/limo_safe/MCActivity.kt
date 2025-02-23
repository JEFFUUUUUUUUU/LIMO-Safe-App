package com.example.limo_safe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.utils.MorseCodeHelper

class MCActivity : AppCompatActivity() {
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private var currentCode: String = generateRandomCode()
    private var countDownTimer: CountDownTimer? = null
    private val CAMERA_PERMISSION_REQUEST_CODE = 123
    private lateinit var sessionManager: SessionManager

    companion object {
        var timeRemaining: Long = 0
        var isTimerRunning = false
        var startTime: Long = 0
        const val COUNTDOWN_DURATION = 120000L  // 2 minutes in milliseconds
        const val MORSE_COOLDOWN = 30000L // 30 seconds cooldown
        const val MAX_TRIES = 3
        private var remainingTries = MAX_TRIES
        private var lastMorsePlayTime = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_mc)

        // Initialize SessionManager with timeout callback
        sessionManager = SessionManager(this) {
            Toast.makeText(this, "Logging out due to inactivity", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        initializeViews()
        checkAndRestoreTimerState()

        generateCodeButton.setOnClickListener {
            currentCode = generateRandomCode()
            updateGeneratedCodeText()

            remainingTries = MAX_TRIES
            startTime = System.currentTimeMillis()
            startCountdown(COUNTDOWN_DURATION, true)

            showMorseCodeDialog(currentCode)
        }

        checkMonitoringButton.setOnClickListener {
            val intent = Intent(this, MonActivity::class.java)
            startActivity(intent)
        }

        exitButton.setOnClickListener {
            showExitConfirmationDialog()
        }
    }

    private fun initializeViews() {
        generateCodeButton = findViewById(R.id.generateCodeButton)
        checkMonitoringButton = findViewById(R.id.checkMonitoringButton)
        exitButton = findViewById(R.id.exitButton)
        generatedCodeText = findViewById(R.id.generatedCodeText)
        codeDisplayText = findViewById(R.id.codeDisplayText)

        checkAndRestoreTimerState()
    }

    private fun updateGeneratedCodeText() {
        codeDisplayText.text = currentCode
    }


    private fun checkAndRestoreTimerState() {
        if (isTimerRunning && timeRemaining > 0) {
            generateCodeButton.isEnabled = false
            startCountdown(timeRemaining, false)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.userActivityDetected()
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
        generateCodeButton.isEnabled = false
        countDownTimer?.cancel()

        if (isNewCountdown) {
            startTime = System.currentTimeMillis()
            isTimerRunning = true
        }

        timeRemaining = duration

        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemaining = millisUntilFinished
                updateButtonText(millisUntilFinished)
            }

            override fun onFinish() {
                isTimerRunning = false
                timeRemaining = 0
                generateCodeButton.isEnabled = true
                generateCodeButton.text = "Generate Code"
            }
        }.start()
    }

    private fun updateButtonText(millisUntilFinished: Long) {
        val secondsRemaining = millisUntilFinished / 1000
        generateCodeButton.text = "Wait: $secondsRemaining sec"
        generateCodeButton.isEnabled = false
    }

    private fun generateRandomCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showMorseCodeDialog(currentCode: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Please align your flashlight to the Sensor of Your LIMO-Safe")
            .setMessage("Generated Code: $currentCode\nTries Left: $remainingTries")
            .setCancelable(false)
            .create()

        val playMorseButton = Button(this).apply {
            text = "Play Morse Code"
            isEnabled = System.currentTimeMillis() - lastMorsePlayTime >= MORSE_COOLDOWN
        }

        playMorseButton.setOnClickListener {
            if (!hasCameraPermission()) {
                requestCameraPermission()
                return@setOnClickListener
            }

            if (remainingTries > 0) {
                remainingTries--
                lastMorsePlayTime = System.currentTimeMillis()

                val morseCodeSequence = MorseCodeHelper.convertToMorsePulseSequence(currentCode)
                MorseCodeHelper.playMorsePulseSequence(this@MCActivity, morseCodeSequence)

                dialog.setMessage("Generated Code: $currentCode\nTries Left: $remainingTries")

                playMorseButton.isEnabled = false

                object : CountDownTimer(MORSE_COOLDOWN, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        playMorseButton.text = "Wait ${millisUntilFinished / 1000}s"
                    }

                    override fun onFinish() {
                        if (remainingTries > 0) {
                            playMorseButton.isEnabled = true
                            playMorseButton.text = "Play Morse Code"
                        }
                    }
                }.start()

                if (remainingTries == 0) {
                    dialog.dismiss()
                    showCooldownMessage()
                }
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            addView(playMorseButton)
            addView(cancelButton)
        }

        dialog.setView(buttonLayout)
        dialog.show()
    }

    private fun showCooldownMessage() {
        AlertDialog.Builder(this)
            .setTitle("Maximum Tries Reached")
            .setMessage("You have used all 3 tries.\nPlease wait for the generate code button's cooldown to finish.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit LIMO Safe App")
            .setMessage("Are you sure you want to exit the LIMO Safe App?")
            .setPositiveButton("Yes") { _, _ -> finishAffinity() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    override fun onResume() {
        super.onResume()
        checkAndRestoreTimerState()
    }

    override fun onPause() {
        super.onPause()
        countDownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.endSession()
    }
}
