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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlin.concurrent.thread
import android.content.DialogInterface
import android.content.Context
import android.content.SharedPreferences
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class MCFragment : Fragment() {
    private lateinit var morseCodeHelper: MorseCodeHelper
    private var generateCooldownEndTime: Long = 0
    private var morseCooldownEndTime: Long = 0
    private var lastMorsePlayTime: Long = 0
    private var currentCode = ""
    private var remainingTries = 3
    private val MORSE_COOLDOWN: Long = 15000 // 15 seconds
    private val GENERATE_COOLDOWN: Long = 60000 // 1 minute
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize SharedPreferences
        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Handle back press
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (generateCooldownEndTime <= currentTime && remainingTries > 0) {
                    if (::dialog.isInitialized && dialog.isShowing) {
                        dialog.dismiss()
                    }
                    if (parentFragmentManager.backStackEntryCount > 0) {
                        parentFragmentManager.popBackStack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
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
        MorseCodeHelper.initialize(requireContext())
        
        // Initialize DialogManager and BiometricManager
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
            
            // Make the codeDisplayText clickable if tries remain
            codeDisplayText.setOnClickListener {
                if (remainingTries > 0) {
                    // Only allow reopening if dialog is not already showing
                    if (!::dialog.isInitialized || !dialog.isShowing) {
                        // Restore dialog state from preferences if needed
                        val code = sharedPreferences.getString(MC_DIALOG_CODE_KEY, currentCode)
                        val tries = sharedPreferences.getInt(MC_DIALOG_TRIES_KEY, remainingTries)
                        currentCode = code ?: ""
                        remainingTries = tries
                        if (currentCode.isNotEmpty() && remainingTries > 0) {
                            // Always restore the correct cooldown (do not reset)
                            showMorseCodeDialog(currentCode)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "No remaining tries for this code.", Toast.LENGTH_SHORT).show()
                }
            }
            // Optionally add a visual cue for clickability if tries remain
            updateCodeClickableStyle()
        } catch (e: Exception) {
            Log.e("MCFragment", "Error initializing UI: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // Restore Morse Code dialog if it was open before minimize and not currently showing
            val wasDialogOpen = sharedPreferences.getBoolean(MC_DIALOG_OPEN_KEY, false)
            if (wasDialogOpen && (!::dialog.isInitialized || !dialog.isShowing)) {
                val code = sharedPreferences.getString(MC_DIALOG_CODE_KEY, "") ?: ""
                val tries = sharedPreferences.getInt(MC_DIALOG_TRIES_KEY, 3)
                val cooldown = sharedPreferences.getLong(MC_DIALOG_COOLDOWN_KEY, 0)
                remainingTries = tries
                currentCode = code
                if (code.isNotEmpty() && tries > 0) {
                    showMorseCodeDialog(code, cooldown)
                }
            }
            // Reset UI state
            resetGenerateButton()
            // Resume cooldown timer if needed
            resumeCooldownIfNeeded()
            // Update UI with current code
            if (::generatedCodeText.isInitialized && ::codeDisplayText.isInitialized) {
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
                    if (drawerLayout.isDrawerOpen(android.view.Gravity.START)) {
                        drawerLayout.closeDrawer(android.view.Gravity.START)
                    } else {
                        drawerLayout.openDrawer(android.view.Gravity.START)
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
                    drawerLayout.closeDrawer(android.view.Gravity.START)
                    
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
        generateCodeButton.isEnabled = false
        generateCodeButton.alpha = 0.5f

        generateCooldownEndTime = System.currentTimeMillis() + duration
        
        // Save cooldown end time to SharedPreferences
        saveGenerateCooldownEndTime(generateCooldownEndTime)

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(duration, 1000) {
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
                
                // Reset cooldown end time in SharedPreferences
                saveGenerateCooldownEndTime(0)
            }
        }.start()
    }

    private fun updateGeneratedCodeText() {
        generatedCodeText.text = "Generated Code: "
        codeDisplayText.text = if (currentCode.isEmpty()) "-------" else currentCode
        
        // Save current code to SharedPreferences
        saveCurrentCode(currentCode)
        updateCodeClickableStyle()
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
        val mainActivity = requireActivity() as MainActivity
        mainActivity.clearBackStackAndNavigateToLogin()
    }

    private fun navigateToMonitoring() {
        try {
            if (isAdded && activity != null && !requireActivity().isFinishing) {
                // First, remove all ongoing timers and dialogs
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
                Log.d("MCFragment", "Navigated to monitoring fragment")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Exit Confirmation")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                // Exit the app completely
                requireActivity().finishAffinity()
                System.exit(0)
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun showMorseCodeDialog(code: String, cooldown: Long = 0) {
        val now = System.currentTimeMillis()
        val cooldownEnd = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
        val remainingCooldown = if (cooldownEnd > now) cooldownEnd - now else 0L

        if (!::dialog.isInitialized || !dialog.isShowing) {
            val tries = sharedPreferences.getInt(MC_DIALOG_TRIES_KEY, remainingTries)
            remainingTries = tries
        }

        // Only show max tries dialog if remainingTries is 0 or less and the user is trying to PLAY, not when generating a new code
        if (remainingTries <= 0 && code.isNotEmpty()) {
            dialogManager.showMaxTriesDialog()
            currentCode = ""
            saveCurrentCode("")
            saveRemainingTries(3)
            return
        }

        dialog = dialogManager.createMorseCodeDialog(
            code,
            remainingTries,
            remainingCooldown
        ) { playButton, cooldownText ->
            playMorseCode(code)
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
        }
        val playButton = dialog.findViewById<Button>(R.id.playButton)
        val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
        if (playButton != null && cooldownText != null && remainingCooldown > 0) {
            playButton.isEnabled = false
            playButton.alpha = 0.5f
            morseTimer?.cancel()
            morseTimer = object : CountDownTimer(remainingCooldown, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = millisUntilFinished / 1000
                    cooldownText.text = "Please wait $seconds seconds before next try"
                    playButton.text = "PLAY MORSE CODE ($seconds)"
                }

                override fun onFinish() {
                    cooldownText.text = "Ready to play"
                    if (remainingTries > 0) {
                        playButton.isEnabled = true
                        playButton.alpha = 1.0f
                    }
                    playButton.text = "PLAY MORSE CODE"
                    lastMorsePlayTime = 0
                    morseCooldownEndTime = 0
                    sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0).apply()
                }
            }.start()
        }
        dialog.show()
    }

    private fun showMorseCodeDialogForce(code: String, cooldown: Long = 0) {
        // For new code generation, always start with NO cooldown
        val remainingCooldown = 0L

        dialog = dialogManager.createMorseCodeDialog(
            code,
            remainingTries,
            remainingCooldown
        ) { playButton, cooldownText ->
            playMorseCode(code)
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
        }
        val playButton = dialog.findViewById<Button>(R.id.playButton)
        val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
        // No cooldown for new code, so enable play immediately
        if (playButton != null && cooldownText != null) {
            playButton.isEnabled = true
            playButton.alpha = 1.0f
            cooldownText.text = "Ready to play"
            playButton.text = "PLAY MORSE CODE"
        }
        dialog.show()
    }

    private fun startMorseCooldown(duration: Long) {
        morseTimer?.cancel()

        val currentDialog = dialog
        val playButton = currentDialog.findViewById<Button>(R.id.playButton)
        val cooldownText = currentDialog.findViewById<TextView>(R.id.cooldownText)

        if (playButton != null && cooldownText != null) {
            playButton.isEnabled = false
            playButton.alpha = 0.5f

            morseCooldownEndTime = System.currentTimeMillis() + duration
            
            // Save cooldown end time to SharedPreferences
            sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, morseCooldownEndTime).apply()

            morseTimer = object : CountDownTimer(duration, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = millisUntilFinished / 1000
                    cooldownText.text = "Please wait $seconds seconds before next try"
                    playButton.text = "PLAY MORSE CODE ($seconds)"
                }

                override fun onFinish() {
                    cooldownText.text = "Ready to play"
                    if (remainingTries > 0) {
                        playButton.isEnabled = true
                        playButton.alpha = 1.0f
                    }
                    playButton.text = "PLAY MORSE CODE"
                    lastMorsePlayTime = 0
                    morseCooldownEndTime = 0
                    
                    // Reset cooldown end time in SharedPreferences
                    sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0).apply()
                }
            }.start()
        }
    }

    private fun playMorseCode(code: String) {
        thread {
            try {
                MorseCodeHelper.playMorseCode(code)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun flashlightOn() {
        try {
            MorseCodeHelper.flashlightOn()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun flashlightOff() {
        try {
            MorseCodeHelper.flashlightOff()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun textToMorse(text: String): String {
        return try {
            MorseCodeHelper.textToMorse(text)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
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

    private fun initializeViews(view: View) {
        generateCodeButton = view.findViewById(R.id.generateCodeButton)
        checkMonitoringButton = view.findViewById(R.id.checkMonitoringButton)
        exitButton = view.findViewById(R.id.exitButton)
        generatedCodeText = view.findViewById(R.id.generatedCodeText)
        codeDisplayText = view.findViewById(R.id.codeDisplayText)
        cooldownText = view.findViewById(R.id.cooldownText)
    }

    override fun onPause() {
        super.onPause()
        try {
            // Cancel any running timers
            morseTimer?.cancel()
            countDownTimer?.cancel()
            
            // Save dialog state if open (preserve even if dialog is showing)
            if (::dialog.isInitialized && dialog.isShowing && currentCode.isNotEmpty() && remainingTries > 0) {
                sharedPreferences.edit()
                    .putBoolean(MC_DIALOG_OPEN_KEY, true)
                    .putString(MC_DIALOG_CODE_KEY, currentCode)
                    .putInt(MC_DIALOG_TRIES_KEY, remainingTries)
                    .putLong(MC_DIALOG_COOLDOWN_KEY, if (morseCooldownEndTime > System.currentTimeMillis()) morseCooldownEndTime - System.currentTimeMillis() else 0L)
                    .apply()
            } else {
                sharedPreferences.edit().putBoolean(MC_DIALOG_OPEN_KEY, false).apply()
            }
            // Do NOT dismiss dialog here; allow system to restore
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        try {
            // Cancel timers
            morseTimer?.cancel()
            countDownTimer?.cancel()
            
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
            MorseCodeHelper.cleanup()
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
            morseTimer?.cancel()
            countDownTimer?.cancel()
            
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
            MorseCodeHelper.cleanup()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Ensure timers are nulled
            morseTimer = null
            countDownTimer = null
            super.onDestroy()
        }
    }

    private fun handleOnBackPressed() {
        showExitConfirmationDialog()
    }

    private fun cleanup() {
        try {
            // Cancel timers
            morseTimer?.cancel()
            countDownTimer?.cancel()
            
            // Dismiss dialog
            if (::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }
            
            // Clear state
            resetGenerateButton()
            lastMorsePlayTime = 0
            morseCooldownEndTime = 0
            generateCooldownEndTime = 0
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            morseTimer = null
            countDownTimer = null
        }
    }

    private fun setupNavigationDrawer() {
        try {
            // Set up hamburger menu click listener
            menuIcon.setOnClickListener {
                if (drawerLayout.isDrawerOpen(android.view.Gravity.START)) {
                    drawerLayout.closeDrawer(android.view.Gravity.START)
                } else {
                    drawerLayout.openDrawer(android.view.Gravity.START)
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
        
        // Load current code
        currentCode = sharedPreferences.getString(CURRENT_CODE_KEY, "") ?: ""
        
        // Load remaining tries
        remainingTries = sharedPreferences.getInt(REMAINING_TRIES_KEY, 3)
        
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
        val currentTime = System.currentTimeMillis()
        
        // Check if generate button cooldown is active
        if (generateCooldownEndTime > currentTime && ::generateCodeButton.isInitialized) {
            val remainingTime = generateCooldownEndTime - currentTime
            
            // Disable button and start timer
            generateCodeButton.isEnabled = false
            generateCodeButton.alpha = 0.5f
            
            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(remainingTime, 1000) {
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
                    
                    // Reset cooldown end time in SharedPreferences
                    saveGenerateCooldownEndTime(0)
                }
            }.start()
        }
        
        // Check if morse cooldown is active
        if (morseCooldownEndTime > currentTime && ::dialog.isInitialized && dialog.isShowing) {
            val remainingTime = morseCooldownEndTime - currentTime
            
            // Disable play button and start timer
            val playButton = dialog.findViewById<Button>(R.id.playButton)
            val cooldownText = dialog.findViewById<TextView>(R.id.cooldownText)
            if (playButton != null && cooldownText != null) {
                playButton.isEnabled = false
                playButton.alpha = 0.5f
                
                morseTimer?.cancel()
                morseTimer = object : CountDownTimer(remainingTime, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val seconds = millisUntilFinished / 1000
                        cooldownText.text = "Please wait $seconds seconds before next try"
                    }
                    
                    override fun onFinish() {
                        cooldownText.text = "Ready to play"
                        if (remainingTries > 0) {
                            playButton.isEnabled = true
                            playButton.alpha = 1.0f
                        }
                        lastMorsePlayTime = 0
                        morseCooldownEndTime = 0
                        
                        // Reset cooldown end time in SharedPreferences
                        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0).apply()
                    }
                }.start()
            }
        }
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
        if (remainingTries > 0) {
            codeDisplayText.isClickable = true
            codeDisplayText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light))
            codeDisplayText.paint.isUnderlineText = true
        } else {
            codeDisplayText.isClickable = false
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
        // Reset cooldown for new code
        lastMorsePlayTime = 0L
        morseCooldownEndTime = 0L
        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0L).apply()
        // Show the play Morse code dialog with 3 tries (force skip max tries logic)
        showMorseCodeDialogForce(currentCode)
    }
    
    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
    }
}