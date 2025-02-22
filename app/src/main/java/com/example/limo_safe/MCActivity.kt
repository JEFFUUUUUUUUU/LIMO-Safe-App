package com.example.limo_safe

import android.content.Context
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
import android.view.View
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

    companion object {
        var timeRemaining: Long = 0
        var isTimerRunning = false
        var startTime: Long = 0
        const val COUNTDOWN_DURATION = 120000L  // 2 minutes in milliseconds
        const val MORSE_COOLDOWN = 30000L // 30 seconds cooldown
        const val MAX_TRIES = 3
        private var remainingTries = MAX_TRIES
        private var lastMorsePlayTime = 0L
        const val DOT_DURATION: Long = 100 // Faster dot duration (was 200)
        const val DASH_DURATION: Long = DOT_DURATION * 2 // Faster dash duration (was 3x)
        const val WORD_SPACE_DURATION: Long = DOT_DURATION * 4 // Faster word space (was 7x)
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
        }

        generateCodeButton.setOnClickListener {
            currentCode = generateRandomCode()
            updateGeneratedCodeText()

            // Reset tries when generating new code
            remainingTries = MAX_TRIES

            // Start the countdown timer immediately after generating code
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
        wifiButton = findViewById(R.id.wifiButton)

        wifiButton.setOnClickListener {
            showWifiCredentialsDialog()
        }
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
        if (isNewCountdown) {
            sessionManager.setLastGenerateTime(System.currentTimeMillis())
        }
        
        isTimerRunning = true
        startTime = System.currentTimeMillis()
        timeRemaining = duration

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemaining = millisUntilFinished
                val minutes = millisUntilFinished / 60000
                val seconds = (millisUntilFinished % 60000) / 1000
                val timeStr = String.format("%d:%02d", minutes, seconds)
                updateButtonText(timeStr)
            }

            override fun onFinish() {
                isTimerRunning = false
                timeRemaining = 0
                updateButtonText("Generate Code")
                generateCodeButton.isEnabled = true
            }
        }.start()
    }

    private fun updateButtonText(text: String) {
        runOnUiThread {
            generateCodeButton.text = text
            generateCodeButton.isEnabled = text == "Generate Code"
        }
    }

    private fun showMorseCodeDialog(currentCode: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Please align your flashlight to the Sensor of Your LIMO-Safe")
            .setMessage("Generated Code: $currentCode\nTries Left: $remainingTries")
            .setCancelable(false)
            .setPositiveButton("Play Morse Code", null)  // Set to null initially
            .create()

        dialog.setOnShowListener {
            val playButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            playButton.isEnabled = System.currentTimeMillis() - lastMorsePlayTime >= MORSE_COOLDOWN

            playButton.setOnClickListener {
                if (System.currentTimeMillis() - lastMorsePlayTime >= MORSE_COOLDOWN) {
                    if (hasCameraPermission()) {
                        remainingTries--
                        val morseCode = convertToMorseCode(currentCode)
                        playMorseCodeSequence(this, morseCode)
                        lastMorsePlayTime = System.currentTimeMillis()
                        
                        // Update dialog message with remaining tries
                        dialog.setMessage("Generated Code: $currentCode\nTries Left: $remainingTries")
                        
                        // Disable button during cooldown
                        playButton.isEnabled = false
                        
                        // Start cooldown timer
                        object : CountDownTimer(MORSE_COOLDOWN, 1000) {
                            override fun onTick(millisUntilFinished: Long) {
                                val minutes = (millisUntilFinished / 1000) / 60
                                val seconds = (millisUntilFinished / 1000) % 60
                                val timeStr = String.format("%d:%02d", minutes, seconds)
                                playButton.text = "Wait $timeStr"
                            }
                            
                            override fun onFinish() {
                                if (remainingTries > 0) {
                                    playButton.isEnabled = true
                                    playButton.text = "Play Morse Code"
                                }
                            }
                        }.start()
                        
                        if (remainingTries == 0) {
                            dialog.dismiss()
                            showMaximumTriesReachedDialog()
                        }
                    } else {
                        requestCameraPermission()
                    }
                } else {
                    showCooldownMessage()
                }
            }
        }
        
        dialog.show()
    }

    private fun showCooldownMessage() {
        AlertDialog.Builder(this)
            .setTitle("Cooldown")
            .setMessage("Please wait for the cooldown to finish.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.userActivityDetected()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Camera permission required for Morse code", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generateRandomCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun getReadableMorseCode(input: String): String {
        return input.uppercase()
    }

    private fun convertToMorseCode(input: String): List<Long> {
        val morseCodeMap = mapOf(
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
            'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
            'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
            'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
            'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
            'Z' to "--..", '1' to ".----", '2' to "..---", '3' to "...--",
            '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
            '8' to "---..", '9' to "----.", '0' to "-----"
        )

        val unitTime = 70L // Base time unit in milliseconds

        return input.uppercase().flatMap { char ->
            val morse = morseCodeMap[char] ?: return@flatMap emptyList<Long>()
            val signalDurations = morse.map { signal ->
                if (signal == '.') unitTime else unitTime * 3
            } + listOf(unitTime) // Add pause after each letter
            signalDurations
        } + listOf(unitTime * 3) // Add extra pause at the end
    }

    private fun playMorseCodeSequence(context: Context, sequence: List<Long>) {
        thread {
            try {
                for (duration in sequence) {
                    if (duration > 200) {
                        toggleFlashlight(context, true)
                        Thread.sleep(duration)
                        toggleFlashlight(context, false)
                    } else {
                        Thread.sleep(duration) // Pause between signals
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(context, "Error playing Morse code: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleFlashlight(context: Context, turnOn: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cameraManager.setTorchMode(cameraId, turnOn)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(context, "Error accessing camera flash: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit LIMO Safe App")
            .setMessage("Are you sure you want to exit the LIMO Safe App?")
            .setPositiveButton("Yes") { _, _ ->
                finishAffinity() // This will close the entire app
            }
            .setNegativeButton("No", null)  // This will dismiss the dialog and stay on MC page
            .show()
    }

    private fun showMaximumTriesReachedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Maximum Tries Reached")
            .setMessage("You have used all available tries. Please wait the cooldown to generate code again.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                // Reset tries for next time
                remainingTries = MAX_TRIES
            }
            .show()
    }

    private fun updateGeneratedCodeText() {
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
                    transmitWifiCredentials(ssid, password)
                } else {
                    Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun transmitWifiCredentials(ssid: String, password: String) {
        // First, transmit SSID
        Toast.makeText(this, "Transmitting SSID...", Toast.LENGTH_SHORT).show()
        transmitMorseCode(ssid) {
            // After SSID is done, transmit password with shorter delay
            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(this, "Transmitting Password...", Toast.LENGTH_SHORT).show()
                transmitMorseCode(password) {
                    Toast.makeText(this, "WiFi credentials transmission complete", Toast.LENGTH_SHORT).show()
                }
            }, 500) // Shorter delay between SSID and password (was 1000)
        }
    }

    private fun transmitMorseCode(text: String, onComplete: () -> Unit) {
        val morseCode = textToMorse(text)
        var currentIndex = 0
        val handler = Handler(Looper.getMainLooper())

        fun transmitNextSymbol() {
            if (currentIndex < morseCode.length) {
                when (morseCode[currentIndex]) {
                    '.' -> {
                        flashlightOn()
                        handler.postDelayed({
                            flashlightOff()
                            handler.postDelayed({
                                currentIndex++
                                transmitNextSymbol()
                            }, DOT_DURATION)
                        }, DOT_DURATION)
                    }
                    '-' -> {
                        flashlightOn()
                        handler.postDelayed({
                            flashlightOff()
                            handler.postDelayed({
                                currentIndex++
                                transmitNextSymbol()
                            }, DASH_DURATION)
                        }, DASH_DURATION)
                    }
                    ' ' -> {
                        handler.postDelayed({
                            currentIndex++
                            transmitNextSymbol()
                        }, WORD_SPACE_DURATION)
                    }
                }
            } else {
                onComplete()
            }
        }

        transmitNextSymbol()
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

    private fun flashlightOn() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cameraManager.setTorchMode(cameraId, true)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Error accessing camera flash: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun flashlightOff() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cameraManager.setTorchMode(cameraId, false)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Error accessing camera flash: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        countDownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.endSession()
        try {
            toggleFlashlight(this, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
