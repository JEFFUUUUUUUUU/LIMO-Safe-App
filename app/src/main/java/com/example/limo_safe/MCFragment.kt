package com.example.limo_safe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.limo_safe.Object.FlashlightControl
import com.example.limo_safe.Object.PersistentTimer
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.utils.DialogManager
import com.example.limo_safe.utils.MorseCodeHelper
import com.example.limo_safe.utils.TimerManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlin.concurrent.thread

class MCFragment : Fragment() {
    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var sessionManager: SessionManager
    private lateinit var persistentTimer: PersistentTimer
    private lateinit var flashlightControl: FlashlightControl
    private lateinit var dialogManager: DialogManager
    private lateinit var timerManager: TimerManager

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    private var currentCode = ""
    private var remainingTries = 3
    private val MORSE_COOLDOWN: Long = 30000
    private val GENERATE_COOLDOWN: Long = 180000
    private var lastMorsePlayTime: Long = 0
    private val PREFS_NAME = "MorseCodePrefs"
    private val KEY_REMAINING_TRIES = "remaining_tries"
    private val KEY_CURRENT_CODE = "current_code"
    private val KEY_LAST_MORSE_TIME = "last_morse_time"
    private val KEY_MORSE_STATE_ACTIVE = "morse_state_active"
    private val KEY_GENERATE_COOLDOWN_END = "generate_cooldown_end"
    private var generateCooldownEndTime: Long = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mc, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeManagers()
        initializeViews(view)
        setupClickListeners()
        checkCameraPermission()
        initializeFirebase()
        loadState()

