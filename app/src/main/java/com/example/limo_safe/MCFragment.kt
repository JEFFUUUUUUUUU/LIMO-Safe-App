package com.example.limo_safe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.limo_safe.Object.PersistentTimer
import com.example.limo_safe.base.BaseFragment
import com.example.limo_safe.utils.DialogManager
import com.example.limo_safe.utils.MorseCodeHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlin.concurrent.thread

class MCFragment : BaseFragment() {
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var dialogManager: DialogManager
    private lateinit var database: DatabaseReference
    private lateinit var persistentTimer: PersistentTimer
    private var countDownTimer: CountDownTimer? = null
    private var morseTimer: CountDownTimer? = null
    private var currentCode = ""
    private var remainingTries = 3
    private val MORSE_COOLDOWN: Long = 15000 // 15 seconds in milliseconds
    private val GENERATE_COOLDOWN: Long = 60000 // 1 minute in milliseconds
    private var lastMorsePlayTime: Long = 0
    private val PREFS_NAME = "MorseCodePrefs"
    private val KEY_REMAINING_TRIES = "remaining_tries"
    private val KEY_CURRENT_CODE = "current_code"
    private val KEY_LAST_MORSE_TIME = "last_morse_time"
    private val KEY_MORSE_STATE_ACTIVE = "morse_state_active"
    private val KEY_GENERATE_COOLDOWN_END = "generate_cooldown_end"
    private val KEY_MORSE_COOLDOWN_END = "morse_cooldown_end"

    private var generateCooldownEndTime: Long = 0
    private var morseCooldownEndTime: Long = 0

    private lateinit var cooldownText: TextView
    private lateinit var dialog: AlertDialog

