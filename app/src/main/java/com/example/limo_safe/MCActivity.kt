package com.example.limo_safe

import android.Manifest
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
import com.example.limo_safe.Object.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Data class representing a single Morse pulse (either a flash ON or an OFF gap).
data class MorsePulse(val flash: Boolean, val duration: Long)

class MCActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var generatedCodeText: TextView
    private lateinit var playMorseButton: Button
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
        const val UNIT_TIME = 70L // Base time unit in milliseconds.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_mc)

        // Initialize SessionManager with a timeout callback.
        sessionManager = SessionManager(this) {
            Toast.makeText(this, "Logging out due to inactivity", Toast.LENGTH_LONG).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        initializeViews()
        checkAndRestoreTimerState()

        playMorseButton.setOnClickListener {
            if (!hasCameraPermission()) {
                requestCameraPermission()
                return@setOnClickListener
            }

            if (playMorseButton.isEnabled) {
                // Generate a new random 8-character alphanumeric code.
                currentCode = generateRandomCode()
                updateGeneratedCodeText()

                // Convert the code into a pulse sequence with explicit gaps.
                val pulses = convertToMorsePulseSequence(currentCode)
                playMorsePulseSequence(this, pulses)

                startTime = System.currentTimeMillis()
                startCountdown(COUNTDOWN_DURATION, true)
            }
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
        playMorseButton = findViewById(R.id.playMorseButton)
        checkMonitoringButton = findViewById(R.id.checkMonitoringButton)
        exitButton = findViewById(R.id.exitButton)

        updateGeneratedCodeText()
    }

    private fun checkAndRestoreTimerState() {
        if (isTimerRunning && timeRemaining > 0) {
            playMorseButton.isEnabled = false
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
        generatedCodeText.text = "Generated Code: $currentCode"
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
        playMorseButton.isEnabled = false
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
                playMorseButton.isEnabled = true
                playMorseButton.text = "Play Morse Code"
            }
        }.start()
    }

    private fun updateButtonText(millisUntilFinished: Long) {
        val secondsRemaining = millisUntilFinished / 1000
        playMorseButton.text = "Wait: $secondsRemaining sec"
        playMorseButton.isEnabled = false
    }

    private fun generateRandomCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    /**
     * Converts the given input string into a sequence of Morse pulses.
     * For each letter:
     *   - A dot is transmitted as: Flash ON for 1 unit (70 ms) followed by an OFF gap (1 unit) if not the last signal.
     *   - A dash is transmitted as: Flash ON for 3 units (210 ms) followed by an OFF gap (1 unit) if not the last signal.
     * After the letter, an additional OFF period (2 units) is added so that the total gap between letters is 3 units (210 ms).
     * A space in the input creates a word gap of 7 units (490 ms).
     */
    private fun convertToMorsePulseSequence(input: String): List<MorsePulse> {
        val morseCodeMap = mapOf(
            'A' to ".-",    'B' to "-...", 'C' to "-.-.", 'D' to "-..",  'E' to ".",
            'F' to "..-.",  'G' to "--.",  'H' to "....", 'I' to "..",   'J' to ".---",
            'K' to "-.-",   'L' to ".-..", 'M' to "--",   'N' to "-.",   'O' to "---",
            'P' to ".--.",  'Q' to "--.-", 'R' to ".-.",  'S' to "...",  'T' to "-",
            'U' to "..-",   'V' to "...-", 'W' to ".--",  'X' to "-..-", 'Y' to "-.--",
            'Z' to "--..",  '1' to ".----", '2' to "..---", '3' to "...--",
            '4' to "....-",  '5' to ".....", '6' to "-....", '7' to "--...",
            '8' to "---..", '9' to "----.", '0' to "-----"
        )

        val pulses = mutableListOf<MorsePulse>()
        val unitTime = UNIT_TIME

        for (char in input.uppercase()) {
            if (char == ' ') {
                // Insert a word gap: OFF for 7 units (490 ms).
                pulses.add(MorsePulse(false, unitTime * 7))
            } else {
                val morse = morseCodeMap[char] ?: continue
                // For each Morse symbol (dot or dash) in the letter:
                for ((index, symbol) in morse.withIndex()) {
                    // Dot: ON for 1 unit; Dash: ON for 3 units.
                    val onDuration = if (symbol == '.') unitTime else unitTime * 3
                    pulses.add(MorsePulse(true, onDuration))
                    // If not the last symbol, add an intra-character gap: OFF for 1 unit.
                    if (index < morse.length - 1) {
                        pulses.add(MorsePulse(false, unitTime))
                    }
                }
                // After the letter, add an additional OFF gap of 2 units (total letter gap = 3 units).
                pulses.add(MorsePulse(false, unitTime * 5))
            }
        }
        return pulses
    }

    /**
     * Plays the generated Morse pulse sequence by toggling the flashlight.
     * For pulses with flash = true, the flashlight is turned on for the specified duration;
     * for OFF pulses, a delay is inserted.
     */
    private fun playMorsePulseSequence(context: Context, pulses: List<MorsePulse>) {
        CoroutineScope(Dispatchers.Main).launch {
            for (pulse in pulses) {
                if (pulse.flash) {
                    toggleFlashlight(context, true)
                    delay(pulse.duration)
                    toggleFlashlight(context, false)
                } else {
                    delay(pulse.duration)
                }
            }
        }
    }

    /**
     * Uses the CameraManager to toggle the device's flashlight (torch mode).
     */
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
                finishAffinity() // Closes the entire app.
            }
            .setNegativeButton("No", null)
            .show()
    }
}
