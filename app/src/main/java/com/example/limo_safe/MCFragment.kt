package com.example.limo_safe

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.LinearLayout
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.Object.PersistentTimer
import kotlin.concurrent.thread

class MCFragment : Fragment() {
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var sessionManager: SessionManager
    private lateinit var persistentTimer: PersistentTimer
    private var countDownTimer: CountDownTimer? = null
    private var currentCode = ""
    private var remainingTries = 3
    private val MORSE_COOLDOWN: Long = 30000 // 30 seconds in milliseconds
    private val GENERATE_COOLDOWN: Long = 30000 // 30 seconds in milliseconds
    private var lastMorsePlayTime: Long = 0
    private val PREFS_NAME = "MorseCodePrefs"
    private val KEY_REMAINING_TRIES = "remaining_tries"
    private val KEY_CURRENT_CODE = "current_code"
    private val KEY_LAST_MORSE_TIME = "last_morse_time"
    private val KEY_MORSE_STATE_ACTIVE = "morse_state_active"

    private fun saveState() {
        val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_REMAINING_TRIES, remainingTries)
            putString(KEY_CURRENT_CODE, currentCode)
            putLong(KEY_LAST_MORSE_TIME, lastMorsePlayTime)
            putBoolean(KEY_MORSE_STATE_ACTIVE, currentCode.isNotEmpty() && remainingTries > 0)
        }.apply()
    }

    private fun loadState() {
        val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        remainingTries = prefs.getInt(KEY_REMAINING_TRIES, 3)
        currentCode = prefs.getString(KEY_CURRENT_CODE, "") ?: ""
        lastMorsePlayTime = prefs.getLong(KEY_LAST_MORSE_TIME, 0)
        val isStateActive = prefs.getBoolean(KEY_MORSE_STATE_ACTIVE, false)
        
        if (isStateActive && currentCode.isNotEmpty()) {
            updateGeneratedCodeText()
            val currentTime = System.currentTimeMillis()
            val timeSinceLastMorse = currentTime - lastMorsePlayTime
            
            if (timeSinceLastMorse < MORSE_COOLDOWN) {
                // If we're still in cooldown period, show dialog with remaining cooldown
                showMorseCodeDialog(currentCode, MORSE_COOLDOWN - timeSinceLastMorse)
            } else {
                // If cooldown is over, show normal dialog
                showMorseCodeDialog(currentCode)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mc, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager(requireActivity()) {
            // Logout callback
            Toast.makeText(requireContext(), "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            navigateToLogin()
        }

        persistentTimer = PersistentTimer(requireContext())
        initializeViews(view)
        setupClickListeners()
        checkCameraPermission()
        loadState()  // Load saved state

        // Check if there's an ongoing timer from previous session
        if (persistentTimer.isTimerRunning()) {
            currentCode = persistentTimer.getCurrentCode()
            updateGeneratedCodeText()
            startCountdown(persistentTimer.getRemainingTime(), false)
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()  // Save state when fragment is paused
    }

    private fun initializeViews(view: View) {
        generateCodeButton = view.findViewById(R.id.generateCodeButton)
        checkMonitoringButton = view.findViewById(R.id.checkMonitoringButton)
        exitButton = view.findViewById(R.id.exitButton)
        generatedCodeText = view.findViewById(R.id.generatedCodeText)
        codeDisplayText = view.findViewById(R.id.codeDisplayText)
    }

    private fun setupClickListeners() {
        generateCodeButton.setOnClickListener {
            if (!generateCodeButton.isEnabled) return@setOnClickListener
            
            // Generate new code only when button is clicked
            val newCode = generateRandomCode()
            remainingTries = 3
            currentCode = newCode
            updateGeneratedCodeText()
            
            // Save the new state
            requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CURRENT_CODE, currentCode)
                .putInt(KEY_REMAINING_TRIES, remainingTries)
                .putBoolean(KEY_MORSE_STATE_ACTIVE, true)
                .apply()
            
            // Show the dialog with the new code
            showMorseCodeDialog(newCode)
            
            // Start the cooldown
            startGenerateButtonCooldown()
        }

        checkMonitoringButton.setOnClickListener {
            navigateToMonitoring()
        }

        exitButton.setOnClickListener {
            showExitConfirmationDialog()
        }
    }

    private fun startGenerateButtonCooldown() {
        generateCodeButton.isEnabled = false
        generateCodeButton.alpha = 0.5f
        
        object : CountDownTimer(GENERATE_COOLDOWN, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                generateCodeButton.text = "Generate Code (${seconds}s)"
            }

            override fun onFinish() {
                generateCodeButton.isEnabled = true
                generateCodeButton.alpha = 1.0f
                generateCodeButton.text = "Generate Code"
            }
        }.start()
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
        countDownTimer?.cancel()
        if (isNewCountdown) {
            persistentTimer.startTimer(duration, currentCode)
        }

        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = (millisUntilFinished / 1000) % 60
                generateCodeButton.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
            }

            override fun onFinish() {
                persistentTimer.stopTimer()
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

    private fun navigateToLogin() {
        val loginFragment = LoginFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commit()
    }

    private fun navigateToMonitoring() {
        val monitoringFragment = MonitoringFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, monitoringFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Exit Confirmation")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                requireActivity().finish()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun showMorseCodeDialog(code: String, remainingCooldown: Long = 0) {
        if (remainingTries <= 0) {
            AlertDialog.Builder(requireContext())
                .setTitle("Maximum Tries Reached")
                .setMessage("You have used all 3 tries. Please wait for the Generate Code button to be available again to get a new code.")
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    // Clear the saved state when tries are exhausted
                    requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_MORSE_STATE_ACTIVE, false)
                        .apply()
                }
                .setCancelable(false)
                .show()
            return
        }

        // Create a custom layout for the dialog
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        // Code display text
        val codeDisplayTextView = TextView(requireContext()).apply {
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
        val instructionText = TextView(requireContext()).apply {
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
        val triesText = TextView(requireContext()).apply {
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
        val cooldownText = TextView(requireContext()).apply {
            if (remainingCooldown > 0) {
                visibility = View.VISIBLE
                text = "Please wait ${remainingCooldown / 1000} seconds before next try"
            } else {
                visibility = View.GONE
            }
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

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Play Morse Code")
            .setView(layout)
            .setCancelable(false)
            .create()

        // Create custom button layout
        val buttonLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(50, 20, 50, 20)
        }

        val playButton = Button(requireContext()).apply {
            text = "Play Morse Code"
            isEnabled = remainingCooldown <= 0
            alpha = if (remainingCooldown <= 0) 1.0f else 0.5f
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

            playMorseCode(code, dialog)
            remainingTries--
            lastMorsePlayTime = System.currentTimeMillis()
            triesText.text = "Remaining tries: $remainingTries"
            saveState()

            // Only start cooldown timer if it's not the last try
            if (remainingTries > 0) {
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
                        playButton.isEnabled = true
                        playButton.alpha = 1.0f
                    }
                }.start()
            } else {
                // Disable play button on last try
                playButton.isEnabled = false
                playButton.alpha = 0.5f
                cooldownText.visibility = View.GONE
            }
        }

        // If there's remaining cooldown, start a timer to enable the button
        if (remainingCooldown > 0) {
            object : CountDownTimer(remainingCooldown, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = millisUntilFinished / 1000
                    cooldownText.text = "Please wait ${seconds} seconds before next try"
                }

                override fun onFinish() {
                    cooldownText.visibility = View.GONE
                    if (remainingTries > 0) {
                        playButton.isEnabled = true
                        playButton.alpha = 1.0f
                    }
                }
            }.start()
        }

        dialog.show()
    }

    private fun playMorseCode(code: String, dialog: AlertDialog? = null) {
        thread {
            for (char in code) {
                when (char) {
                    '.' -> {
                        flashlightOn()
                        Thread.sleep(70)
                        flashlightOff()
                        Thread.sleep(70)
                    }
                    '-' -> {
                        flashlightOn()
                        Thread.sleep(210)  // 3x dot duration for dash
                        flashlightOff()
                        Thread.sleep(70)
                    }
                    ' ' -> {
                        Thread.sleep(210)  // 3x dot duration for word space
                    }
                    else -> {
                        val morseChar = textToMorse(char.toString())
                        for (mc in morseChar) {
                            if (mc == '.') {
                                flashlightOn()
                                Thread.sleep(70)
                                flashlightOff()
                                Thread.sleep(70)
                            } else if (mc == '-') {
                                flashlightOn()
                                Thread.sleep(210)  // 3x dot duration for dash
                                flashlightOff()
                                Thread.sleep(70)
                            }
                        }
                        Thread.sleep(210)  // 3x dot duration between characters
                    }
                }
            }
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "Code transmission complete", Toast.LENGTH_SHORT).show()
                // Check if this was the last try
                if (remainingTries <= 1) {  // Check for 1 since we haven't decremented yet
                    // Dismiss the Morse code dialog first
                    dialog?.dismiss()
                    
                    AlertDialog.Builder(requireContext())
                        .setTitle("Maximum Tries Reached")
                        .setMessage("You have used all tries. Please generate a new code.")
                        .setPositiveButton("OK") { maxTriesDialog, _ ->
                            maxTriesDialog.dismiss()
                            // Clear the saved state
                            requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_MORSE_STATE_ACTIVE, false)
                                .apply()
                            // Re-enable generate button
                            generateCodeButton.isEnabled = true
                            generateCodeButton.alpha = 1.0f
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    private fun flashlightOn() {
        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, true)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun flashlightOff() {
        val cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                requireActivity(),
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
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Camera permission is required for flashlight functionality",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }
        }
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
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
    }
}
