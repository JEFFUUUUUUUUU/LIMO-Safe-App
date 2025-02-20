package com.example.limo_safe

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
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
    private lateinit var titleText: TextView
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private var currentCode: String = generateRandomCode()
    private var countDownTimer: CountDownTimer? = null
    private val CAMERA_PERMISSION_REQUEST_CODE = 123
    private lateinit var sessionManager: SessionManager

    companion object {
        var timeRemaining: Long = 0
        var isTimerRunning = false
        var startTime: Long = 0
        const val COUNTDOWN_DURATION = 30000L
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

        // Check timer state immediately
        checkAndRestoreTimerState()

        generateCodeButton.setOnClickListener {
            currentCode = generateRandomCode()
            updateGeneratedCodeText()
            
            // Start the countdown timer immediately after generating code
            startTime = System.currentTimeMillis()
            startCountdown(COUNTDOWN_DURATION, true)

            val dialogBuilder = AlertDialog.Builder(this)
            dialogBuilder.setTitle("Please align your flashlight to the Sensor of Your LIMO-Safe")
                .setMessage("Generated Code: $currentCode")
                .setCancelable(false)
                .setPositiveButton("Play Morse Code") { dialog, _ ->
                    if (!hasCameraPermission()) {
                        requestCameraPermission()
                        return@setPositiveButton
                    }

                    val morseCodeSequence = convertToMorseCode(currentCode)
                    playMorseCodeSequence(this, morseCodeSequence)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    // Just dismiss the dialog, the countdown timer will continue
                    dialog.dismiss()
                }
                .setOnDismissListener {
                    // Ensure button stays disabled and countdown continues
                    generateCodeButton.isEnabled = false
                }

            val alert = dialogBuilder.create()
            alert.show()
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
        titleText = findViewById(R.id.titleText)
        generatedCodeText = findViewById(R.id.generatedCodeText)
        codeDisplayText = findViewById(R.id.codeDisplayText)
        generateCodeButton = findViewById(R.id.generateCodeButton)
        checkMonitoringButton = findViewById(R.id.checkMonitoringButton)
        exitButton = findViewById(R.id.exitButton)

        updateGeneratedCodeText()
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

    private fun updateGeneratedCodeText() {
        generatedCodeText.text = "Generated Code:"
        codeDisplayText.text = currentCode
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
        try {
            toggleFlashlight(this, false)
        } catch (e: Exception) {
            e.printStackTrace()
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
}
