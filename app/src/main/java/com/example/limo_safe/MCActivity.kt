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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.example.limo_safe.Object.SessionManager
import kotlin.concurrent.thread

class MCActivity : AppCompatActivity() {
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var wifiButton: ImageButton
    private var currentCode: String = generateRandomCode()
    private var countDownTimer: CountDownTimer? = null
    private val CAMERA_PERMISSION_REQUEST_CODE = 123
    private lateinit var sessionManager: SessionManager
    private var isWifiTransmitted: Boolean = false

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
            // This will be called when session times out
            Toast.makeText(this, "Logging out due to inactivity", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        // Initialize views and session manager
        initializeViews()

        // Check if we're still in cooldown period
        val lastGenerateTime = sessionManager.getLastGenerateTime()
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGenerateTime < COUNTDOWN_DURATION) {
            // Start the countdown timer with remaining time
            val remainingTime = COUNTDOWN_DURATION - (currentTime - lastGenerateTime)
            startCountdown(remainingTime, false)
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f
        }

        generateCodeButton.setOnClickListener {
            currentCode = generateRandomCode()
            updateGeneratedCodeText()

            // Reset tries when generating new code
            remainingTries = MAX_TRIES

            // Start the countdown timer immediately after generating code
            startCountdown(COUNTDOWN_DURATION, true)
            
            // Disable the button during cooldown
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f

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
        wifiButton = findViewById(R.id.wifiButton)

        // Reset WiFi icon state
        if (!isWifiTransmitted) {
            wifiButton.setImageResource(R.drawable.ic_wifi)
        }

        wifiButton.setOnClickListener {
            if (!isWifiTransmitted) {
                showWifiCredentialsDialog()
            } else {
                Toast.makeText(this, "WiFi credentials already transmitted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
        countDownTimer?.cancel()
        isTimerRunning = true

        if (isNewCountdown) {
            startTime = System.currentTimeMillis()
            sessionManager.setLastGenerateTime(startTime)
        }

        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemaining = millisUntilFinished
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = (millisUntilFinished / 1000) % 60
                generateCodeButton.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
            }

            override fun onFinish() {
                isTimerRunning = false
                currentCode = generateRandomCode()
                updateGeneratedCodeText()
                generateCodeButton.text = "Generate Code"
                // Re-enable the button after cooldown
                generateCodeButton.isEnabled = true
                generateCodeButton.alpha = 1.0f
            }
        }.start()
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

    private fun generateRandomCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..6)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun updateGeneratedCodeText() {
        generatedCodeText.text = "Generated Code: "
        codeDisplayText.text = currentCode
    }

    private fun showWifiCredentialsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        val ssidInput = EditText(this).apply {
            hint = "SSID"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        val passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        val descriptionText = TextView(this).apply {
            text = "Before pressing enter, please align your phone flashlight to the sensor of your LIMO-Safe"
            setTextColor(resources.getColor(android.R.color.darker_gray))
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(ssidInput)
        layout.addView(passwordInput)
        layout.addView(descriptionText)

        AlertDialog.Builder(this)
            .setTitle("Wi-Fi Credentials")
            .setView(layout)
            .setPositiveButton("Enter") { dialog, _ ->
                val ssid = ssidInput.text.toString()
                val password = passwordInput.text.toString()
                if (ssid.isNotEmpty() && password.isNotEmpty()) {
                    dialog.dismiss()
                    transmitWifiCredentials(ssid, password)
                } else {
                    Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun transmitWifiCredentials(ssid: String, password: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Transmitting Data")
            .setMessage("Please remain in the app and align your flashlight at the sensor while transmitting data.\nThank you.")
            .setCancelable(false)
            .create()

        progressDialog.show()

        // First, transmit SSID
        thread {
            runOnUiThread {
                Toast.makeText(this, "Transmitting SSID...", Toast.LENGTH_SHORT).show()
            }
            
            // Transmit SSID
            for (char in ssid) {
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

            Thread.sleep(1000) // Pause between SSID and password

            runOnUiThread {
                Toast.makeText(this, "Transmitting Password...", Toast.LENGTH_SHORT).show()
            }

            // Transmit password
            for (char in password) {
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
                progressDialog.dismiss()
                Toast.makeText(this, "WiFi credentials transmission complete", Toast.LENGTH_SHORT).show()
                // Change WiFi icon to green
                wifiButton.setImageResource(R.drawable.wifi_icon_green)
                isWifiTransmitted = true
            }
        }
    }

    private fun textToMorse(text: String): String {
        val morseMap = mapOf(
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..",
            'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
            'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
            'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
            'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
            'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
            'Y' to "-.--", 'Z' to "--..", '1' to ".----", '2' to "..---",
            '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....",
            '7' to "--...", '8' to "---..", '9' to "----.", '0' to "-----",
            ' ' to " "
        )

        return text.uppercase().map { char ->
            morseMap[char] ?: ""
        }.joinToString(" ")
    }

    override fun onResume() {
        super.onResume()
        // Restore WiFi icon state
        if (isWifiTransmitted) {
            wifiButton.setImageResource(R.drawable.wifi_icon_green)
        } else {
            wifiButton.setImageResource(R.drawable.ic_wifi)
        }
    }
}