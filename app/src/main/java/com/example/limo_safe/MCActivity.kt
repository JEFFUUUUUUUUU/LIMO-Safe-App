package com.example.limo_safe

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.view.View
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.LinearLayout
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.MonActivity
import kotlin.concurrent.thread

class MCActivity : AppCompatActivity() {
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var sessionManager: SessionManager
    private var countDownTimer: CountDownTimer? = null
    private var startTime: Long = 0
    private var currentCode = ""
    private var remainingTries = 3
    private val MORSE_COOLDOWN: Long = 30000 // 30 seconds in milliseconds
    private var lastMorsePlayTime: Long = 0

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_mc)

        sessionManager = SessionManager(this) {
            // Logout callback
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        initializeViews()
        setupClickListeners()
        checkCameraPermission()

        // Check if there's an ongoing timer
        if (TimerState.isTimerRunning && TimerState.timeRemaining > 0) {
            startCountdown(TimerState.timeRemaining, false)
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f
        }
    }

    private fun initializeViews() {
        generateCodeButton = findViewById(R.id.generateCodeButton)
        checkMonitoringButton = findViewById(R.id.checkMonitoringButton)
        generatedCodeText = findViewById(R.id.generatedCodeText)
        codeDisplayText = findViewById(R.id.codeDisplayText)
    }

    private fun setupClickListeners() {
        generateCodeButton.setOnClickListener {
            if (!TimerState.isTimerRunning) {
                currentCode = generateRandomCode()
                updateGeneratedCodeText()
                startCountdown(120000, true) // 2 minutes in milliseconds
                generateCodeButton.isEnabled = false
                generateCodeButton.alpha = 0.5f
                remainingTries = 3
                showMorseCodeDialog(currentCode)
            }
        }

        checkMonitoringButton.setOnClickListener {
            val intent = Intent(this, MonActivity::class.java)
            startActivity(intent)
        }
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
        countDownTimer?.cancel()
        if (isNewCountdown) {
            TimerState.timeRemaining = duration
            TimerState.isTimerRunning = true
        }

        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                TimerState.timeRemaining = millisUntilFinished
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = (millisUntilFinished / 1000) % 60
                generateCodeButton.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
            }

            override fun onFinish() {
                TimerState.isTimerRunning = false
                TimerState.timeRemaining = 0
                currentCode = generateRandomCode()
                updateGeneratedCodeText()
                generateCodeButton.text = "Generate Code"
                generateCodeButton.isEnabled = true
                generateCodeButton.alpha = 1.0f
            }
        }.start()
    }

    private fun updateGeneratedCodeText() {
        generatedCodeText.text = "Generated Code: "
        codeDisplayText.text = currentCode
    }

    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Confirmation")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun showMorseCodeDialog(code: String) {
        if (remainingTries <= 0) {
            AlertDialog.Builder(this)
                .setTitle("Maximum Tries Reached")
                .setMessage("You have used all 3 tries. Please wait for the Generate Code button to be available again to get a new code.")
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
            return
        }

        // Create a custom layout for the dialog
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        // Code display text
        val codeDisplayTextView = TextView(this).apply {
            val fullText = "Code: $code"
            val spannableString = SpannableString(fullText)
            val startIndex = fullText.indexOf(code)
            spannableString.setSpan(
                StyleSpan(Typeface.BOLD),
                startIndex,
                startIndex + code.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text = spannableString
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        // Main instruction text
        val instructionText = TextView(this).apply {
            text = "Align first your phone flashlight to the Sensor of your LIMO-Safe while transmitting data. Thank you"
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        // Remaining tries text
        val triesText = TextView(this).apply {
            text = "Remaining tries: $remainingTries"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Cooldown text
        val cooldownText = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.holo_orange_dark))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
            }
        }

        layout.addView(codeDisplayTextView)
        layout.addView(instructionText)
        layout.addView(triesText)
        layout.addView(cooldownText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Play Morse Code")
            .setView(layout)
            .setCancelable(false)
            .create()

        // Create custom button layout
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(50, 20, 50, 20)
        }

        val playButton = Button(this).apply {
            text = "Play Morse Code"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buttonLayout.addView(playButton)
        layout.addView(buttonLayout)

        playButton.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastMorsePlayTime < MORSE_COOLDOWN) {
                val remainingCooldown = (MORSE_COOLDOWN - (currentTime - lastMorsePlayTime)) / 1000
                cooldownText.text = "Please wait ${remainingCooldown} seconds before next try"
                cooldownText.visibility = View.VISIBLE
                playButton.isEnabled = false
                playButton.alpha = 0.5f
                return@setOnClickListener
            }

            playMorseCode(code)
            remainingTries--
            lastMorsePlayTime = System.currentTimeMillis()
            triesText.text = "Remaining tries: $remainingTries"

            // Start cooldown timer
            object : CountDownTimer(MORSE_COOLDOWN, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = millisUntilFinished / 1000
                    cooldownText.text = "Please wait ${seconds} seconds before next try"
                    cooldownText.visibility = View.VISIBLE
                    playButton.isEnabled = false
                    playButton.alpha = 0.5f
                }

                override fun onFinish() {
                    cooldownText.visibility = View.GONE
                    if (remainingTries > 0) {
                        playButton.isEnabled = true
                        playButton.alpha = 1.0f
                    } else {
                        AlertDialog.Builder(this@MCActivity)
                            .setTitle("Maximum Tries Reached")
                            .setMessage("You have used all 3 tries. Please wait for the Generate Code button to be available again to get a new code.")
                            .setPositiveButton("OK") { dialogInner, _ ->
                                dialogInner.dismiss()
                                dialog.dismiss()
                            }
                            .setCancelable(false)
                            .show()
                    }
                }
            }.start()
        }

        dialog.show()
    }

    private fun playMorseCode(code: String) {
        thread {
            for (char in code) {
                when (char) {
                    '.' -> {
                        flashlightOn()
                        Thread.sleep(200)
                        flashlightOff()
                        Thread.sleep(200)
                    }
                    '-' -> {
                        flashlightOn()
                        Thread.sleep(600)
                        flashlightOff()
                        Thread.sleep(200)
                    }
                    ' ' -> {
                        Thread.sleep(600)
                    }
                    else -> {
                        val morseChar = textToMorse(char.toString())
                        for (mc in morseChar) {
                            if (mc == '.') {
                                flashlightOn()
                                Thread.sleep(200)
                                flashlightOff()
                                Thread.sleep(200)
                            } else if (mc == '-') {
                                flashlightOn()
                                Thread.sleep(600)
                                flashlightOff()
                                Thread.sleep(200)
                            }
                        }
                        Thread.sleep(600) // Space between characters
                    }
                }
            }
            runOnUiThread {
                Toast.makeText(this, "Code transmission complete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun flashlightOn() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, true)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun flashlightOff() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, false)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun textToMorse(text: String): String {
        val morseMap = mapOf(
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
            'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
            'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
            'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
            'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
            'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--",
            '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..",
            '9' to "----."
        )
        return text.uppercase().map { morseMap[it] ?: "" }.joinToString(" ")
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                } else {
                    Toast.makeText(
                        this,
                        "Camera permission is required for flashlight functionality",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
        }
    }

    override fun onBackPressed() {
        showExitConfirmationDialog()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.userActivityDetected()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.endSession()
    }
}
