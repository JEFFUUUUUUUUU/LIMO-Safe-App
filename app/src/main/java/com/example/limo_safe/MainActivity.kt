package com.example.limo_safe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var auth: FirebaseAuth
    private var mainContent: LinearLayout? = null
    private var pressToEnterButton: Button? = null
    private var fragmentContainer: FrameLayout? = null
    
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            
            // Hide action bar
            supportActionBar?.hide()
            
            // Initialize Firebase Auth
            auth = FirebaseAuth.getInstance()
            
            // Setup fragment manager listener
            supportFragmentManager.addOnBackStackChangedListener(this)
            
            // Initialize views safely
            initializeViews()
            
            // Setup listeners
            setupListeners()
            
            // Show main screen
            showMainScreen()
            
            Log.d(TAG, "MainActivity initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupListeners() {
        try {
            pressToEnterButton?.setOnClickListener {
                navigateToLogin()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up listeners: ${e.message}")
        }
    }
    
    private fun initializeViews() {
        try {
            // Initialize views safely
            mainContent = findViewById(R.id.mainContent)
            pressToEnterButton = findViewById(R.id.pressToEnterButton)
            fragmentContainer = findViewById(R.id.fragmentContainer)
            
            // Set initial visibility if views are not null
            fragmentContainer?.visibility = View.GONE
            mainContent?.visibility = View.VISIBLE
            pressToEnterButton?.visibility = View.VISIBLE
            
            Log.d(TAG, "Views initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views: ${e.message}")
            Toast.makeText(this, "Error initializing views: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkInitialState() {
        try {
            // Always show the main screen first with the Press to Enter button
            showMainScreen()
            
            // Clear any existing user session
            clearAllPreferences()
            auth.signOut()
            
            Log.d(TAG, "Initial state checked successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking initial state: ${e.message}")
            showMainScreen()
            Toast.makeText(this, "Error initializing app. Please restart.", Toast.LENGTH_LONG).show()
        }
    }

    fun onLoginSuccessful() {
        try {
            // Make sure views are initialized
            if (mainContent == null || pressToEnterButton == null || fragmentContainer == null) {
                initializeViews()
            }

            // Clear any existing fragments
            if (!isFinishing && !isDestroyed) {
                supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }

            // Update UI visibility
            mainContent?.visibility = View.GONE
            pressToEnterButton?.visibility = View.GONE
            fragmentContainer?.visibility = View.VISIBLE

            // Create and show MC fragment with a small delay to ensure UI is ready
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    try {
                        val mcFragment = MCFragment()
                        supportFragmentManager.beginTransaction()
                            .setCustomAnimations(
                                android.R.anim.fade_in,
                                android.R.anim.fade_out
                            )
                            .replace(R.id.fragmentContainer, mcFragment)
                            .commit()
                        
                        Log.d(TAG, "Navigated to MC Fragment successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error navigating to MC Fragment: ${e.message}")
                        Toast.makeText(this, "Error navigating to LIMO Generator", Toast.LENGTH_SHORT).show()
                        showMainScreen()
                    }
                }
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onLoginSuccessful: ${e.message}")
            Toast.makeText(this, "Error during login", Toast.LENGTH_SHORT).show()
            showMainScreen()
        }
    }

    fun showMainScreen() {
        try {
            // Clear any existing fragments
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            
            // Update UI visibility with null safety
            mainContent?.visibility = View.VISIBLE
            pressToEnterButton?.visibility = View.VISIBLE
            fragmentContainer?.visibility = View.GONE
            
            Log.d(TAG, "Main screen shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing main screen: ${e.message}")
            // Don't finish the activity, just show a toast
            Toast.makeText(this, "Error showing main screen", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearBackStackAndNavigateToLogin() {
        try {
            // Sign out from Firebase first
            auth.signOut()
            
            // Clear preferences
            clearAllPreferences()
            
            // Make sure views are initialized
            if (mainContent == null || pressToEnterButton == null || fragmentContainer == null) {
                initializeViews()
            }
            
            // Immediately update UI visibility to prevent flashing
            mainContent?.visibility = View.GONE
            pressToEnterButton?.visibility = View.GONE
            fragmentContainer?.visibility = View.VISIBLE
            
            // Clear any existing fragments first
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            
            // Create and show login fragment directly without delay
            val loginFragment = LoginFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, loginFragment)
                .commitNow() // Use commitNow for immediate execution
            
            Log.d(TAG, "Cleared back stack and navigated to login")
        } catch (e: Exception) {
            Log.e(TAG, "Error in logout navigation: ${e.message}")
            
            // Last resort fallback - try a completely different approach
            try {
                // Create a new intent to restart the activity cleanly
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                
                // Sign out again to be sure
                auth.signOut()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed fallback navigation: ${e2.message}")
            }
        }
    }

    private fun clearAllPreferences() {
        try {
            getSharedPreferences("MorseCodePrefs", Context.MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("LIMOSafePrefs", Context.MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("UserPrefs", Context.MODE_PRIVATE).edit().clear().apply()
            Log.d(TAG, "Preferences cleared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing preferences: ${e.message}")
        }
    }

    fun navigateToLogin() {
        try {
            // Make sure views are initialized
            if (mainContent == null || pressToEnterButton == null || fragmentContainer == null) {
                initializeViews()
            }
            
            // Create a smooth transition animation
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Update UI visibility
                    mainContent?.visibility = View.GONE
                    pressToEnterButton?.visibility = View.GONE
                    fragmentContainer?.visibility = View.VISIBLE
                    
                    // Create and show login fragment with maroon and orange theme
                    val loginFragment = LoginFragment()
                    supportFragmentManager.beginTransaction()
                        .setCustomAnimations(
                            android.R.anim.fade_in,
                            android.R.anim.fade_out
                        )
                        .replace(R.id.fragmentContainer, loginFragment)
                        .commit()
                    
                    Log.d(TAG, "Navigated to login successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed navigation: ${e.message}")
                    showMainScreen()
                }
            }, 300) // Short delay for smooth transition
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to login: ${e.message}")
            Toast.makeText(this, "Error navigating to login", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
            showMainScreen()
        }
    }

    fun navigateToMonitoring() {
        try {
            // Make sure views are initialized
            if (mainContent == null || pressToEnterButton == null || fragmentContainer == null) {
                initializeViews()
            }
            
            // Update UI visibility
            mainContent?.visibility = View.GONE
            pressToEnterButton?.visibility = View.GONE
            fragmentContainer?.visibility = View.VISIBLE
            
            // Create and show monitoring fragment
            val monitoringFragment = MonitoringFragment()
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
                .replace(R.id.fragmentContainer, monitoringFragment)
                .addToBackStack(null)
                .commit()
                
            Log.d(TAG, "Navigated to monitoring successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to monitoring: ${e.message}")
            Toast.makeText(this, "Error navigating to monitoring", Toast.LENGTH_SHORT).show()
            showMainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        // Only check initial state when first launching the app
        if (supportFragmentManager.backStackEntryCount == 0) {
            checkInitialState()
        }
    }

    override fun onPause() {
        super.onPause()
        // Save any necessary state
    }

    override fun onDestroy() {
        try {
            supportFragmentManager.removeOnBackStackChangedListener(this)
            clearAllPreferences()
        } finally {
            super.onDestroy()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try {
            // Get current fragment
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            
            when (currentFragment) {
                is MonitoringFragment -> {
                    // Pop back to MC Fragment
                    supportFragmentManager.popBackStack()
                }
                is MCFragment -> {
                    // Show exit confirmation
                    showExitConfirmation()
                }
                is LoginFragment -> {
                    // Return to main screen
                    showMainScreen()
                }
                else -> super.onBackPressed()
            }
        } catch (e: Exception) {
            super.onBackPressed()
        }
    }

    private fun showExitConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit Confirmation")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ ->
                showMainScreen()
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onBackStackChanged() {
        try {
            // Get current fragment
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)

            // Update UI based on current fragment
            when (currentFragment) {
                null -> {
                    // No fragments - show main screen
                    mainContent?.visibility = View.VISIBLE
                    pressToEnterButton?.visibility = View.VISIBLE
                    fragmentContainer?.visibility = View.GONE
                }
                is LoginFragment, is MCFragment, is MonitoringFragment -> {
                    // Any app fragment - hide main screen
                    mainContent?.visibility = View.GONE
                    pressToEnterButton?.visibility = View.GONE
                    fragmentContainer?.visibility = View.VISIBLE
                }
                else -> {
                    // Unknown fragment - show main screen
                    showMainScreen()
                }
            }
            
            Log.d(TAG, "Back stack changed, updated UI")

            // Ensure all fragment transactions are complete
            try {
                supportFragmentManager.executePendingTransactions()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing pending transactions: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onBackStackChanged: ${e.message}")
        }
    }
}