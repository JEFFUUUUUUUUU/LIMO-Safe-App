package com.example.limo_safe

import android.Manifest
import android.annotation.SuppressLint
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.limo_safe.utils.MorseCodeHelper
import com.example.limo_safe.utils.DialogManager
import com.example.limo_safe.utils.BiometricManager
import com.example.limo_safe.utils.PasswordConfirmationDialog
import com.example.limo_safe.utils.TimerManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlin.concurrent.thread
import android.content.Context
import android.content.SharedPreferences
import android.view.View.VISIBLE
import androidx.core.view.GravityCompat

class MCFragment : Fragment() {
    private var morseCodeHelper: MorseCodeHelper? = null
    private var generateCooldownEndTime: Long = 0
    private var morseCooldownEndTime: Long = 0
    private var expirationEndTime: Long = 0
    private var lastMorsePlayTime: Long = 0
    private var currentCode = ""
    private var remainingTries = 3
    private val MORSE_COOLDOWN: Long = 15000 // 15 seconds
    private val GENERATE_COOLDOWN: Long = 60000 // 1 minute
    private val EXPIRATION_DURATION: Long = 50000 // 50 seconds

    // SharedPreferences keys
    private val PREFS_NAME = "MCFragmentPrefs"
    private val GENERATE_COOLDOWN_END_KEY = "generate_cooldown_end"
    private val CURRENT_CODE_KEY = "current_code"
    private val REMAINING_TRIES_KEY = "remaining_tries"
    private val MORSE_COOLDOWN_END_KEY = "morse_cooldown_end_time"
    private val MC_DIALOG_OPEN_KEY = "mc_dialog_open"
    private val MC_DIALOG_CODE_KEY = "mc_dialog_code"
    private val MC_DIALOG_TRIES_KEY = "mc_dialog_tries"
    private val MC_DIALOG_COOLDOWN_KEY = "mc_dialog_cooldown"
    private val EXPIRATION_END_TIME_KEY = "expiration_end_time"

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var timerManager: TimerManager

    private lateinit var generateCodeButton: Button
    private lateinit var checkMonitoringButton: Button
    private lateinit var exitButton: Button
    private lateinit var generatedCodeText: TextView
    private lateinit var codeDisplayText: TextView
    private lateinit var database: DatabaseReference
    private lateinit var cooldownText: TextView
    private lateinit var dialog: AlertDialog
    private lateinit var dialogManager: DialogManager
    private var countDownTimer: CountDownTimer? = null
    private var morseTimer: CountDownTimer? = null
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var menuIcon: ImageView
    private lateinit var logoutButton: Button
    private lateinit var biometricSetupButton: Button
    private lateinit var biometricManager: BiometricManager
    private lateinit var navHeaderSubtitle: TextView

    private var fromCodeClick = false

    // Add a class variable to store dialog title reference
    private var dialogTitleText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        morseCodeHelper = MorseCodeHelper(requireContext())
        // Initialize SharedPreferences
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Initialize TimerManager
        timerManager = TimerManager(sharedPreferences)