        if (persistentTimer.isTimerRunning()) {
            currentCode = persistentTimer.getCurrentCode()
            updateGeneratedCodeText()
            startCountdown(persistentTimer.getRemainingTime(), false)
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f
        }
    }

    private fun initializeManagers() {
        sessionManager = SessionManager(requireActivity()) {
            Toast.makeText(requireContext(), "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            navigateToLogin()
        }
        flashlightControl = FlashlightControl(requireContext())
        persistentTimer = PersistentTimer(requireContext())
        dialogManager = DialogManager(requireContext())
        timerManager = TimerManager()
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
            handleGenerateCode()
        }

        checkMonitoringButton.setOnClickListener {
            navigateToMonitoring()
        }

        exitButton.setOnClickListener {
            dialogManager.showExitConfirmationDialog { requireActivity().finish() }
        }
    }

    private fun initializeFirebase() {
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference

        // Check if user is logged in and verified
        auth.currentUser?.let { user ->
            if (!user.isEmailVerified) {
                dialogManager.showErrorDialog(
                    "Verification Required",
                    "Please verify your email before using this feature."
                )
                return
            }

            // Get user data
            database.child("users").child(user.uid)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (!snapshot.exists()) {
                        dialogManager.showErrorDialog(
                            "Account Error",
                            "User account not found. Please log out and sign in again."
                        )
                        return@addOnSuccessListener
                    }

                    // Store user tag for reference
                    val userTag = snapshot.child("tag").getValue(String::class.java)
                    // Ready to use OTP features
                }
                .addOnFailureListener {
                    dialogManager.showErrorDialog(
                        "Connection Error",
                        "Failed to retrieve user data. Please check your connection."
                    )
                }
        }
    }

    private fun handleGenerateCode() {
        val currentTime = System.currentTimeMillis()
        if (currentTime < generateCooldownEndTime) {
            return
        }
    
        // Check if user is logged in and verified
        val user = auth.currentUser
        if (user == null || !user.isEmailVerified) {
            dialogManager.showErrorDialog(
                "Authentication Required",
                "Please verify your email to generate codes."
            )
            return
        }
    
        generateRandomCode { newCode ->
            if (newCode.isEmpty()) {
                dialogManager.showErrorDialog(
                    "Error",
                    "Could not generate code. Please try again."
                )
                return@generateRandomCode
            }
    
            remainingTries = 3
            currentCode = newCode
            lastMorsePlayTime = 0
            
            updateGeneratedCodeText()
            saveState()
            showMorseCodeDialog(newCode)
            startGenerateButtonCooldown()
    
            // Store OTP in user's data
            database.child("users").child(user.uid)
                .child("otp").setValue(mapOf(
                    "code" to newCode,
                    "timestamp" to currentTime,
                    "attempts" to 0
                ))
        }
    }

    private fun generateRandomCode(callback: (String) -> Unit) {
        // Get user tag asynchronously
        auth.currentUser?.let { user ->
            database.child("users").child(user.uid).child("tag").get()
                .addOnSuccessListener { snapshot ->
                    val userTag = snapshot.getValue(String::class.java)
                    if (userTag == null) {
                        callback("") // No tag found
                        return@addOnSuccessListener
                    }
    
                    // Generate 6-digit OTP
                    val otp = (100000..999999).random().toString()
    
                    // Return tag + OTP (7 characters total)
                    callback(userTag + otp)
                }
                .addOnFailureListener {
                    callback("") // Handle failure
                }
        } ?: callback("") // Handle case where user is null
    }

    private fun updateGeneratedCodeText() {
        generatedCodeText.text = if (currentCode.isNotEmpty()) {
            // Only show OTP part (last 6 digits)
            "Generated Code: ${currentCode.drop(1)}"
        } else {
            "No code generated"
        }
    }

    private fun showMorseCodeDialog(code: String) {
        if (remainingTries <= 0) {
            dialogManager.showMaxTriesDialog()
            return
        }

        val currentTime = System.currentTimeMillis()
        val remainingMorseCooldown = if (lastMorsePlayTime > 0) {
            val elapsed = currentTime - lastMorsePlayTime
            if (elapsed < MORSE_COOLDOWN) MORSE_COOLDOWN - elapsed else 0
        } else 0

        dialogManager.createMorseCodeDialog(
            code = code,
            remainingTries = remainingTries,
            remainingCooldown = remainingMorseCooldown
        ) { button, cooldownText ->
            handleMorseCodePlay(code, button, cooldownText)
        }.show()
    }

    private fun handleMorseCodePlay(code: String, button: Button, cooldownText: TextView) {
        if (remainingTries <= 0) {
            dialogManager.showMaxTriesDialog()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMorsePlayTime < MORSE_COOLDOWN) {
            return
        }

        // Decrement tries after validating
        remainingTries--
        // Update dialog display
        dialogManager.updateRemainingTries(remainingTries)
        
        lastMorsePlayTime = System.currentTimeMillis()
        saveState()

        timerManager.startMorseCooldown(MORSE_COOLDOWN, button, cooldownText) {
            // Cooldown complete callback
        }

        thread {
            playMorseCode(code)
        }
    }

    private fun saveState() {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REMAINING_TRIES, remainingTries)
            .putString(KEY_CURRENT_CODE, currentCode)
            .putLong(KEY_LAST_MORSE_TIME, lastMorsePlayTime)
            .putLong(KEY_GENERATE_COOLDOWN_END, generateCooldownEndTime)
            .putBoolean(KEY_MORSE_STATE_ACTIVE, currentCode.isNotEmpty() && remainingTries > 0)
            .apply()
    }

    private fun loadState() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        remainingTries = prefs.getInt(KEY_REMAINING_TRIES, 3)
        currentCode = prefs.getString(KEY_CURRENT_CODE, "") ?: ""
        lastMorsePlayTime = prefs.getLong(KEY_LAST_MORSE_TIME, 0)
        generateCooldownEndTime = prefs.getLong(KEY_GENERATE_COOLDOWN_END, 0)

        val currentTime = System.currentTimeMillis()
        if (generateCooldownEndTime > currentTime) {
            startGenerateButtonCooldown(generateCooldownEndTime - currentTime)
        }

        if (prefs.getBoolean(KEY_MORSE_STATE_ACTIVE, false) && currentCode.isNotEmpty()) {
            updateGeneratedCodeText()
            val remainingMorseCooldown = if (lastMorsePlayTime > 0) {
                val elapsed = currentTime - lastMorsePlayTime
                if (elapsed < MORSE_COOLDOWN) MORSE_COOLDOWN - elapsed else 0
            } else 0
            showMorseCodeDialog(currentCode)
        }
    }

    private fun startGenerateButtonCooldown(duration: Long = GENERATE_COOLDOWN) {
        generateCooldownEndTime = System.currentTimeMillis() + duration
        timerManager.startGenerateButtonCooldown(
            duration,
            generateCodeButton
        ) { generateCooldownEndTime = 0 }
    }

    private fun startCountdown(duration: Long, isNewCountdown: Boolean) {
    if (isNewCountdown) {
        persistentTimer.startTimer(duration, currentCode)
    }
    
    timerManager.startGenerateButtonCooldown(duration, generateCodeButton) {
        persistentTimer.stopTimer()
        
        // Generate new code asynchronously
        generateRandomCode { newCode ->
            if (newCode.isNotEmpty()) {
                currentCode = newCode
                updateGeneratedCodeText()
            } else {
                // Handle error if newCode is empty
                dialogManager.showErrorDialog("Error", "Could not generate new code.")
            }
        }
    }
}

    private fun playMorseCode(code: String) {
        val morsePulses = MorseCodeHelper.convertToMorsePulseSequence(code)
        MorseCodeHelper.playMorsePulseSequence(requireContext(), morsePulses)
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "Code transmission complete", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToLogin() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, LoginFragment.newInstance())
            .commit()
    }

    private fun navigateToMonitoring() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MonitoringFragment.newInstance())
            .addToBackStack(null)
            .commit()
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
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        requireContext(),
                        "Camera permission is required for flashlight functionality",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerManager.cancelTimers()
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
