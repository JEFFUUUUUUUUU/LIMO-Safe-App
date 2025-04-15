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
import com.google.firebase.auth.FirebaseAuth
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
    private lateinit var accountTextView: TextView
    private lateinit var logoutButton: Button

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
        
        // Initialize DialogManager
        dialogManager = DialogManager(requireContext())
        
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
            accountTextView = navHeader.findViewById(R.id.accountTextView)
            logoutButton = navHeader.findViewById(R.id.logoutButton)
            
            // Set up user account info
            val currentUser = FirebaseAuth.getInstance().currentUser
            accountTextView.text = currentUser?.email ?: "account."
            
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
                            mainActivity.findViewById<View>(R.id.pressToEnterButton)?.visibility = View.GONE
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
            accountTextView = view.findViewById(R.id.accountTextView)
            logoutButton = view.findViewById(R.id.logoutButton)
            
            // Ensure all views are properly initialized
            if (generateCodeButton == null || checkMonitoringButton == null || 
                exitButton == null || generatedCodeText == null || 
                codeDisplayText == null || cooldownText == null || 
                drawerLayout == null || menuIcon == null || 
                accountTextView == null || logoutButton == null) {
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
        } catch (e: Exception) {
            Log.e("MCFragment", "Error initializing UI: ${e.message}")
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
                        return@setOnClickListener
                    }

                    // Generate new code only when button is clicked
                    val newCode = generateRandomCode()
                    remainingTries = 3
                    currentCode = newCode
                    lastMorsePlayTime = 0 // Reset morse cooldown when generating new code
                    updateGeneratedCodeText()

                    // Show the dialog with the new code
                    showMorseCodeDialog(newCode)

                    // Start the cooldown
                    startGenerateButtonCooldown()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Error generating code: ${e.message}", Toast.LENGTH_SHORT).show()
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
                FirebaseAuth.getInstance().signOut()
                navigateToLogin()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun showMorseCodeDialog(code: String, remainingCooldown: Long = 0) {
        try {
            val builder = AlertDialog.Builder(requireContext())
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_morse_code, null)
            
            // Initialize dialog views
            val codeTextView = dialogView.findViewById<TextView>(R.id.codeDisplayText)
            val triesText = dialogView.findViewById<TextView>(R.id.triesText)
            val playButton = dialogView.findViewById<Button>(R.id.playButton)
            val cooldownText = dialogView.findViewById<TextView>(R.id.cooldownText)
            
            codeTextView.text = "Code: $code"
            triesText.text = "Remaining tries: $remainingTries"
            
            // Set up play button
            val currentTime = System.currentTimeMillis()
            if (remainingCooldown > 0) {
                startMorseCooldown(remainingCooldown)
                playButton.isEnabled = false
                playButton.alpha = 0.5f
            }
            
            playButton.setOnClickListener {
                if (!checkCameraPermission()) {
                    return@setOnClickListener
                }
                
                playButton.isEnabled = false
                playButton.alpha = 0.5f
                
                val currentTime = System.currentTimeMillis()
                playMorseCode(code)
                remainingTries--
                triesText.text = "Remaining tries: $remainingTries"

                if (remainingTries > 0) {
                    lastMorsePlayTime = currentTime
                    startMorseCooldown(MORSE_COOLDOWN)
                } else {
                    // Close the current dialog
                    dialog.dismiss()
                    
                    // Show maximum tries reached dialog
                    dialogManager.showMaxTriesDialog()
                }
            }
            
            builder.setView(dialogView)
                .setTitle("Morse Code")
                .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
            
            dialog = builder.create()
            dialog.show()
        } catch (e: Exception) {
            Log.e("MCFragment", "Error showing dialog: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startMorseCooldown(duration: Long) {
        morseTimer?.cancel()

        val currentDialog = dialog
        val playButton = currentDialog.findViewById<Button>(R.id.playButton)
        val cooldownText = currentDialog.findViewById<TextView>(R.id.cooldownText)

        playButton?.isEnabled = false
        playButton?.alpha = 0.5f

        morseCooldownEndTime = System.currentTimeMillis() + duration

        morseTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                cooldownText?.text = "Please wait $seconds seconds before next try"
            }

            override fun onFinish() {
                cooldownText?.text = "Ready to play"
                playButton?.isEnabled = true
                playButton?.alpha = 1.0f
                lastMorsePlayTime = 0
                morseCooldownEndTime = 0
            }
        }.start()
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
            
            // Dismiss dialog if showing
            if (::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
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
            accountTextView.text = currentUser?.email ?: "account."
            
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
                            mainActivity.findViewById<View>(R.id.pressToEnterButton)?.visibility = View.GONE
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
        
        // Check if we need to resume a cooldown
        val currentTime = System.currentTimeMillis()
        if (generateCooldownEndTime > currentTime) {
            val remainingTime = generateCooldownEndTime - currentTime
            Log.d("MCFragment", "Resuming cooldown with ${remainingTime}ms remaining")
            
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
    }
    

    
    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
    }
}