        // Handle back press
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (timerManager.isGenerateInCooldown() && remainingTries > 0) {
                    if (::dialog.isInitialized && dialog.isShowing) {
                        dialog.dismiss()
                    }
                    if (parentFragmentManager.backStackEntryCount > 0) {
                        parentFragmentManager.popBackStack()
                    } else {
                        // Show the custom exit confirmation dialog when back button is pressed
                        showExitConfirmationDialog()
                    }
                } else {
                    Toast.makeText(requireContext(),
                        "Please wait for cooldown or check remaining tries",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Initialize MorseCodeHelper with context
        morseCodeHelper = MorseCodeHelper(requireContext())

        // Initialize DialogManager
        dialogManager = DialogManager(requireContext())
        biometricManager = BiometricManager(requireContext())

        // Load saved state from SharedPreferences
        loadSavedState()

        // Create the view
        val view = inflater.inflate(R.layout.fragment_mc, container, false)

        // Initialize drawer layout and hamburger menu
        drawerLayout = view.findViewById(R.id.drawerLayout)
        menuIcon = view.findViewById(R.id.menuIcon)

        // Set up account info and logout button in the navigation drawer
        val navHeader = view.findViewById<View>(R.id.nav_header_root)
        if (navHeader != null) {
            navHeaderSubtitle = navHeader.findViewById(R.id.navHeaderSubtitle)
            logoutButton = navHeader.findViewById(R.id.logoutButton)
            biometricSetupButton = navHeader.findViewById(R.id.biometricSetupButton)

            // Set up user account info
            val currentUser = FirebaseAuth.getInstance().currentUser
            navHeaderSubtitle.text = currentUser?.email ?: "account."

            // Update biometric button text
            updateBiometricButtonText()

            // Set up biometric setup button
            biometricSetupButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                setupBiometricLogin(currentUser)
            }

            // Set up logout button
            logoutButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)

                // Show confirmation dialog using DialogManager for consistency
                dialogManager.showLogoutConfirmationDialog {
                    try {
                        // Sign out from Firebase
                        FirebaseAuth.getInstance().signOut()

                        // Navigate to login fragment directly
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && !mainActivity.isFinishing) {
                            // Create and show login fragment directly
                            val loginFragment = LoginFragment()
                            mainActivity.supportFragmentManager.beginTransaction()
                                .setCustomAnimations(
                                    android.R.anim.fade_in,
                                    android.R.anim.fade_out
                                )
                                .replace(R.id.fragmentContainer, loginFragment)
                                .commit()

                            // Update UI visibility
                            mainActivity.findViewById<View>(R.id.mainContent)?.visibility = View.GONE
                            mainActivity.findViewById<View>(R.id.fragmentContainer)?.visibility = View.VISIBLE
                        } else {
                            Log.e("MCFragment", "MainActivity is null or finishing")
                        }
                    } catch (e: Exception) {
                        Log.e("MCFragment", "Error during logout: ${e.message}")
                    }
                }
            }
        }

        // Set up hamburger menu click listener directly
        menuIcon.setOnClickListener {
            Log.d("MCFragment", "Menu icon clicked")
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        
        // Find and set up the Check Monitoring button in the navigation drawer
        Handler(Looper.getMainLooper()).post {
            try {
                // Get the direct child of the DrawerLayout that's not the main content
                for (i in 0 until drawerLayout.childCount) {
                    val child = drawerLayout.getChildAt(i)
                    if (child.id != R.id.mainContent) {
                        // Find the navigation header root
                        val navHeaderRoot = child.findViewById<View>(R.id.nav_header_root)
                        if (navHeaderRoot != null) {
                            // Find the Check Monitoring button
                            val checkMonitoringBtn = navHeaderRoot.findViewById<Button>(R.id.checkMonitoringButton)
                            if (checkMonitoringBtn != null) {
                                Log.d("MCFragment", "Found Check Monitoring button, setting up click listener")
                                checkMonitoringBtn.setOnClickListener {
                                    Log.d("MCFragment", "Check Monitoring button clicked")
                                    // Close drawer
                                    drawerLayout.closeDrawer(GravityCompat.START)
                                    // Navigate to monitoring
                                    navigateToMonitoring()
                                }
                            } else {
                                Log.e("MCFragment", "Check Monitoring button not found in nav header")
                            }
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("MCFragment", "Error setting up Check Monitoring button: ${e.message}")
                e.printStackTrace()
            }
        }

        // Add drawer listener to dim background when drawer is opened
        val mainContent = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.mainContent)
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Apply dim effect based on how far the drawer is open
                val dimAmount = slideOffset * 0.3f // Max 30% dim when fully open
                mainContent?.alpha = 1f - dimAmount // Reduce alpha for dimming
            }

            override fun onDrawerOpened(drawerView: View) {
                // Drawer fully opened
                mainContent?.alpha = 0.7f // 30% dim when fully open
            }

            override fun onDrawerClosed(drawerView: View) {
                // Drawer fully closed
                mainContent?.alpha = 1.0f // No dim when closed
            }

            override fun onDrawerStateChanged(newState: Int) {
                // Not needed for dimming effect
            }
        })

        return view
    }

    private fun setupViews(view: View) {
        try {
            // Initialize all UI elements
            generateCodeButton = view.findViewById(R.id.generateCodeButton)
            checkMonitoringButton = view.findViewById(R.id.checkMonitoringButton)
            exitButton = view.findViewById(R.id.exitButton)
            generatedCodeText = view.findViewById(R.id.generatedCodeText)
            codeDisplayText = view.findViewById(R.id.codeDisplayText)
            cooldownText = view.findViewById(R.id.cooldownText)
            drawerLayout = view.findViewById(R.id.drawerLayout)
            menuIcon = view.findViewById(R.id.menuIcon)
            logoutButton = view.findViewById(R.id.logoutButton)

            // Ensure all views are properly initialized
            if (generateCodeButton == null || checkMonitoringButton == null ||
                exitButton == null || generatedCodeText == null ||
                codeDisplayText == null || cooldownText == null ||
                drawerLayout == null || menuIcon == null ||
                logoutButton == null) {
                throw IllegalStateException("One or more views could not be found in the layout")
            }
        } catch (e: Exception) {
            Log.e("MCFragment", "Error initializing views: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            // Initialize views first
            setupViews(view)

            // Initialize Firebase
            database = FirebaseDatabase.getInstance().reference

            // Update UI based on current state
            updateGeneratedCodeText()

            // Show all UI elements with explicit visibility
            generateCodeButton.visibility = VISIBLE
            checkMonitoringButton.visibility = VISIBLE
            exitButton.visibility = VISIBLE
            generatedCodeText.visibility = VISIBLE
            codeDisplayText.visibility = VISIBLE

            // Setup click listeners after views are initialized
            setupClickListeners()

            // Setup navigation drawer
            setupNavigationDrawer()
            // Check camera permission
            checkCameraPermission()

            // Make the codeDisplayText clickable
            codeDisplayText.setOnClickListener {
                fromCodeClick = true
                // Check if there's a valid code that was generated by the user
                val hasValidCode = currentCode.isNotEmpty() && displayCode.isNotEmpty() && remainingTries > 0

                if (hasValidCode) {
                    // Only allow reopening if dialog is not already showing
                    if (!::dialog.isInitialized || !dialog.isShowing) {
                        // Get the EXACT current cooldown remaining time
                        val currentTime = System.currentTimeMillis()
                        val cooldownEndTime = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
                        val remainingCooldown = if (cooldownEndTime > currentTime) cooldownEndTime - currentTime else 0L

                        Log.d("MCFragment", "Reopening dialog with cooldown: $remainingCooldown ms, end time: $cooldownEndTime")

                        // Show the dialog with current code, tries, and cooldown
                        showMorseCodeDialog(currentCode, remainingCooldown)

                        // Start cooldown timer if needed
                        startMorseCooldownTimer(remainingCooldown)
                    }
                } else {
                    // Show toast message for no generated code
                    Toast.makeText(requireContext(), "No generated code yet.", Toast.LENGTH_SHORT).show()
                }
                fromCodeClick = false
            }
            // Add visual cue for clickability
            updateCodeClickableStyle()
        } catch (e: Exception) {
            Log.e("MCFragment", "Error initializing UI: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            // Save dialog state when app is minimized
            saveDialogState()
            Log.d("MCFragment", "onPause: Saved dialog state")

            // Cancel any running timers
            morseTimer?.cancel()
            countDownTimer?.cancel()

            // Always save the current remaining tries to ensure persistence across navigation
            saveRemainingTries(remainingTries)

            // Save dialog state if open (preserve even if dialog is showing)
            if (::dialog.isInitialized && dialog.isShowing && currentCode.isNotEmpty()) {
                // Calculate exact remaining cooldown and expiration time
                val now = System.currentTimeMillis()
                val remainingCooldown = if (morseCooldownEndTime > now) morseCooldownEndTime - now else 0L
                val remainingExpiration = if (expirationEndTime > now) expirationEndTime - now else EXPIRATION_DURATION

                Log.d("MCFragment", "onPause: Saving dialog state, cooldown: $remainingCooldown, expiration: $remainingExpiration, tries: $remainingTries")

                sharedPreferences.edit()
                    .putBoolean(MC_DIALOG_OPEN_KEY, true)
                    .putString(MC_DIALOG_CODE_KEY, currentCode)
                    .putInt(MC_DIALOG_TRIES_KEY, remainingTries)
                    .putLong(MC_DIALOG_COOLDOWN_KEY, remainingCooldown)
                    .putLong(EXPIRATION_END_TIME_KEY, expirationEndTime)
                    .apply()
            } else {
                // Even if dialog is not showing, we still need to save the current code and tries
                if (currentCode.isNotEmpty()) {
                    sharedPreferences.edit()
                        .putBoolean(MC_DIALOG_OPEN_KEY, false)
                        .putString(MC_DIALOG_CODE_KEY, currentCode)
                        .putInt(MC_DIALOG_TRIES_KEY, remainingTries)
                        .apply()
                } else {
                    sharedPreferences.edit().putBoolean(MC_DIALOG_OPEN_KEY, false).apply()
                }
            }
            // Important: Do NOT dismiss dialog here; we want to preserve its state
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            Log.d("MCFragment", "onResume: Restoring state")

            // FIRST: Always check if any code has expired and clear it completely
            // This will also show the expired code dialog if needed
            checkAndClearExpiredCode()

            // Load the expiration end time from SharedPreferences
            val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
            if (savedExpirationTime > 0) {
                // If there's a saved expiration time, restore it
                expirationEndTime = savedExpirationTime

                // Check if the timer has expired
                val now = System.currentTimeMillis()
                if (expirationEndTime <= now) {
                    // Timer has already expired and was handled by checkAndClearExpiredCode()
                    // Just make sure everything is cleared
                    clearAllCodeData()
                    return  // Don't proceed with dialog restoration if the timer has expired
                } else {
                    // Timer is still running, start the UI timer with the remaining time
                    startExpirationUITimer(expirationEndTime - now)
                }
            }

            // Restore Morse Code dialog if it was open before minimize and not currently showing
            val wasDialogOpen = sharedPreferences.getBoolean(MC_DIALOG_OPEN_KEY, false)
            if (wasDialogOpen && (!::dialog.isInitialized || !dialog.isShowing)) {
                val code = sharedPreferences.getString(MC_DIALOG_CODE_KEY, "") ?: ""
                val tries = sharedPreferences.getInt(MC_DIALOG_TRIES_KEY, 3)
                val cooldown = sharedPreferences.getLong(MC_DIALOG_COOLDOWN_KEY, 0)

                // Get the EXACT current cooldown remaining time
                val now = System.currentTimeMillis()
                val cooldownEnd = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
                val actualCooldown = if (cooldownEnd > now) cooldownEnd - now else cooldown

                Log.d("MCFragment", "onResume: Restoring dialog, code=$code, tries=$tries, cooldown=$actualCooldown, expirationTime=$expirationEndTime")

                // Double-check if the code has expired - if so, don't restore it
                if (expirationEndTime <= 0 || expirationEndTime <= now || code.isEmpty()) {
                    // Code has expired or is invalid, don't restore it and clear all data
                    clearAllCodeData()
                } else if (tries > 0) {
                    // Code is still valid, restore it
                    remainingTries = tries
                    currentCode = code

                    // Create the dialog with the current state
                    dialog = dialogManager.createMorseCodeDialog(
                        code,
                        tries,
                        actualCooldown,
                        { playButton, cooldownText ->
                            playMorseCode(transmitCode)
                            remainingTries--
                            sharedPreferences.edit().putInt(MC_DIALOG_TRIES_KEY, remainingTries).apply()
                            updateCodeClickableStyle()
                            updateGeneratedCodeText()
                            dialogManager.updateTriesText(remainingTries)
                            startMorseCooldown(MORSE_COOLDOWN)
                            if (remainingTries <= 0) {
                                dialogManager.showMaxTriesDialog()
                                currentCode = ""
                                saveCurrentCode("")
                                remainingTries = 3
                                saveRemainingTries(3)
                                // Reset expiration timer
                                resetExpirationTimer()
                                updateGeneratedCodeText()
                                updateCodeClickableStyle()
                            }
                        },
                        // Handle the expiration
                        {
                            // Show the Code Expired dialog
                            dialogManager.showCodeExpiredDialog(code) {
                                // Reset code and tries when user confirms
                                currentCode = ""
                                saveCurrentCode("")
                                remainingTries = 3
                                saveRemainingTries(3)
                                // Reset expiration timer
                                resetExpirationTimer()
                                updateGeneratedCodeText()
                                updateCodeClickableStyle()
                            }
                        },
                        // Use the actual remaining expiration time
                        expirationEndTime - now
                    )

                    // Start the cooldown timer if needed
                    if (actualCooldown > 0) {
                        startMorseCooldownTimer(actualCooldown)
                    }

                    dialog.show()
                }
            }

            // Reset UI state
            resetGenerateButton()

            // Resume cooldown timer if needed
            resumeCooldownIfNeeded()

            // Always update UI to ensure expired codes show as dashes
            if (::generatedCodeText.isInitialized && ::codeDisplayText.isInitialized) {
                // Check if the code has expired - if so, ensure it shows dashes
                val now = System.currentTimeMillis()
                val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
                if (savedExpirationTime > 0 && savedExpirationTime <= now) {
                    // Force display as dashes for expired code
                    displayCode = ""
                    currentCode = ""
                }
                updateGeneratedCodeText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupClickListeners() {
        try {
            generateCodeButton.setOnClickListener {
                try {
                    // Check camera permission first
                    if (!checkCameraPermission()) {
                        return@setOnClickListener
                    }

                    // Check if we're still in cooldown
                    val currentTime = System.currentTimeMillis()
                    if (currentTime < generateCooldownEndTime) {
                        // If we have an existing code and we're in cooldown, just reopen the dialog
                        if (currentCode.isNotEmpty()) {
                            showMorseCodeDialog(currentCode)
                        }
                        return@setOnClickListener
                    }

                    // Reset tries and show dialog for new code
                    generateNewMorseCodeAndShowDialog()
                    startGenerateButtonCooldown()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            checkMonitoringButton.setOnClickListener {
                try {
                    navigateToMonitoring()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Error navigating to monitoring: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            exitButton.setOnClickListener {
                try {
                    showExitConfirmationDialog()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Error showing exit dialog: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // Set up hamburger menu click listener
            menuIcon.setOnClickListener {
                try {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("MCFragment", "Error toggling drawer: ${e.message}")
                }
            }

            // Set up logout button in navigation drawer
            logoutButton.setOnClickListener {
                try {
                    // Close drawer
                    drawerLayout.closeDrawer(GravityCompat.START)

                    // Show confirmation dialog
                    val builder = AlertDialog.Builder(requireContext())
                    builder.setTitle("Logout Confirmation")
                        .setMessage("Are you sure you want to logout?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            dialog.dismiss()
                            FirebaseAuth.getInstance().signOut()

                            // Navigate back to login
                            val mainActivity = activity as? MainActivity
                            mainActivity?.clearBackStackAndNavigateToLogin()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .create()
                        .show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e("MCFragment", "Error handling logout: ${e.message}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startGenerateButtonCooldown(duration: Long = GENERATE_COOLDOWN) {
        timerManager.startGenerateButtonCooldown(
            duration,
            generateCodeButton
        ) {
            // This code runs when the timer finishes
            generateCooldownEndTime = 0
        }
        
        // Store the end time for reference
        generateCooldownEndTime = System.currentTimeMillis() + duration
    }

    private fun updateGeneratedCodeText() {
        generatedCodeText.text = "Generated Code: "

        // First check if the code has expired
        val now = System.currentTimeMillis()
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        val isExpired = savedExpirationTime > 0 && savedExpirationTime <= now

        // If code is expired, force display as dashes
        if (isExpired) {
            displayCode = ""
            currentCode = ""
            // Also clear in SharedPreferences
            sharedPreferences.edit()
                .putString(MC_DIALOG_CODE_KEY, "")
                .putString("display_code", "")
                .putString("transmit_code", "")
                .apply()
        }

        // Check if there's a valid code that was generated by the user
        val hasValidCode = currentCode.isNotEmpty() && displayCode.isNotEmpty() && remainingTries > 0 && !isExpired

        // Only show the actual code if it was generated, tries remain, and not expired
        codeDisplayText.text = if (hasValidCode) displayCode else "------"

        // Only save code to SharedPreferences if it's valid
        if (hasValidCode) {
            saveCurrentCode(displayCode)
            saveTransmitCode(transmitCode)
        }

        // Update the clickable style of the code
        updateCodeClickableStyle()

        Log.d("MCFragment", "Updated generated code text. isExpired=$isExpired, hasValidCode=$hasValidCode, display=${codeDisplayText.text}")
    }

    @SuppressLint("RestrictedApi")
    private var displayCode = "" // Code shown to user (without tag)
    private var transmitCode = "" // Full code with tag for Morse transmission
    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val randomCode = (1..6).map { chars.random() }.joinToString("")

        // Set the display code immediately
        displayCode = randomCode

        // Update UI with display code only (without tag)
        updateGeneratedCodeText()

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return randomCode

        // First, get the user's tag from Firebase
        database.child("users").child(userId).child("tag").get()
            .addOnSuccessListener { tagSnapshot ->
                val userTag = tagSnapshot.value as? String ?: ""
                // Take only the first character if tag exists
                val tagChar = if (userTag.isNotEmpty()) userTag.first().toString() else ""
                val fullCode = tagChar + randomCode
                transmitCode = fullCode

                // Store the OTP data
                val otpData = mapOf(
                    "code" to fullCode,
                    "created_at" to System.currentTimeMillis(),
                    "expires_at" to (System.currentTimeMillis() + 30000) // 30 seconds
                )

                // Prepare log entry using push() to create a unique key
                val logsRef = database.child("users").child(userId).child("logs").push()
                val logEntry = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "event" to "otp"
                )

                // Update both OTP and logs
                val updates = HashMap<String, Any>()
                updates["/users/$userId/otp"] = otpData
                updates["/users/$userId/logs/${logsRef.key}"] = logEntry

                database.updateChildren(updates)
                    .addOnSuccessListener {
                        // No need to update UI again since we've already displayed the code

                        // Auto-delete after 30 seconds
                        Handler(Looper.getMainLooper()).postDelayed({
                            database.child("users").child(userId).child("otp").removeValue()
                        }, 45000)
                    }
            }

        // Return the random code initially, it will be updated when we get the tag
        return randomCode
    }

    private fun navigateToMonitoring() {
        try {
            if (isAdded && activity != null && !requireActivity().isFinishing) {
                // First, ensure we save the current state before navigating
                // This is critical for preserving tries when navigating between pages
                saveRemainingTries(remainingTries)

                // Save dialog state if it's showing
                if (::dialog.isInitialized && dialog.isShowing && currentCode.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val remainingCooldown = if (morseCooldownEndTime > now) morseCooldownEndTime - now else 0L

                    sharedPreferences.edit()
                        .putBoolean(MC_DIALOG_OPEN_KEY, true)
                        .putString(MC_DIALOG_CODE_KEY, currentCode)
                        .putInt(MC_DIALOG_TRIES_KEY, remainingTries)
                        .putLong(MC_DIALOG_COOLDOWN_KEY, remainingCooldown)
                        .apply()

                    Log.d("MCFragment", "navigateToMonitoring: Saved dialog state with tries: $remainingTries")
                }

                // Now remove timers and dismiss dialog
                countDownTimer?.cancel()
                morseTimer?.cancel()
                if (::dialog.isInitialized && dialog.isShowing) {
                    dialog.dismiss()
                }

                // Create the monitoring fragment
                val monitoringFragment = MonitoringFragment()

                // Use the activity's supportFragmentManager to ensure proper navigation
                val mainActivity = activity as MainActivity
                mainActivity.supportFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                    )
                    .replace(R.id.fragmentContainer, monitoringFragment)
                    .addToBackStack(null)
                    .commit()

                // Log the navigation for debugging
                Log.d("MCFragment", "Navigated to monitoring fragment with remaining tries: $remainingTries")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExitConfirmationDialog() {
        // Use the DialogManager's showExitConfirmationDialog method
        // This ensures consistent behavior throughout the app
        dialogManager.showExitConfirmationDialog {
            // This code runs when the user clicks "Yes"
            // Exit the app completely
            requireActivity().finishAffinity()
            System.exit(0)
        }
        // When the user clicks "No", the dialog will automatically be dismissed
    }

    private fun showMorseCodeDialog(code: String, cooldown: Long = 0) {
        // First, check if the expiration timer has already expired
        if (checkExpirationTimer()) {
            return  // Don't show the dialog if the timer has expired
        }

        // Get the EXACT current cooldown remaining time
        val currentTimeMillis = System.currentTimeMillis()
        val cooldownEnd = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
        // Use the provided cooldown if it's specified, otherwise calculate from stored end time
        val remainingCooldown = if (cooldown > 0) cooldown else if (cooldownEnd > currentTimeMillis) cooldownEnd - currentTimeMillis else 0L

        // Get the remaining expiration time from SharedPreferences
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        val remainingExpirationTime = if (savedExpirationTime > currentTimeMillis) savedExpirationTime - currentTimeMillis else EXPIRATION_DURATION

        // Always load the saved remaining tries from SharedPreferences to ensure persistence
        // This is critical for preserving tries when navigating between pages
        val savedTries = sharedPreferences.getInt(REMAINING_TRIES_KEY, 3)

        // Only update remainingTries if we're not generating a new code
        // This prevents overwriting the value when we're just reopening the dialog
        if (!fromCodeClick && currentCode == code) {
            remainingTries = savedTries
            Log.d("MCFragment", "showMorseCodeDialog: Loaded saved tries: $remainingTries")
        }

        Log.d("MCFragment", "showMorseCodeDialog: remainingCooldown=$remainingCooldown, cooldownEnd=$cooldownEnd, now=$currentTimeMillis, tries=$remainingTries")

        // Only show max tries dialog if remainingTries is 0 or less and the user is trying to PLAY, not when generating a new code
        if (remainingTries <= 0 && code.isNotEmpty() && !fromCodeClick) {
            dialogManager.showMaxTriesDialog()
            currentCode = ""
            saveCurrentCode("")
            saveRemainingTries(3)
            updateGeneratedCodeText()
            return
        }

        // Cancel any existing timer before creating a new dialog
        morseTimer?.cancel()
        morseTimer = null

        // Create the dialog with the current state and handle expiration
        dialog = dialogManager.createMorseCodeDialog(
            code,
            remainingTries,
            remainingCooldown,
            { playButton, cooldownText ->
                playMorseCode(transmitCode)
                remainingTries--
                sharedPreferences.edit().putInt(MC_DIALOG_TRIES_KEY, remainingTries).apply()
                updateCodeClickableStyle()
                updateGeneratedCodeText()
                dialogManager.updateTriesText(remainingTries)
                startMorseCooldown(MORSE_COOLDOWN)
                if (remainingTries <= 0) {
                    dialogManager.showMaxTriesDialog()
                    currentCode = ""
                    saveCurrentCode("")
                    saveRemainingTries(3)
                    dialog.dismiss()
                }
            },
            // Handle the expiration after 50 seconds
            {
                // Show the Code Expired dialog
                dialogManager.showCodeExpiredDialog(code) {
                    // Reset code and tries when user confirms
                    currentCode = ""
                    saveCurrentCode("")
                    remainingTries = 3
                    saveRemainingTries(3)
                    // Reset expiration timer
                    resetExpirationTimer()
                    updateGeneratedCodeText()
                    updateCodeClickableStyle()
                }
            },
            remainingExpirationTime
        )

        // Now that the dialog is created, get the dialog title and store it
        dialogTitleText = dialog.findViewById<TextView>(R.id.dialogTitle)
        if (dialogTitleText == null) {
            Log.e("MCFragment", "Dialog title not found after dialog creation")
        } else {
            Log.d("MCFragment", "Dialog title found after dialog creation: $dialogTitleText")
        }

        dialog.show()

        // We need to wait a moment for the dialog to be fully displayed
        Handler(Looper.getMainLooper()).postDelayed({
            // Only start a new expiration timer if there isn't an active one
            val currentTime = System.currentTimeMillis()
            if (expirationEndTime <= currentTime) {
                // Start a new timer with the full duration
                startExpirationTimer(EXPIRATION_DURATION)
            } else {
                // Resume the existing timer with the remaining time
                startExpirationUITimer(expirationEndTime - currentTime)
            }

            startMorseCooldownTimer(remainingCooldown)
        }, 100) // Small delay to ensure dialog is fully displayed
    }

    private fun showMorseCodeDialogForce(code: String, cooldown: Long = 0) {
        // For new code generation, always start with NO cooldown
        val remainingCooldown = 0L

        // Get the current time
        val currentTimeMillis = System.currentTimeMillis()

        // Check if there's an existing expiration timer
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        val remainingExpirationTime = if (savedExpirationTime > currentTimeMillis) {
            // Use the remaining time from the existing timer
            savedExpirationTime - currentTimeMillis
        } else {
            // Start a new timer with the full duration
            EXPIRATION_DURATION
        }

        dialog = dialogManager.createMorseCodeDialog(
            code,
            remainingTries,
            remainingCooldown,
            { playButton, cooldownText ->
                playMorseCode(transmitCode)
                remainingTries--
                sharedPreferences.edit().putInt(MC_DIALOG_TRIES_KEY, remainingTries).apply()
                updateCodeClickableStyle()
                updateGeneratedCodeText()
                dialogManager.updateTriesText(remainingTries)
                startMorseCooldown(MORSE_COOLDOWN)
                // If this was the last try, show max tries dialog after playing
                if (remainingTries <= 0) {
                    dialogManager.showMaxTriesDialog()
                    currentCode = ""
                    saveCurrentCode("")
                    saveRemainingTries(3)
                    dialog.dismiss()
                }
            },
            // Handle the expiration after 50 seconds
            {
                // Show the Code Expired dialog
                dialogManager.showCodeExpiredDialog(code) {
                    // Reset code and tries when user confirms
                    currentCode = ""
                    saveCurrentCode("")
                    remainingTries = 3
                    saveRemainingTries(3)
                    // Reset expiration timer
                    resetExpirationTimer()
                    updateGeneratedCodeText()
                    updateCodeClickableStyle()
                }
            },
            remainingExpirationTime
        )

        val playButton = dialog.findViewById<Button>(R.id.playButton)
        val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
        // No cooldown for new code, so enable play immediately
        if (playButton != null && cooldownText != null) {
            playButton.isEnabled = true
            playButton.alpha = 1.0f
            cooldownText.text = "Ready to play"
            playButton.text = "PLAY MORSE CODE"
        }

        // Only start a new expiration timer if there isn't an active one
        val currentTime = System.currentTimeMillis()
        if (expirationEndTime <= currentTime) {
            // Start a new timer with the full duration
            startExpirationTimer(EXPIRATION_DURATION)
        }

        startMorseCooldownTimer(remainingCooldown)

        dialog.show()
    }

    private fun startMorseCooldown(duration: Long) {
        // Set the cooldown end time in memory for reference
        morseCooldownEndTime = System.currentTimeMillis() + duration
        
        val playButton = dialog.findViewById<Button>(R.id.playButton)
        val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
        
        if (playButton != null) {
            timerManager.startMorseCooldown(
                duration,
                playButton,
                cooldownText
            ) {
                // This code runs when the timer finishes
                lastMorsePlayTime = 0
                morseCooldownEndTime = 0
            }
        }
    }

    /**
     * Starts or restarts the Morse code cooldown timer with the specified duration
     * This is a separate method to avoid code duplication and ensure consistent timer behavior
     */
    private fun startMorseCooldownTimer(duration: Long) {
        val playButton = dialog.findViewById<Button>(R.id.playButton)
        val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
        
        if (playButton != null) {
            if (duration > 0) {
                timerManager.startMorseCooldown(
                    duration,
                    playButton,
                    cooldownText
                ) {
                    // This code runs when the timer finishes
                    lastMorsePlayTime = 0
                    morseCooldownEndTime = 0
                }
            } else {
                // Make sure UI is reset properly when no cooldown
                playButton.isEnabled = remainingTries > 0
                playButton.alpha = if (remainingTries > 0) 1.0f else 0.5f
                if (cooldownText != null) {
                    cooldownText.visibility = View.GONE
                }
                playButton.text = "PLAY MORSE CODE"
            }
        }
    }

    private fun playMorseCode(code: String) {
        thread {
            try {
                morseCodeHelper?.playMorseCode(code)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
            return false
        }
        return true
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

    private fun resetGenerateButton() {
        generateCodeButton.apply {
            isEnabled = true
            alpha = 1.0f
            text = "Generate Code"
        }
    }

    override fun onDestroyView() {
        try {
            // Cancel timers
            timerManager.cancelAllTimers()

            // Dismiss dialog
            if (::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }

            // Dismiss any active dialogs from DialogManager
            if (::dialogManager.isInitialized) {
                dialogManager.dismissActiveDialog()
            }

            // Clear state
            resetGenerateButton()
            lastMorsePlayTime = 0
            morseCooldownEndTime = 0
            generateCooldownEndTime = 0

            // Cleanup morse code helper
            morseCodeHelper?.cleanup()
            morseCodeHelper = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Ensure timers are nulled
            morseTimer = null
            countDownTimer = null
            super.onDestroyView()
        }
    }

    override fun onDestroy() {
        try {
            // Cancel timers
            timerManager.cancelAllTimers()

            // Dismiss dialog
            if (::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }

            // Clear state
            resetGenerateButton()
            lastMorsePlayTime = 0
            morseCooldownEndTime = 0
            generateCooldownEndTime = 0

            // Cleanup morse code helper
            morseCodeHelper?.cleanup()
            morseCodeHelper = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Ensure timers are nulled
            morseTimer = null
            countDownTimer = null
            super.onDestroy()
        }
    }

    private fun setupNavigationDrawer() {
        try {
            // Set up hamburger menu click listener
            menuIcon.setOnClickListener {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            }

            // Set up user account info
            val currentUser = FirebaseAuth.getInstance().currentUser
            navHeaderSubtitle.text = currentUser?.email ?: "account."

            // Update biometric button text
            updateBiometricButtonText()

            // Set up biometric setup button
            biometricSetupButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)
                setupBiometricLogin(currentUser)
            }

            // Set up logout button
            logoutButton.setOnClickListener {
                // Close drawer
                drawerLayout.closeDrawer(GravityCompat.START)

                // Show confirmation dialog using DialogManager for consistency
                dialogManager.showLogoutConfirmationDialog {
                    try {
                        // Sign out from Firebase
                        FirebaseAuth.getInstance().signOut()

                        // Navigate to login fragment directly
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && !mainActivity.isFinishing) {
                            // Create and show login fragment directly
                            val loginFragment = LoginFragment()
                            mainActivity.supportFragmentManager.beginTransaction()
                                .setCustomAnimations(
                                    android.R.anim.fade_in,
                                    android.R.anim.fade_out
                                )
                                .replace(R.id.fragmentContainer, loginFragment)
                                .commit()

                            // Update UI visibility
                            mainActivity.findViewById<View>(R.id.mainContent)?.visibility = View.GONE
                            mainActivity.findViewById<View>(R.id.fragmentContainer)?.visibility = View.VISIBLE
                        } else {
                            Log.e("MCFragment", "MainActivity is null or finishing")
                        }
                    } catch (e: Exception) {
                        Log.e("MCFragment", "Error during logout: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MCFragment", "Error setting up navigation drawer: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Save generate cooldown end time to SharedPreferences
     */
    private fun saveGenerateCooldownEndTime(endTime: Long) {
        sharedPreferences.edit().putLong(GENERATE_COOLDOWN_END_KEY, endTime).apply()
    }

    /**
     * Save current code to SharedPreferences
     */
    private fun saveCurrentCode(code: String) {
        sharedPreferences.edit().putString(CURRENT_CODE_KEY, code).apply()
    }

    private fun saveTransmitCode(code: String) {
        sharedPreferences.edit().putString("transmit_code_key", code).apply()
    }

    /**
     * Save remaining tries to SharedPreferences
     */
    private fun saveRemainingTries(tries: Int) {
        sharedPreferences.edit().putInt(REMAINING_TRIES_KEY, tries).apply()
    }

    /**
     * Load saved state from SharedPreferences
     */
    private fun loadSavedState() {
        // Load cooldown end time
        generateCooldownEndTime = sharedPreferences.getLong(GENERATE_COOLDOWN_END_KEY, 0)

        // Load transmit code (with tag)
        transmitCode = sharedPreferences.getString("transmit_code_key", "") ?: ""

        // Load current code
        currentCode = sharedPreferences.getString(CURRENT_CODE_KEY, "") ?: ""

        // Reset display code if returning to the fragment
        displayCode = if (currentCode.isNotEmpty()) currentCode else ""

        // Load remaining tries - this is critical for preserving tries when navigating between pages
        remainingTries = sharedPreferences.getInt(REMAINING_TRIES_KEY, 3)

        // Also load tries from dialog-specific key if it exists and has a valid value
        val dialogTries = sharedPreferences.getInt(MC_DIALOG_TRIES_KEY, -1)
        if (dialogTries > 0 && dialogTries <= 3) {
            // Use the dialog-specific tries value if it exists and is valid
            remainingTries = dialogTries
            // Synchronize the values
            saveRemainingTries(remainingTries)
        }

        Log.d("MCFragment", "loadSavedState: Loaded remaining tries: $remainingTries")

        // Load morse cooldown end time
        morseCooldownEndTime = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)

        // Check if we need to resume a cooldown
        val currentTime = System.currentTimeMillis()
        if (generateCooldownEndTime > currentTime) {
            val remainingTime = generateCooldownEndTime - currentTime

            // We'll start the timer when the button is visible
        }
    }

    /**
     * Resume cooldown timer if needed
     */
    private fun resumeCooldownIfNeeded() {
        // Update dialog title reference if dialog is showing
        if (::dialog.isInitialized && dialog.isShowing) {
            dialogTitleText = dialog.findViewById(R.id.dialogTitle)
            if (dialogTitleText == null) {
                Log.e("MCFragment", "Could not find dialog title when resuming timers")
            } else {
                Log.d("MCFragment", "Found dialog title when resuming timers: $dialogTitleText")
            }
        }

        // Use TimerManager to resume all timers if needed
        timerManager.resumeTimersIfNeeded(
            generateButton = if (::generateCodeButton.isInitialized) generateCodeButton else null,
            playButton = if (::dialog.isInitialized && dialog.isShowing) {
                dialog.findViewById<Button>(R.id.playButton)
            } else null,
            cooldownText = if (::dialog.isInitialized && dialog.isShowing) {
                dialog.findViewById<TextView>(R.id.cooldownText)
            } else null,
            titleText = dialogTitleText,
            onGenerateFinish = {
                generateCooldownEndTime = 0
            },
            onMorseFinish = {
                lastMorsePlayTime = 0
                morseCooldownEndTime = 0
            },
            onExpirationFinish = {
                // When timer finishes, show the expired dialog with the code
                if (::dialogManager.isInitialized) {
                    // Capture the code before dismissing the dialog
                    val expiredCode = currentCode
                    
                    dialogManager.dismissActiveDialog()
                    dialogTitleText = null // Clear reference when dialog is dismissed
                    
                    // Only show the dialog if we have a valid code
                    if (expiredCode.isNotEmpty()) {
                        dialogManager.showCodeExpiredDialog(expiredCode) {
                            // Reset code and tries when user confirms
                            currentCode = ""
                            saveCurrentCode("")
                            remainingTries = 3
                            saveRemainingTries(3)
                            // Reset expiration time
                            resetExpirationTimer()
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                        }
                    }
                }
            }
        )
        
        // Update local time variables from TimerManager
        if (timerManager.isGenerateInCooldown()) {
            generateCooldownEndTime = System.currentTimeMillis() + timerManager.getRemainingGenerateCooldownTime()
        }
        
        if (timerManager.isMorseInCooldown()) {
            morseCooldownEndTime = System.currentTimeMillis() + timerManager.getRemainingMorseCooldownTime()
        }
        
        expirationEndTime = System.currentTimeMillis() + timerManager.getRemainingExpirationTime()
    }

    /**
     * Update the biometric setup button text based on whether biometric is enabled
     */
    private fun updateBiometricButtonText() {
        try {
            if (::biometricSetupButton.isInitialized) {
                biometricSetupButton.text = if (biometricManager.isBiometricEnabled()) {
                    "Disable Biometric"
                } else {
                    "Set Up Biometric"
                }
            }
        } catch (e: Exception) {
            Log.e("MCFragment", "Error updating biometric button text: ${e.message}")
        }
    }

    /**
     * Set up biometric login functionality
     */
    private fun setupBiometricLogin(currentUser: FirebaseUser?) {
        // Check if biometric is available
        if (!biometricManager.isBiometricAvailable()) {
            Toast.makeText(requireContext(),
                "Biometric authentication is not available on this device",
                Toast.LENGTH_SHORT).show()
            return
        }

        // Get current user email
        val email = currentUser?.email

        if (email == null) {
            Toast.makeText(requireContext(), "Unable to get user email", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if biometric is already enabled
        if (biometricManager.isBiometricEnabled()) {
            // Show dialog to disable biometric
            showDisableBiometricDialog()
        } else {
            // Show password confirmation dialog before enabling biometric
            val passwordConfirmationDialog = PasswordConfirmationDialog(requireContext(), dialogManager)
            passwordConfirmationDialog.showPasswordConfirmationDialog(
                email = email,
                biometricManager = biometricManager,
                fragment = this,
                onSuccess = {
                    updateBiometricButtonText()
                },
                onCancel = {}
            )
        }
    }

    /**
     * Show a dialog to confirm disabling biometric authentication
     */
    private fun showDisableBiometricDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Disable Biometric Login")
            .setMessage("Are you sure you want to disable biometric login?")
            .setPositiveButton("Disable") { dialog, _ ->
                dialog.dismiss()
                biometricManager.disableBiometric()
                Toast.makeText(requireContext(), "Biometric login disabled", Toast.LENGTH_SHORT).show()
                updateBiometricButtonText()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun updateCodeClickableStyle() {
        // Make the code clickable only if there's a valid code
        val hasValidCode = currentCode.isNotEmpty() && displayCode.isNotEmpty() && remainingTries > 0
        codeDisplayText.isClickable = true

        if (hasValidCode) {
            // Highlight the code when it's valid and clickable
            codeDisplayText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light))
            codeDisplayText.paint.isUnderlineText = true
        } else {
            // Use normal styling when no valid code
            codeDisplayText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            codeDisplayText.paint.isUnderlineText = false
        }
    }

    private fun generateNewMorseCodeAndShowDialog() {
        // Always reset tries to 3 for every new code generation
        remainingTries = 3
        saveRemainingTries(remainingTries)
        // Generate a new code
        currentCode = generateRandomCode()
        saveCurrentCode(currentCode)
        // EXPLICITLY UPDATE THE UI TEXT
        codeDisplayText.text = displayCode
        // Reset cooldown for new code
        lastMorsePlayTime = 0L
        morseCooldownEndTime = 0L
        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0L).apply()
        // Show the play Morse code dialog with 3 tries (force skip max tries logic)
        showMorseCodeDialogForce(currentCode)
    }

    /**
     * Reset the expiration timer and save to SharedPreferences
     */
    private fun resetExpirationTimer() {
        timerManager.resetExpirationTimer()
        expirationEndTime = 0
    }

    /**
     * Start the expiration timer with the specified duration
     */
    private fun startExpirationTimer(duration: Long) {
        // Set the expiration end time for reference
        expirationEndTime = System.currentTimeMillis() + duration
        
        // We need to find the dialog title after the dialog is fully initialized
        Handler(Looper.getMainLooper()).post {
            // Find and store the title text
            dialogTitleText = if (::dialog.isInitialized && dialog.isShowing) {
                try {
                    val title = dialog.findViewById<TextView>(R.id.dialogTitle)
                    if (title != null) {
                        Log.d("MCFragment", "Found dialog title for expiration timer: $title")
                    } else {
                        Log.e("MCFragment", "Dialog title NOT FOUND for expiration timer")
                    }
                    title
                } catch (e: Exception) {
                    Log.e("MCFragment", "Error finding dialog title: ${e.message}")
                    null
                }
            } else {
                Log.e("MCFragment", "Dialog not initialized or not showing")
                null
            }
            
            // Start the timer using the TimerManager with the found title text
            timerManager.startExpirationTimer(
                duration,
                dialogTitleText
            ) {
                // This code runs when the timer finishes (code expires)
                if (::dialogManager.isInitialized) {
                    // Capture the code before dismissing the dialog
                    val expiredCode = currentCode
                    
                    dialogManager.dismissActiveDialog()
                    dialogTitleText = null // Clear reference
                    
                    // Only show the dialog if we have a valid code
                    if (expiredCode.isNotEmpty()) {
                        dialogManager.showCodeExpiredDialog(expiredCode) {
                            // Reset code and tries when user confirms
                            currentCode = ""
                            saveCurrentCode("")
                            remainingTries = 3
                            saveRemainingTries(3)
                            // Reset expiration time
                            resetExpirationTimer()
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts or restarts the expiration UI timer with the specified duration
     */
    private fun startExpirationUITimer(duration: Long) {
        // We need to find the dialog title after the dialog is fully initialized
        Handler(Looper.getMainLooper()).post {
            // Find and store the title text
            dialogTitleText = if (::dialog.isInitialized && dialog.isShowing) {
                try {
                    val title = dialog.findViewById<TextView>(R.id.dialogTitle)
                    if (title != null) {
                        Log.d("MCFragment", "Found dialog title for UI timer: $title")
                    } else {
                        Log.e("MCFragment", "Dialog title NOT FOUND for UI timer")
                    }
                    title
                } catch (e: Exception) {
                    Log.e("MCFragment", "Error finding dialog title for UI timer: ${e.message}")
                    null
                }
            } else {
                Log.e("MCFragment", "Dialog not initialized or not showing for UI timer")
                null
            }
            
            // Use the TimerManager to handle the timer with the found title text
            timerManager.startExpirationTimer(
                duration,
                dialogTitleText
            ) {
                // This code runs when the timer finishes (code expires)
                if (::dialogManager.isInitialized) {
                    // Capture the code before dismissing the dialog
                    val expiredCode = currentCode
                    
                    dialogManager.dismissActiveDialog()
                    dialogTitleText = null // Clear reference
                    
                    // Only show the dialog if we have a valid code
                    if (expiredCode.isNotEmpty()) {
                        dialogManager.showCodeExpiredDialog(expiredCode) {
                            // Reset code and tries when user confirms
                            currentCode = ""
                            saveCurrentCode("")
                            remainingTries = 3
                            saveRemainingTries(3)
                            // Reset expiration time
                            resetExpirationTimer()
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if the expiration timer has expired and handle accordingly
     * @return true if expired, false otherwise
     */
    private fun checkExpirationTimer(): Boolean {
        if (timerManager.isCodeExpired()) {
            // Capture the expired code before clearing it
            val expiredCode = currentCode
            
            // Always clear all code data when expired
            clearAllCodeData()
            
            // Only show the expired dialog if the user hasn't already used all tries
            val savedTries = sharedPreferences.getInt(REMAINING_TRIES_KEY, 3)
            if (savedTries > 0 && expiredCode.isNotEmpty()) {
                // Use a Handler to post this on the main thread after a short delay
                // This ensures proper dialog handling and prevents any race conditions
                Handler(Looper.getMainLooper()).post {
                    try {
                        dialogManager.showCodeExpiredDialog(expiredCode) {
                            // Additional reset actions when user confirms
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                        }
                    } catch (e: Exception) {
                        Log.e("MCFragment", "Error showing expired code dialog in checkExpirationTimer", e)
                    }
                }
            }
            return true
        }
        return false
    }

    /**
     * Check if any code has expired and clear it completely from SharedPreferences
     * This is called at the start of onResume to ensure no expired codes are ever displayed
     */
    private fun checkAndClearExpiredCode() {
        val now = System.currentTimeMillis()
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        val savedCode = sharedPreferences.getString(MC_DIALOG_CODE_KEY, "") ?: ""

        // If there's an expiration time set and it has passed, show dialog and clear everything
        if (savedExpirationTime > 0 && savedExpirationTime <= now && savedCode.isNotEmpty()) {
            Log.d("MCFragment", "Found expired code: $savedCode")

            // Clear all data immediately to prevent state issues
            clearAllCodeData()
            Log.d("MCFragment", "Cleared expired code data during checkAndClearExpiredCode")

            // Only show the expired dialog if the user hasn't already used all tries
            val savedTries = sharedPreferences.getInt(REMAINING_TRIES_KEY, 3)
            if (savedTries > 0) {
                // Use a Handler to post this on the main thread after a short delay
                // This ensures proper dialog handling and prevents any race conditions
                Handler(Looper.getMainLooper()).post {
                    try {
                        dialogManager.showCodeExpiredDialog(savedCode) {
                            // Additional reset actions when user confirms
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                            // No need to clear data again, we already did it above
                        }
                    } catch (e: Exception) {
                        Log.e("MCFragment", "Error showing expired code dialog", e)
                    }
                }
            }
        }

        // Also check if there's a code but no expiration time (invalid state)
        if (savedCode.isNotEmpty() && savedExpirationTime <= 0) {
            clearAllCodeData()
            Log.d("MCFragment", "Cleared code with no expiration time (invalid state)")
        }
    }

    /**
     * Clear all code-related data from memory and SharedPreferences
     * This ensures that expired codes are completely removed from the system
     */
    private fun clearAllCodeData() {
        // Clear in-memory variables
        currentCode = ""
        displayCode = ""  // Clear the display code to ensure it shows as dashes
        transmitCode = "" // Clear the transmit code as well
        remainingTries = 3
        expirationEndTime = 0

        // Clear all code-related SharedPreferences
        val editor = sharedPreferences.edit()
        editor.putString(MC_DIALOG_CODE_KEY, "")
        editor.putString("display_code", "") // Clear display code in SharedPreferences
        editor.putString("transmit_code", "") // Clear transmit code in SharedPreferences
        editor.putInt(MC_DIALOG_TRIES_KEY, 3)
        editor.putLong(EXPIRATION_END_TIME_KEY, 0)
        editor.putBoolean(MC_DIALOG_OPEN_KEY, false)
        editor.apply()

        // Update UI to show dashes
        updateGeneratedCodeText()
        updateCodeClickableStyle()

        Log.d("MCFragment", "All code data cleared completely, UI reset to show dashes")
    }

    /**
     * Save the current dialog state to SharedPreferences
     * This ensures the dialog can be restored when returning from minimized state
     */
    private fun saveDialogState() {
        // Save whether the dialog is currently open
        val isDialogOpen = ::dialog.isInitialized && dialog.isShowing
        
        // Save all relevant dialog state
        val editor = sharedPreferences.edit()
        editor.putBoolean(MC_DIALOG_OPEN_KEY, isDialogOpen)
        
        if (isDialogOpen) {
            // Save current code and tries
            editor.putString(MC_DIALOG_CODE_KEY, currentCode)
            editor.putInt(MC_DIALOG_TRIES_KEY, remainingTries)
            
            // Save cooldown and expiration times
            val now = System.currentTimeMillis()
            val remainingCooldown = if (morseCooldownEndTime > now) morseCooldownEndTime - now else 0L
            editor.putLong(MC_DIALOG_COOLDOWN_KEY, remainingCooldown)
            editor.putLong(EXPIRATION_END_TIME_KEY, expirationEndTime)
            
            Log.d("MCFragment", "Saved dialog state: code=$currentCode, tries=$remainingTries, cooldown=$remainingCooldown")
        }
        
        editor.apply()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
        
        /**
         * Factory method to create a new instance of MCFragment
         * @return A new instance of MCFragment
         */
        fun newInstance(): MCFragment {
            return MCFragment()
        }
    }
}