    private fun saveState() {
        val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_REMAINING_TRIES, remainingTries)
            putString(KEY_CURRENT_CODE, currentCode)
            putLong(KEY_LAST_MORSE_TIME, lastMorsePlayTime)
            putBoolean(KEY_MORSE_STATE_ACTIVE, currentCode.isNotEmpty() && remainingTries > 0)
            putLong(KEY_GENERATE_COOLDOWN_END, generateCooldownEndTime)
            putLong(KEY_MORSE_COOLDOWN_END, morseCooldownEndTime)
        }.apply()
    }

    private fun loadState() {
        val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        remainingTries = prefs.getInt(KEY_REMAINING_TRIES, 3)
        currentCode = prefs.getString(KEY_CURRENT_CODE, "") ?: ""
        lastMorsePlayTime = prefs.getLong(KEY_LAST_MORSE_TIME, 0)
        val isStateActive = prefs.getBoolean(KEY_MORSE_STATE_ACTIVE, false)
        generateCooldownEndTime = prefs.getLong(KEY_GENERATE_COOLDOWN_END, 0)

        // Check and restore generate button cooldown state
        val currentTime = System.currentTimeMillis()
        if (generateCooldownEndTime > currentTime) {
            startGenerateButtonCooldown(generateCooldownEndTime - currentTime)
        } else {
            generateCodeButton.isEnabled = true
            generateCodeButton.alpha = 1.0f
            generateCodeButton.text = "Generate Code"
            generateCooldownEndTime = 0
        }

        if (isStateActive && currentCode.isNotEmpty()) {
            updateGeneratedCodeText()

            // Calculate remaining morse cooldown time
            val currentTime = System.currentTimeMillis()
            val remainingMorseCooldown = if (lastMorsePlayTime > 0) {
                val elapsed = currentTime - lastMorsePlayTime
                if (elapsed < MORSE_COOLDOWN) MORSE_COOLDOWN - elapsed else 0
            } else 0

            showMorseCodeDialog(currentCode, remainingMorseCooldown)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Remove any existing fragments
        parentFragmentManager.fragments.forEach { fragment ->
            if (fragment != this && fragment.isVisible) {
                parentFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNow()
            }
        }

        val view = inflater.inflate(R.layout.fragment_mc, container, false)
        view.visibility = View.VISIBLE
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ensure this fragment is the only one visible
        parentFragmentManager.fragments.forEach { fragment ->
            if (fragment != this) {
                fragment.view?.visibility = View.GONE
            }
        }

        view.visibility = View.VISIBLE
        view.bringToFront()

        dialogManager = DialogManager(requireContext())
        persistentTimer = PersistentTimer(requireContext())
        initializeViews(view)
        setupClickListeners()
        checkCameraPermission()
        loadState()  // Load saved state

        // Make sure all views are visible
        view.findViewById<View>(R.id.topBar).visibility = View.VISIBLE
        view.findViewById<View>(R.id.bottomBar).visibility = View.VISIBLE
        generateCodeButton.visibility = View.VISIBLE
        checkMonitoringButton.visibility = View.VISIBLE
        exitButton.visibility = View.VISIBLE
        generatedCodeText.visibility = View.VISIBLE
        codeDisplayText.visibility = View.VISIBLE

        // Check if there's an ongoing timer from previous session
        if (persistentTimer.isTimerRunning()) {
            currentCode = persistentTimer.getCurrentCode()
            updateGeneratedCodeText()
            startCountdown(persistentTimer.getRemainingTime(), false)
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f
        }

        // If there was an active morse code session, restore it
        val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isStateActive = prefs.getBoolean(KEY_MORSE_STATE_ACTIVE, false)
        if (isStateActive && currentCode.isNotEmpty() && remainingTries > 0) {
            // Calculate remaining morse cooldown time if any
            val currentTime = System.currentTimeMillis()
            val remainingCooldown = if (lastMorsePlayTime > 0) {
                val elapsed = currentTime - lastMorsePlayTime
                if (elapsed < MORSE_COOLDOWN) MORSE_COOLDOWN - elapsed else 0
            } else 0

            // Show morse code dialog with saved state
            showMorseCodeDialog(currentCode, remainingCooldown)
        }

        database = FirebaseDatabase.getInstance().reference
    }

    override fun onPause() {
        super.onPause()
        saveState()
        // Dismiss dialog but don't reset state
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
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
            // Check if we're still in cooldown
            val currentTime = System.currentTimeMillis()
            if (currentTime < generateCooldownEndTime) {
                return@setOnClickListener
            }

            // Generate new code only when button is clicked
            val newCode = generateRandomCode()
            remainingTries = 3
            currentCode = newCode
            lastMorsePlayTime = 0 // Reset morse cooldown when generating new code
            updateGeneratedCodeText()

            // Save the new state
            requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CURRENT_CODE, currentCode)
                .putInt(KEY_REMAINING_TRIES, remainingTries)
                .putBoolean(KEY_MORSE_STATE_ACTIVE, true)
                .putLong(KEY_LAST_MORSE_TIME, 0) // Reset morse cooldown time
                .apply()

            // Show the dialog with the new code
            Log.d("MCFragment", "showMorseCodeDialog() called with code: $currentCode")
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

    private fun startGenerateButtonCooldown(duration: Long = GENERATE_COOLDOWN) {
        generateCodeButton.isEnabled = false
        generateCodeButton.alpha = 0.5f

        generateCooldownEndTime = System.currentTimeMillis() + duration

        object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = (millisUntilFinished / 1000) % 60
                generateCodeButton.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
            }

            override fun onFinish() {
                generateCodeButton.isEnabled = true
                generateCodeButton.alpha = 1.0f
                generateCodeButton.text = "Generate Code"
                generateCooldownEndTime = 0
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

    @SuppressLint("RestrictedApi")
    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val randomCode = (1..6).map { chars.random() }.joinToString("")
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return randomCode

        // First, get the user's tag from Firebase
        database.child("users").child(userId).child("tag").get()
            .addOnSuccessListener { tagSnapshot ->
                val userTag = tagSnapshot.value as? String ?: ""
                // Take only the first character if tag exists
                val tagChar = if (userTag.isNotEmpty()) userTag.first().toString() else ""
                val fullCode = tagChar + randomCode

                // Store the OTP data
                val otpData = mapOf(
                    "code" to fullCode,
                    "created_at" to ServerValue.TIMESTAMP,
                    "expires_at" to (System.currentTimeMillis() + 30000) // 30 seconds
                )

                // Prepare log entry using push() to create a unique key
                val logsRef = database.child("users").child(userId).child("logs").push()
                val logEntry = mapOf(
                    "timestamp" to ServerValue.TIMESTAMP,
                    "event" to "otp"
                )

                // Update both OTP and logs
                val updates = mapOf(
                    "/users/$userId/otp" to otpData,
                    "${logsRef.path}" to logEntry
                )

                database.updateChildren(updates)
                    .addOnSuccessListener {
                        // Update the UI with the full code
                        activity?.runOnUiThread {
                            currentCode = fullCode
                            updateGeneratedCodeText()
                        }

                        // Auto-delete after 30 seconds
                        Handler(Looper.getMainLooper()).postDelayed({
                            database.child("users").child(userId).child("otp").removeValue()
                        }, 45000)
                    }
            }

        // Return the random code initially, it will be updated when we get the tag
        return randomCode
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
            .setCustomAnimations(
                R.anim.slide_in_right,  // Enter animation
                R.anim.fade_in,         // Exit animation
                R.anim.fade_in,         // Pop enter animation
                R.anim.slide_in_right   // Pop exit animation
            )
            .replace(R.id.fragmentContainer, monitoringFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showExitConfirmationDialog() {
        val dialog = dialogManager.createCustomDialog(R.layout.dialog_exit_confirmation)
        dialog.show()

        // Get dialog buttons
        val yesButton = dialog.findViewById<Button>(R.id.yesButton)
        val noButton = dialog.findViewById<Button>(R.id.noButton)

        // Set click listeners
        yesButton?.setOnClickListener {
            dialog.dismiss()
            // Save state before logging out
            saveState()
            // Sign out from Firebase
            FirebaseAuth.getInstance().signOut()
            // Navigate to login
            navigateToLogin()
        }

        noButton?.setOnClickListener {
            dialog.dismiss()
        }

        // Session timeout handling is already managed by DialogManager
    }

    // Modify the showMorseCodeDialog method
    private fun showMorseCodeDialog(code: String, remainingCooldown: Long = 0) {
        // Dismiss any existing dialog first
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }

        // Create new dialog
        dialog = dialogManager.createMorseCodeDialog(
            code = code,
            remainingTries = remainingTries,
            remainingCooldown = remainingCooldown
        ) { playButton, cooldownText ->
            // Play button click handler
            sessionManager.userActivityDetected()

            // Calculate actual cooldown based on current time
            val currentTime = System.currentTimeMillis()
            val actualCooldown = if (lastMorsePlayTime > 0) {
                val elapsed = currentTime - lastMorsePlayTime
                if (elapsed < MORSE_COOLDOWN && remainingTries > 1) MORSE_COOLDOWN - elapsed else 0
            } else 0

            // Only proceed if cooldown is complete
            if (actualCooldown > 0) {
                cooldownText.text = "Please wait ${actualCooldown / 1000} seconds before next try"
                return@createMorseCodeDialog
            }

            // Process the morse code play
            if (remainingTries > 0) {
                playMorseCode(code)
                remainingTries--
                dialogManager.updateTriesText(remainingTries)
                saveState()

                if (remainingTries > 0) {
                    lastMorsePlayTime = currentTime
                    startMorseCooldown(MORSE_COOLDOWN)
                } else {
                    // Show max tries dialog
                    dialogManager.showMaxTriesDialog()
                }
            }
        }

        // Show the dialog
        dialog.show()
    }

    private fun startMorseCooldown(duration: Long) {
        morseTimer?.cancel()

        val currentDialog = dialog
        val playButton = currentDialog?.findViewById<Button>(R.id.playButton)
        val cooldownText = currentDialog?.findViewById<TextView>(R.id.cooldownText)

        playButton?.isEnabled = false
        playButton?.alpha = 0.5f

        morseTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                activity?.runOnUiThread {
                    cooldownText?.text = "Please wait $seconds seconds before next try"
                }
            }

            override fun onFinish() {
                activity?.runOnUiThread {
                    cooldownText?.text = "Ready to play"
                    playButton?.isEnabled = true
                    playButton?.alpha = 1.0f
                    lastMorsePlayTime = 0
                }
            }
        }.start()
    }

    private fun playMorseCode(code: String) {
        val transmitCode = codeDisplayText.text.toString() // Contains tag+code
        val pulses = MorseCodeHelper.convertToMorsePulseSequence(transmitCode)
        MorseCodeHelper.playMorsePulseSequence(requireContext(), pulses)
    }

    private fun flashlightOn() = Unit
    private fun flashlightOff() = Unit
    private fun textToMorse(text: String) = ""

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
        cleanup()
        // Remove endSession call as it's no longer needed
        // The SessionManager will handle cleanup through its lifecycle observer
    }

    // Add this to loadState() method
    override fun onResume() {
        super.onResume()
        sessionManager.userActivityDetected()

        // Get SharedPreferences properly
        val prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Recalculate and synchronize cooldown times
        val currentTime = System.currentTimeMillis()

        // Check morse cooldown
        morseCooldownEndTime = prefs.getLong(KEY_MORSE_COOLDOWN_END, 0)
        if (morseCooldownEndTime > currentTime && currentCode.isNotEmpty() && remainingTries > 0) {
            // If we have an active morse session with cooldown
            val remainingCooldown = morseCooldownEndTime - currentTime
            if (::dialog.isInitialized && dialog.isShowing) {
                // Update existing dialog
                val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
                cooldownText?.text = "Please wait ${remainingCooldown / 1000} seconds before next try"
            } else {
                // Show new dialog with remaining cooldown
                showMorseCodeDialog(currentCode, remainingCooldown)
            }
        }
    }

    // Create a single method to handle all cooldown timers
    private fun handleCooldowns() {
        val currentTime = System.currentTimeMillis()

        // Handle generate button cooldown
        if (generateCooldownEndTime > currentTime) {
            startGenerateButtonCooldown(generateCooldownEndTime - currentTime)
        }

        // Handle morse code cooldown if dialog is active
        if (currentCode.isNotEmpty() && remainingTries > 0 && lastMorsePlayTime > 0) {
            val elapsed = currentTime - lastMorsePlayTime
            if (elapsed < MORSE_COOLDOWN) {
                morseCooldownEndTime = lastMorsePlayTime + MORSE_COOLDOWN
                // Only start cooldown if dialog is showing
                if (::dialog.isInitialized && dialog.isShowing) {
                    startMorseCooldown(MORSE_COOLDOWN - elapsed)
                }
            }
        }
    }

    private fun cleanup() {
        morseTimer?.cancel()
        countDownTimer?.cancel()
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
    }
}