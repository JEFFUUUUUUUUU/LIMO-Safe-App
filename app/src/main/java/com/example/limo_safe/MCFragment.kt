
package com.example.limo_safe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.example.limo_safe.utils.BiometricManager
import com.example.limo_safe.utils.DialogManager
import com.example.limo_safe.utils.MorseCodeHelper
import com.example.limo_safe.utils.PasswordConfirmationDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlin.concurrent.thread

// Main Fragment class that handles the Morse Code functionality, extends Fragment
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

    // Called when the fragment is first created
    override fun onCreate(savedInstanceState: Bundle?) {
        // Call the parent class's onCreate method
        super.onCreate(savedInstanceState)
        // Initialize the MorseCodeHelper with the current context
        morseCodeHelper = MorseCodeHelper(requireContext())
        // Initialize SharedPreferences for data persistence
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Handle back press behavior
        val callback = object : OnBackPressedCallback(true) {
            // Define what happens when back button is pressed
            override fun handleOnBackPressed() {
                // Get current time to check cooldown
                val currentTime = System.currentTimeMillis()
                // Only allow back navigation if not in cooldown and tries remain
                if (generateCooldownEndTime <= currentTime && remainingTries > 0) {
                    // Dismiss dialog if it's showing
                    if (::dialog.isInitialized && dialog.isShowing) {
                        dialog.dismiss()
                    }
                    // Pop back stack if there are fragments in the back stack
                    if (parentFragmentManager.backStackEntryCount > 0) {
                        parentFragmentManager.popBackStack()
                    } else {
                        // Show exit confirmation dialog if this is the last fragment
                        showExitConfirmationDialog()
                    }
                } else {
                    // Show toast message if in cooldown or no tries remain
                    Toast.makeText(requireContext(),
                        "Please wait for cooldown or check remaining tries",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Register the callback with the activity's back press dispatcher
        requireActivity().onBackPressedDispatcher.addCallback(this, callback)
    }

    // Called to create the view hierarchy associated with the fragment
    override fun onCreateView(
        // Layout inflater to inflate the fragment's layout
        inflater: LayoutInflater,
        // Container where the fragment's UI will be attached
        container: ViewGroup?,
        // Saved instance state if fragment is being reconstructed
        savedInstanceState: Bundle?
    ): View? {
        // Initialize MorseCodeHelper with context
        morseCodeHelper = MorseCodeHelper(requireContext())

        // Initialize DialogManager for managing dialog operations
        dialogManager = DialogManager(requireContext())
        // Initialize BiometricManager for biometric authentication
        biometricManager = BiometricManager(requireContext())

        // Load saved state from SharedPreferences
        loadSavedState()

        // Inflate the fragment's layout
        val view = inflater.inflate(R.layout.fragment_mc, container, false)

        // Initialize drawer layout and hamburger menu
        drawerLayout = view.findViewById(R.id.drawerLayout)
        menuIcon = view.findViewById(R.id.menuIcon)

        // Set up account info and logout button in the navigation drawer
        val navHeader = view.findViewById<View>(R.id.nav_header_root)
        if (navHeader != null) {
            // Find navigation header subtitle TextView
            navHeaderSubtitle = navHeader.findViewById(R.id.navHeaderSubtitle)
            // Find logout button
            logoutButton = navHeader.findViewById(R.id.logoutButton)
            // Find biometric setup button
            biometricSetupButton = navHeader.findViewById(R.id.biometricSetupButton)

            // Set up user account info from Firebase
            val currentUser = FirebaseAuth.getInstance().currentUser
            // Set the user's email as the subtitle or default to "account."
            navHeaderSubtitle.text = currentUser?.email ?: "account."

            // Update biometric button text based on current state
            updateBiometricButtonText()

            // Set up biometric setup button click listener
            biometricSetupButton.setOnClickListener {
                // Close drawer when button is clicked
                drawerLayout.closeDrawer(GravityCompat.START)
                // Start biometric setup process
                setupBiometricLogin(currentUser)
            }

            // Set up logout button click listener
            logoutButton.setOnClickListener {
                // Close drawer when button is clicked
                drawerLayout.closeDrawer(GravityCompat.START)

                // Show confirmation dialog using DialogManager for consistency
                dialogManager.showLogoutConfirmationDialog {
                    try {
                        // Sign out from Firebase when confirmed
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
                            // Log error if MainActivity is not available
                            Log.e("MCFragment", "MainActivity is null or finishing")
                        }
                    } catch (e: Exception) {
                        // Log any errors during logout
                        Log.e("MCFragment", "Error during logout: ${e.message}")
                    }
                }
            }
        }

        // Set up hamburger menu click listener directly
        menuIcon.setOnClickListener {
            // Log the click event
            Log.d("MCFragment", "Menu icon clicked")
            // Toggle drawer open/closed state
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        
        // Find and set up the Check Monitoring button in the navigation drawer
        // Post to main thread to ensure drawer is fully initialized
        Handler(Looper.getMainLooper()).post {
            try {
                // Get the direct child of the DrawerLayout that's not the main content
                for (i in 0 until drawerLayout.childCount) {
                    val child = drawerLayout.getChildAt(i)
                    // Skip the main content view
                    if (child.id != R.id.mainContent) {
                        // Find the navigation header root
                        val navHeaderRoot = child.findViewById<View>(R.id.nav_header_root)
                        if (navHeaderRoot != null) {
                            // Find the Check Monitoring button
                            val checkMonitoringBtn = navHeaderRoot.findViewById<Button>(R.id.checkMonitoringButton)
                            if (checkMonitoringBtn != null) {
                                // Log success finding the button
                                Log.d("MCFragment", "Found Check Monitoring button, setting up click listener")
                                // Set up click listener
                                checkMonitoringBtn.setOnClickListener {
                                    // Log click event
                                    Log.d("MCFragment", "Check Monitoring button clicked")
                                    // Close drawer
                                    drawerLayout.closeDrawer(GravityCompat.START)
                                    // Navigate to monitoring screen
                                    navigateToMonitoring()
                                }
                            } else {
                                // Log error if button not found
                                Log.e("MCFragment", "Check Monitoring button not found in nav header")
                            }
                        }
                        // Break after processing the drawer view
                        break
                    }
                }
            } catch (e: Exception) {
                // Log any errors during setup
                Log.e("MCFragment", "Error setting up Check Monitoring button: ${e.message}")
                e.printStackTrace()
            }
        }

        // Add drawer listener to dim background when drawer is opened
        val mainContent = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.mainContent)
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            // Called when drawer slide position changes
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Apply dim effect based on how far the drawer is open
                val dimAmount = slideOffset * 0.3f // Max 30% dim when fully open
                mainContent?.alpha = 1f - dimAmount // Reduce alpha for dimming
            }

            // Called when drawer is fully opened
            override fun onDrawerOpened(drawerView: View) {
                // Set maximum dim effect when fully open
                mainContent?.alpha = 0.7f // 30% dim when fully open
            }

            // Called when drawer is fully closed
            override fun onDrawerClosed(drawerView: View) {
                // Remove dim effect when fully closed
                mainContent?.alpha = 1.0f // No dim when closed
            }

            // Called when drawer state changes
            override fun onDrawerStateChanged(newState: Int) {
                // Not used for dimming effect
            }
        })

        // Return the inflated view
        return view
    }

    // Initialize all UI components from the layout
    private fun setupViews(view: View) {
        try {
            // Find and initialize all UI elements using findViewById
            generateCodeButton = view.findViewById(R.id.generateCodeButton)
            checkMonitoringButton = view.findViewById(R.id.checkMonitoringButton)
            exitButton = view.findViewById(R.id.exitButton)
            generatedCodeText = view.findViewById(R.id.generatedCodeText)
            codeDisplayText = view.findViewById(R.id.codeDisplayText)
            cooldownText = view.findViewById(R.id.cooldownText)
            drawerLayout = view.findViewById(R.id.drawerLayout)
            menuIcon = view.findViewById(R.id.menuIcon)
            logoutButton = view.findViewById(R.id.logoutButton)

            // Ensure all views are properly initialized to prevent null pointer exceptions
            if (generateCodeButton == null || checkMonitoringButton == null ||
                exitButton == null || generatedCodeText == null ||
                codeDisplayText == null || cooldownText == null ||
                drawerLayout == null || menuIcon == null ||
                logoutButton == null) {
                // Throw exception if any view is null
                throw IllegalStateException("One or more views could not be found in the layout")
            }
        } catch (e: Exception) {
            // Log any errors during view initialization
            Log.e("MCFragment", "Error initializing views: ${e.message}")
            e.printStackTrace()
        }
    }

    // Called after onCreateView when the fragment's view has been created
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Call parent implementation first
        super.onViewCreated(view, savedInstanceState)

        try {
            // Initialize views first to ensure they're available
            setupViews(view)

            // Initialize Firebase database reference
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
            
            // Check camera permission for flashlight
            checkCameraPermission()

            // Make the codeDisplayText clickable to reopen Morse code dialog
            codeDisplayText.setOnClickListener {
                // Set flag to indicate dialog is being opened from code click
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

                        // Log reopening information
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
                // Reset fromCodeClick flag
                fromCodeClick = false
            }
            // Add visual cue for clickability
            updateCodeClickableStyle()
        } catch (e: Exception) {
            // Log any errors during UI initialization
            Log.e("MCFragment", "Error initializing UI: ${e.message}")
            e.printStackTrace()
        }
    }

    // Called when the fragment is no longer visible to the user
    override fun onPause() {
        // Call parent implementation first
        super.onPause()
        try {
            // Save dialog state when app is minimized
            saveDialogState()
            // Log that dialog state was saved
            Log.d("MCFragment", "onPause: Saved dialog state")

            // Cancel any running timers to prevent memory leaks
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

                // Log the saved dialog state
                Log.d("MCFragment", "onPause: Saving dialog state, cooldown: $remainingCooldown, expiration: $remainingExpiration, tries: $remainingTries")

                // Save all dialog state to SharedPreferences
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
                    // Save code and tries but mark dialog as closed
                    sharedPreferences.edit()
                        .putBoolean(MC_DIALOG_OPEN_KEY, false)
                        .putString(MC_DIALOG_CODE_KEY, currentCode)
                        .putInt(MC_DIALOG_TRIES_KEY, remainingTries)
                        .apply()
                } else {
                    // Just mark dialog as closed if no code
                    sharedPreferences.edit().putBoolean(MC_DIALOG_OPEN_KEY, false).apply()
                }
            }
            // Important: Do NOT dismiss dialog here; we want to preserve its state
        } catch (e: Exception) {
            // Print stack trace for any exceptions
            e.printStackTrace()
        }
    }

    // Called when the fragment is visible to the user
    override fun onResume() {
        // Call parent implementation first
        super.onResume()
        try {
            // Log that we're restoring state
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
                // Retrieve saved dialog state
                val code = sharedPreferences.getString(MC_DIALOG_CODE_KEY, "") ?: ""
                val tries = sharedPreferences.getInt(MC_DIALOG_TRIES_KEY, 3)
                val cooldown = sharedPreferences.getLong(MC_DIALOG_COOLDOWN_KEY, 0)

                // Get the EXACT current cooldown remaining time
                val now = System.currentTimeMillis()
                val cooldownEnd = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
                val actualCooldown = if (cooldownEnd > now) cooldownEnd - now else cooldown

                // Log the dialog restoration details
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
                            // Play Morse code when play button is clicked
                            playMorseCode(transmitCode)
                            // Decrement remaining tries
                            remainingTries--
                            // Save updated tries count
                            sharedPreferences.edit().putInt(MC_DIALOG_TRIES_KEY, remainingTries).apply()
                            // Update UI elements
                            updateCodeClickableStyle()
                            updateGeneratedCodeText()
                            dialogManager.updateTriesText(remainingTries)
                            // Start cooldown timer
                            startMorseCooldown(MORSE_COOLDOWN)
                            // Show max tries dialog if no tries remain
                            if (remainingTries <= 0) {
                                dialogManager.showMaxTriesDialog()
                                currentCode = ""
                                saveCurrentCode("")
                                saveRemainingTries(3)
                                dialog.dismiss()
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

                    // Show the dialog
                    dialog.show()
                }
            }

            // Reset UI state for generate button
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
                // Update the UI
                updateGeneratedCodeText()
            }
        } catch (e: Exception) {
            // Print stack trace for any exceptions
            e.printStackTrace()
        }
    }

    // Set up click listeners for all buttons and interactive elements
    private fun setupClickListeners() {
        try {
            // Set up generate code button click listener
            generateCodeButton.setOnClickListener {
                try {
                    // Check camera permission first - needed for flashlight
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
                    // Start cooldown for generate button
                    startGenerateButtonCooldown()
                } catch (e: Exception) {
                    // Print stack trace for any exceptions
                    e.printStackTrace()
                }
            }

            // Set up check monitoring button click listener
            checkMonitoringButton.setOnClickListener {
                try {
                    // Navigate to monitoring screen
                    navigateToMonitoring()
                } catch (e: Exception) {
                    // Print stack trace and show error message
                    e.printStackTrace()
                    Toast.makeText(context, "Error navigating to monitoring: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // Set up exit button click listener
            exitButton.setOnClickListener {
                try {
                    // Show exit confirmation dialog
                    showExitConfirmationDialog()
                } catch (e: Exception) {
                    // Print stack trace and show error message
                    e.printStackTrace()
                    Toast.makeText(context, "Error showing exit dialog: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // Set up hamburger menu click listener
            menuIcon.setOnClickListener {
                try {
                    // Toggle drawer open/closed state
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START)
                    }
                } catch (e: Exception) {
                    // Print stack trace and log error
                    e.printStackTrace()
                    Log.e("MCFragment", "Error toggling drawer: ${e.message}")
                }
            }

            // Set up logout button in navigation drawer
            logoutButton.setOnClickListener {
                try {
                    // Close drawer when button is clicked
                    drawerLayout.closeDrawer(GravityCompat.START)

                    // Show confirmation dialog
                    val builder = AlertDialog.Builder(requireContext())
                    builder.setTitle("Logout Confirmation")
                        .setMessage("Are you sure you want to logout?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            // Dismiss dialog
                            dialog.dismiss()
                            // Sign out from Firebase
                            FirebaseAuth.getInstance().signOut()

                            // Navigate back to login
                            val mainActivity = activity as? MainActivity
                            mainActivity?.clearBackStackAndNavigateToLogin()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            // Just dismiss dialog when cancelled
                            dialog.dismiss()
                        }
                        .create()
                        .show()
                } catch (e: Exception) {
                    // Print stack trace and log error
                    e.printStackTrace()
                    Log.e("MCFragment", "Error handling logout: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Print stack trace for any exceptions
            e.printStackTrace()
        }
    }

    // Start cooldown timer for generate button
    private fun startGenerateButtonCooldown(duration: Long = GENERATE_COOLDOWN) {
        // Disable button and make it appear disabled
        generateCodeButton.isEnabled = false
        generateCodeButton.alpha = 0.5f

        // Set the cooldown end time in the future
        generateCooldownEndTime = System.currentTimeMillis() + duration

        // Save cooldown end time to SharedPreferences
        saveGenerateCooldownEndTime(generateCooldownEndTime)

        // Cancel any existing timer
        countDownTimer?.cancel()
        // Create and start a new timer
        countDownTimer = object : CountDownTimer(duration, 1000) {
            // Called on each tick (every second)
            override fun onTick(millisUntilFinished: Long) {
                // Calculate minutes and seconds for display
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = (millisUntilFinished / 1000) % 60
                // Update button text with remaining time
                generateCodeButton.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
            }

            // Called when timer finishes
            override fun onFinish() {
                // Re-enable button and reset appearance
                generateCodeButton.isEnabled = true
                generateCodeButton.alpha = 1.0f
                generateCodeButton.text = "Generate Code"
                // Reset cooldown end time
                generateCooldownEndTime = 0

                // Reset cooldown end time in SharedPreferences
                saveGenerateCooldownEndTime(0)
            }
        }.start()
    }

    // Update the generated code text and handle expired codes
    private fun updateGeneratedCodeText() {
        // Set base text label
        generatedCodeText.text = "Generated Code: "

        // First check if the code has expired
        val now = System.currentTimeMillis()
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        val isExpired = savedExpirationTime > 0 && savedExpirationTime <= now

        // If code is expired, force display as dashes
        if (isExpired) {
            // Clear code variables
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

        // Log the updated state
        Log.d("MCFragment", "Updated generated code text. isExpired=$isExpired, hasValidCode=$hasValidCode, display=${codeDisplayText.text}")
    }

    // SuppressLint to avoid restricted API warnings
    @SuppressLint("RestrictedApi")
    // Variables to store different versions of the code
    private var displayCode = "" // Code shown to user (without tag)
    private var transmitCode = "" // Full code with tag for Morse transmission
    
    // Generate a random 6-character code
    private fun generateRandomCode(): String {
        // Define possible characters for the code
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        // Generate random 6-character code
        val randomCode = (1..4).map { chars.random() }.joinToString("")

        // Set the display code immediately
        displayCode = randomCode

        // Update UI with display code only (without tag)
        updateGeneratedCodeText()

        // Get current user ID from Firebase or return random code if not available
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return randomCode

        // First, get the user's tag from Firebase
        database.child("users").child(userId).child("tag").get()
            .addOnSuccessListener { tagSnapshot ->
                // Get user tag or empty string if null
                val userTag = tagSnapshot.value as? String ?: ""
                // Take only the first character if tag exists
                val tagChar = if (userTag.isNotEmpty()) userTag.first().toString() else ""
                // Create full code by prepending tag character to random code
                val fullCode = tagChar + randomCode
                // Store the full code for transmission
                transmitCode = fullCode

                // Store the OTP data in Firebase
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

                // Send updates to Firebase
                database.updateChildren(updates)
                    .addOnSuccessListener {
                        // No need to update UI again since we've already displayed the code

                        // Auto-delete after 45 seconds (30s expiry + 15s buffer)
                        Handler(Looper.getMainLooper()).postDelayed({
                            database.child("users").child(userId).child("otp").removeValue()
                        }, 45000)
                    }
            }

        // Return the random code initially, it will be updated when we get the tag
        return randomCode
    }

    // Navigate to the login screen
    private fun navigateToLogin() {
        // Get the main activity
        val mainActivity = requireActivity() as MainActivity
        // Use the helper method in MainActivity to clear backstack and navigate to login
        mainActivity.clearBackStackAndNavigateToLogin()
    }

    // Navigate to the monitoring screen
    private fun navigateToMonitoring() {
        try {
            // Check if fragment is attached to activity and activity isn't finishing
            if (isAdded && activity != null && !requireActivity().isFinishing) {
                // First, ensure we save the current state before navigating
                // This is critical for preserving tries when navigating between pages
                saveRemainingTries(remainingTries)

                // Save dialog state if it's showing
                if (::dialog.isInitialized && dialog.isShowing && currentCode.isNotEmpty()) {
                    // Calculate remaining cooldown
                    val now = System.currentTimeMillis()
                    val remainingCooldown = if (morseCooldownEndTime > now) morseCooldownEndTime - now else 0L

                    // Save to SharedPreferences
                    sharedPreferences.edit()
                        .putBoolean(MC_DIALOG_OPEN_KEY, true)
                        .putString(MC_DIALOG_CODE_KEY, currentCode)
                        .putInt(MC_DIALOG_TRIES_KEY, remainingTries)
                        .putLong(MC_DIALOG_COOLDOWN_KEY, remainingCooldown)
                        .apply()

                    // Log the saved state
                    Log.d("MCFragment", "navigateToMonitoring: Saved dialog state with tries: $remainingTries")
                }

                // Now cancel timers and dismiss dialog
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
            // Print stack trace and show error message
            e.printStackTrace()
            Toast.makeText(context, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Show the exit confirmation dialog
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

    // Show the Morse code dialog with the given code and cooldown
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

        // Log dialog parameters
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
                // Play Morse code when button is clicked
                playMorseCode(transmitCode)
                // Decrement remaining tries
                remainingTries--
                // Save updated tries count
                sharedPreferences.edit().putInt(MC_DIALOG_TRIES_KEY, remainingTries).apply()
                // Update UI elements
                updateCodeClickableStyle()
                updateGeneratedCodeText()
                dialogManager.updateTriesText(remainingTries)
                // Start cooldown timer
                startMorseCooldown(MORSE_COOLDOWN)
                // Check if we've run out of tries
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

        // Only start a new expiration timer if there isn't an active one
        val currentTime = System.currentTimeMillis()
        if (expirationEndTime <= currentTime) {
            // Start a new timer with the full duration
            startExpirationTimer(EXPIRATION_DURATION)
        }

        // Start cooldown timer if needed
        startMorseCooldownTimer(remainingCooldown)

        // Show the dialog
        dialog.show()
    }

    // Force show the Morse code dialog with a new code - skip some validation checks
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

        // Create the dialog with the current state
        dialog = dialogManager.createMorseCodeDialog(
            code,
            remainingTries,
            remainingCooldown,
            { playButton, cooldownText ->
                // Play Morse code when button is clicked
                playMorseCode(transmitCode)
                // Decrement remaining tries
                remainingTries--
                // Save updated tries count
                sharedPreferences.edit().putInt(MC_DIALOG_TRIES_KEY, remainingTries).apply()
                // Update UI elements
                updateCodeClickableStyle()
                updateGeneratedCodeText()
                dialogManager.updateTriesText(remainingTries)
                // Start cooldown timer
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

        // Get reference to play button and cooldown text
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

        // Start cooldown timer if needed (should be 0)
        startMorseCooldownTimer(remainingCooldown)

        // Show the dialog
        dialog.show()
    }

    // Start Morse code cooldown with the specified duration
    private fun startMorseCooldown(duration: Long) {
        // Set the cooldown end time in the future
        morseCooldownEndTime = System.currentTimeMillis() + duration
        Log.d("MCFragment", "Setting cooldown end time to: $morseCooldownEndTime (${duration}ms from now)")

        // Save cooldown end time to SharedPreferences
        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, morseCooldownEndTime).apply()

        // Start the timer with the new duration
        startMorseCooldownTimer(duration)
    }

    /**
     * Starts or restarts the Morse code cooldown timer with the specified duration
     * This is a separate method to avoid code duplication and ensure consistent timer behavior
     */
    private fun startMorseCooldownTimer(duration: Long) {
        // Always cancel any existing timer before starting a new one
        morseTimer?.cancel()
        morseTimer = null

        // Find UI elements in the dialog
        val playButton = dialog.findViewById<Button>(R.id.playButton)
        val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)

        // Only proceed if UI elements are found
        if (playButton != null && cooldownText != null) {
            if (duration > 0) {
                // Update UI for cooldown state
                playButton.isEnabled = false
                playButton.alpha = 0.5f

                // Calculate seconds remaining (round up to ensure we don't show 0 when there's still time)
                val secondsRemaining = (duration + 999) / 1000

                // Only update button text with cooldown, hide the cooldown text
                cooldownText.visibility = View.GONE
                playButton.text = "PLAY MORSE CODE (${secondsRemaining})"

                Log.d("MCFragment", "Starting cooldown timer with ${duration}ms remaining")

                // Start the countdown timer
                morseTimer = object : CountDownTimer(duration, 1000) {
                    // Update every second
                    override fun onTick(millisUntilFinished: Long) {
                        val seconds = (millisUntilFinished + 999) / 1000 // Round up
                        // Only update button text with cooldown
                        playButton.text = "PLAY MORSE CODE (${seconds})"
                        Log.d("MCFragment", "Cooldown tick: ${seconds}s remaining")
                    }

                    // When timer completes
                    override fun onFinish() {
                        // Reset button state when cooldown finishes
                        if (remainingTries > 0) {
                            playButton.isEnabled = true
                            playButton.alpha = 1.0f
                        }
                        playButton.text = "PLAY MORSE CODE"
                        lastMorsePlayTime = 0
                        morseCooldownEndTime = 0
                        // Clear cooldown in SharedPreferences
                        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0).apply()
                        Log.d("MCFragment", "Cooldown timer finished")
                    }
                }.start()
            } else {
                // Make sure UI is reset properly when no cooldown
                playButton.isEnabled = remainingTries > 0
                playButton.alpha = if (remainingTries > 0) 1.0f else 0.5f
                cooldownText.visibility = View.GONE
                playButton.text = "PLAY MORSE CODE"
                Log.d("MCFragment", "No cooldown needed, button enabled: ${remainingTries > 0}")
            }
        }
    }

    // Play the given code in Morse code using a separate thread
    private fun playMorseCode(code: String) {
        // Use a separate thread to avoid blocking the UI
        thread {
            try {
                // Use the morseCodeHelper to play the code
                morseCodeHelper?.playMorseCode(code)
            } catch (e: Exception) {
                // Print stack trace for any exceptions
                e.printStackTrace()
            }
        }
    }

    // Check if camera permission is granted (needed for flashlight)
    private fun checkCameraPermission(): Boolean {
        // Check if the camera permission is already granted
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            // Request permission if not granted
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
            // Return false to indicate permission not yet granted
            return false
        }
        // Return true to indicate permission is granted
        return true
    }

    // Handle the result of permission requests
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        // Check which permission request this is responding to
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                // Check if permission was granted
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, show toast
                    Toast.makeText(
                        requireContext(),
                        "Camera permission granted",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Proceed with generating code if that's what we were trying to do
                    if (::generateCodeButton.isInitialized && generateCodeButton.isEnabled) {
                        generateRandomCode()
                    }
                } else {
                    // Permission denied, show a toast message
                    Toast.makeText(
                        requireContext(),
                        "Camera permission is required for flashlight functionality",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Regardless of permission result, make sure we're still on the LIMO Generator page
                // by checking if we're still attached to the activity
                if (isAdded && !isDetached) {
                    // We're still on the MCFragment, no need to navigate
                    Log.d("MCFragment", "Still on MCFragment after permission result")
                } else {
                    // If somehow we got detached, try to navigate back to MCFragment
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null && !mainActivity.isFinishing) {
                        Log.d("MCFragment", "Navigating back to MCFragment after permission result")
                        mainActivity.onLoginSuccessful() // This will navigate to MCFragment
                    }
                }
                return
            }
            else -> {
                // Call super implementation for other permission requests
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    // Reset the generate button to its initial state
    private fun resetGenerateButton() {
        // Only proceed if the button is initialized
        if (::generateCodeButton.isInitialized) {
            // Reset all properties using apply to set multiple properties at once
            generateCodeButton.apply {
                // Enable the button
                isEnabled = true
                // Set full opacity
                alpha = 1.0f
                // Reset text
                text = "Generate Code"
            }
        }
    }

    // Called when the view is about to be destroyed
    override fun onDestroyView() {
        // Call the cleanup method to handle resource cleanup
        cleanupResources()
        // Call parent implementation
        super.onDestroyView()
    }

    // Called when the fragment is destroyed
    override fun onDestroy() {
        // Call the cleanup method to handle resource cleanup
        cleanupResources()
        // Call parent implementation
        super.onDestroy()
    }

    /**
     * Centralized cleanup method to handle resource cleanup
     * Used by onDestroyView, onDestroy, and other cleanup scenarios
     */
    private fun cleanupResources() {
        try {
            // Cancel any running timers
            morseTimer?.cancel()
            countDownTimer?.cancel()

            // Dismiss dialog if it's showing
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
            // Print stack trace for any exceptions
            e.printStackTrace()
        } finally {
            // Ensure timers are nulled even if exception occurs
            morseTimer = null
            countDownTimer = null
        }
    }

    // Set up the navigation drawer and its contents
    private fun setupNavigationDrawer() {
        try {
            // Set up hamburger menu click listener
            menuIcon.setOnClickListener {
                // Toggle drawer open/closed state
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            }

            // Set up user account info
            val currentUser = FirebaseAuth.getInstance().currentUser
            // Display user email or default text
            navHeaderSubtitle.text = currentUser?.email ?: "account."

            // Update biometric button text based on current state
            updateBiometricButtonText()

            // Set up biometric setup button
            biometricSetupButton.setOnClickListener {
                // Close drawer when button is clicked
                drawerLayout.closeDrawer(GravityCompat.START)
                // Start biometric setup process
                setupBiometricLogin(currentUser)
            }

            // Set up logout button
            logoutButton.setOnClickListener {
                // Close drawer when button is clicked
                drawerLayout.closeDrawer(GravityCompat.START)

                // Show confirmation dialog using DialogManager for consistency
                dialogManager.showLogoutConfirmationDialog {
                    try {
                        // Sign out from Firebase when confirmed
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
                            // Log error if MainActivity is not available
                            Log.e("MCFragment", "MainActivity is null or finishing")
                        }
                    } catch (e: Exception) {
                        // Log any errors during logout
                        Log.e("MCFragment", "Error during logout: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            // Log any errors during navigation drawer setup
            Log.e("MCFragment", "Error setting up navigation drawer: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Save generate cooldown end time to SharedPreferences
     */
    private fun saveGenerateCooldownEndTime(endTime: Long) {
        // Save the generate button cooldown end time to SharedPreferences
        sharedPreferences.edit().putLong(GENERATE_COOLDOWN_END_KEY, endTime).apply()
    }

    /**
     * Save current code to SharedPreferences
     */
    private fun saveCurrentCode(code: String) {
        // Save the current code to SharedPreferences
        sharedPreferences.edit().putString(CURRENT_CODE_KEY, code).apply()
    }

    /**
     * Save transmit code (with tag) to SharedPreferences
     */
    private fun saveTransmitCode(code: String) {
        // Save the full transmit code (with tag) to SharedPreferences
        sharedPreferences.edit().putString("transmit_code_key", code).apply()
    }

    /**
     * Save remaining tries to SharedPreferences
     */
    private fun saveRemainingTries(tries: Int) {
        // Save the remaining tries count to SharedPreferences
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

        // Log the loaded tries for debugging
        Log.d("MCFragment", "loadSavedState: Loaded remaining tries: $remainingTries")

        // Load morse cooldown end time
        morseCooldownEndTime = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)

        // Check if we need to resume a cooldown
        val currentTime = System.currentTimeMillis()
        if (generateCooldownEndTime > currentTime) {
            val remainingTime = generateCooldownEndTime - currentTime

            // We'll start the timer when the button is visible in onResume
        }
    }

    /**
     * Resume cooldown timer if needed
     */
    private fun resumeCooldownIfNeeded() {
        // Get current time
        val currentTime = System.currentTimeMillis()

        // Check if generate button cooldown is active
        if (generateCooldownEndTime > currentTime && ::generateCodeButton.isInitialized) {
            // Calculate remaining time
            val remainingTime = generateCooldownEndTime - currentTime

            // Disable button and start timer
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f

            // Cancel any existing timer
            countDownTimer?.cancel()
            // Create and start new timer for remaining time
            countDownTimer = object : CountDownTimer(remainingTime, 1000) {
                // Update every second
                override fun onTick(millisUntilFinished: Long) {
                    // Calculate minutes and seconds for display
                    val minutes = millisUntilFinished / 1000 / 60
                    val seconds = (millisUntilFinished / 1000) % 60
                    // Update button text with remaining time
                    generateCodeButton.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
                }

                // When timer completes
                override fun onFinish() {
                    // Re-enable button and reset appearance
                    generateCodeButton.isEnabled = true
                    generateCodeButton.alpha = 1.0f
                    generateCodeButton.text = "Generate Code"
                    // Reset cooldown end time
                    generateCooldownEndTime = 0

                    // Reset cooldown end time in SharedPreferences
                    saveGenerateCooldownEndTime(0)
                }
            }.start()
        }

        // Check if morse cooldown is active and dialog is showing
        if (morseCooldownEndTime > currentTime && ::dialog.isInitialized && dialog.isShowing) {
            // Calculate remaining time
            val remainingTime = morseCooldownEndTime - currentTime
            Log.d("MCFragment", "resumeCooldownIfNeeded: Resuming morse cooldown with ${remainingTime}ms remaining")

            // Use the common timer function to restart the timer
            startMorseCooldownTimer(remainingTime)
        }
    }

    /**
     * Update the biometric setup button text based on whether biometric is enabled
     */
    private fun updateBiometricButtonText() {
        try {
            // Check if button is initialized
            if (::biometricSetupButton.isInitialized) {
                // Set button text based on current biometric state
                biometricSetupButton.text = if (biometricManager.isBiometricEnabled()) {
                    "Disable Biometric"
                } else {
                    "Set Up Biometric"
                }
            }
        } catch (e: Exception) {
            // Log any errors during button text update
            Log.e("MCFragment", "Error updating biometric button text: ${e.message}")
        }
    }

    /**
     * Set up biometric login functionality
     */
    private fun setupBiometricLogin(currentUser: FirebaseUser?) {
        // Check if biometric is available on the device
        if (!biometricManager.isBiometricAvailable()) {
            // Show toast message if biometric is not available
            Toast.makeText(requireContext(),
                "Biometric authentication is not available on this device",
                Toast.LENGTH_SHORT).show()
            return
        }

        // Get current user email
        val email = currentUser?.email

        // Check if email is available
        if (email == null) {
            // Show toast message if email is null
            Toast.makeText(requireContext(), "Unable to get user email", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if biometric is already enabled
        if (biometricManager.isBiometricEnabled()) {
            // Show dialog to disable biometric if already enabled
            showDisableBiometricDialog()
        } else {
            // Show password confirmation dialog before enabling biometric
            val passwordConfirmationDialog = PasswordConfirmationDialog(requireContext(), dialogManager)
            passwordConfirmationDialog.showPasswordConfirmationDialog(
                email = email,
                biometricManager = biometricManager,
                fragment = this,
                onSuccess = {
                    // Update button text when biometric is successfully enabled
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
        // Build and show confirmation dialog
        AlertDialog.Builder(requireContext())
            .setTitle("Disable Biometric Login")
            .setMessage("Are you sure you want to disable biometric login?")
            .setPositiveButton("Disable") { dialog, _ ->
                // Dismiss dialog when confirmed
                dialog.dismiss()
                // Disable biometric authentication
                biometricManager.disableBiometric()
                // Show success message
                Toast.makeText(requireContext(), "Biometric login disabled", Toast.LENGTH_SHORT).show()
                // Update button text to reflect change
                updateBiometricButtonText()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                // Just dismiss dialog when cancelled
                dialog.dismiss()
            }
            .create()
            .show()
    }

    // Update the style of the code display text based on its state
    private fun updateCodeClickableStyle() {
        // Make the code clickable only if there's a valid code
        val hasValidCode = currentCode.isNotEmpty() && displayCode.isNotEmpty() && remainingTries > 0
        // Always set clickable to allow for feedback when no code is available
        codeDisplayText.isClickable = true

        if (hasValidCode) {
            // Highlight the code when it's valid and clickable - orange color and underline
            codeDisplayText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light))
            codeDisplayText.paint.isUnderlineText = true
        } else {
            // Use normal styling when no valid code - white color, no underline
            codeDisplayText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            codeDisplayText.paint.isUnderlineText = false
        }
    }

    // Generate a new Morse code and show the dialog
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
        // Reset expiration end time to 0
        expirationEndTime = 0
        // Save to SharedPreferences
        sharedPreferences.edit().putLong(EXPIRATION_END_TIME_KEY, 0).apply()
    }

    /**
     * Start the expiration timer with the specified duration
     * This EXACTLY mirrors the logic used in startMorseCooldown for consistent behavior
     */
    private fun startExpirationTimer(duration: Long) {
        // Set the expiration end time in the future
        expirationEndTime = System.currentTimeMillis() + duration
        // Log the expiration time for debugging
        Log.d("MCFragment", "Setting expiration end time to: $expirationEndTime (${duration}ms from now)")

        // Save expiration end time to SharedPreferences
        sharedPreferences.edit().putLong(EXPIRATION_END_TIME_KEY, expirationEndTime).apply()

        // Start the UI timer with the new duration
        startExpirationUITimer(duration)
    }

    /**
     * Starts or restarts the expiration UI timer with the specified duration
     * This EXACTLY mirrors the logic used in startMorseCooldownTimer for consistent behavior
     */
    private fun startExpirationUITimer(duration: Long) {
        // Create a timer that updates the UI countdown
        val expirationTimer = object : CountDownTimer(duration, 1000) {
            // Update UI on each tick
            override fun onTick(millisUntilFinished: Long) {
                // Update UI if dialog is showing
                if (::dialog.isInitialized && dialog.isShowing) {
                    // Find the title text view in the dialog
                    val titleText = dialog.findViewById<TextView>(R.id.dialogTitle)
                    if (titleText != null) {
                        // Calculate seconds and update title
                        val seconds = (millisUntilFinished + 999) / 1000 // Round up
                        titleText.text = "Play Morse Code (${seconds}s)"
                    }
                }
            }

            // When timer finishes, show the expired dialog
            override fun onFinish() {
                // When timer finishes, show the expired dialog with the code
                if (::dialogManager.isInitialized) {
                    // Capture the code before dismissing the dialog
                    val expiredCode = currentCode
                    Log.d("MCFragment", "Timer finished, expired code: $expiredCode")

                    // Dismiss any active dialog
                    dialogManager.dismissActiveDialog()

                    // Only show the dialog if we have a valid code
                    if (expiredCode.isNotEmpty()) {
                        // Show code expired dialog
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
        }.start()

        // We don't store the timer reference because we're using the end time approach
        // for persistence, just like the morse cooldown
        Log.d("MCFragment", "Started expiration UI timer with ${duration}ms remaining")
    }

    /**
     * Check if the expiration timer has expired and handle accordingly
     * @return true if expired, false otherwise
     */
    private fun checkExpirationTimer(): Boolean {
        // Get current time
        val now = System.currentTimeMillis()
        // Check if timer has expired
        if (expirationEndTime > 0 && expirationEndTime <= now) {
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
                        // Show expired code dialog
                        dialogManager.showCodeExpiredDialog(expiredCode) {
                            // Additional reset actions when user confirms
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                        }
                    } catch (e: Exception) {
                        // Log any errors showing dialog
                        Log.e("MCFragment", "Error showing expired code dialog in checkExpirationTimer", e)
                    }
                }
            }
            // Return true to indicate timer has expired
            return true
        }
        // Return false to indicate timer has not expired
        return false
    }

    /**
     * Check if any code has expired and clear it completely from SharedPreferences
     * This is called at the start of onResume to ensure no expired codes are ever displayed
     */
    private fun checkAndClearExpiredCode() {
        // Get current time
        val now = System.currentTimeMillis()
        // Get saved expiration time and code
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        val savedCode = sharedPreferences.getString(MC_DIALOG_CODE_KEY, "") ?: ""

        // If there's an expiration time set and it has passed, show dialog and clear everything
        if (savedExpirationTime > 0 && savedExpirationTime <= now && savedCode.isNotEmpty()) {
            // Log the expired code
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
                        // Show expired code dialog
                        dialogManager.showCodeExpiredDialog(savedCode) {
                            // Additional reset actions when user confirms
                            updateGeneratedCodeText()
                            updateCodeClickableStyle()
                            // No need to clear data again, we already did it above
                        }
                    } catch (e: Exception) {
                        // Log any errors showing dialog
                        Log.e("MCFragment", "Error showing expired code dialog", e)
                    }
                }
            }
        }

        // Also check if there's a code but no expiration time (invalid state)
        if (savedCode.isNotEmpty() && savedExpirationTime <= 0) {
            // Clear all data if in invalid state
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

        // Log that all data was cleared
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
            
            // Log saved dialog state
            Log.d("MCFragment", "Saved dialog state: code=$currentCode, tries=$remainingTries, cooldown=$remainingCooldown")
        }
        
        // Apply changes
        editor.apply()
    }

    /**
     * Public method to save dialog state that can be called from MainActivity
     * This ensures the dialog state is properly saved when the app is minimized
     */
    fun saveDialogStatePublic() {
        saveDialogState()
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
