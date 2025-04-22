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
import com.example.limo_safe.utils.AppFlags

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var auth: FirebaseAuth
    private var mainContent: LinearLayout? = null
    private var fragmentContainer: FrameLayout? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    // --- BEGIN: State Persistence for Fragment & Dialog ---
    private val NAV_STATE_PREFS = "NavStatePrefs"
    private val KEY_LAST_FRAGMENT = "last_fragment"
    private val KEY_DIALOG_STATE = "dialog_state"

    private fun saveNavigationState() {
        try {
            val prefs = getSharedPreferences(NAV_STATE_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            // Save current fragment
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            val fragmentTag = when (currentFragment) {
                is MCFragment -> "MCFragment"
                is MonitoringFragment -> "MonitoringFragment"
                is LoginFragment -> "LoginFragment"
                is SignUpFragment -> "SignUpFragment"
                is ForgotPasswordFragment -> "ForgotPasswordFragment"
                else -> ""
            }
            editor.putString(KEY_LAST_FRAGMENT, fragmentTag)

            // Save dialog state if needed (handled by fragments/dialog manager)
            // Optionally, you can trigger the fragment to save its dialog state here if needed
            // For now, just clear dialog state (dialog state is handled in fragment SharedPreferences)
            editor.remove(KEY_DIALOG_STATE)

            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving navigation state: ${e.message}")
        }
    }

    private fun restoreNavigationState() {
        try {
            val prefs = getSharedPreferences(NAV_STATE_PREFS, Context.MODE_PRIVATE)
            val lastFragment = prefs.getString(KEY_LAST_FRAGMENT, "") ?: ""

            // Check if user is logged in
            val currentUser = auth.currentUser

            if (lastFragment.isNotEmpty()) {
                when (lastFragment) {
                    // For protected screens, only restore if user is logged in
                    "MCFragment", "MonitoringFragment" -> {
                        if (currentUser != null) {
                            // User is logged in, restore the fragment
                            val fragment = if (lastFragment == "MCFragment") MCFragment() else MonitoringFragment()
                            if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) !is MCFragment &&
                                supportFragmentManager.findFragmentById(R.id.fragmentContainer) !is MonitoringFragment) {
                                supportFragmentManager.beginTransaction()
                                    .replace(R.id.fragmentContainer, fragment)
                                    .commitAllowingStateLoss()
                            }
                        } else {
                            // User is not logged in, navigate to login
                            Log.d(TAG, "User not logged in, redirecting to login instead of $lastFragment")
                            // Clear saved navigation state
                            prefs.edit().remove(KEY_LAST_FRAGMENT).apply()
                            navigateToLogin()
                        }
                    }
                    // Login-related screens can be restored regardless of login state
                    "LoginFragment" -> if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) !is LoginFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, LoginFragment())
                            .commitAllowingStateLoss()
                    }
                    "SignUpFragment" -> if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) !is SignUpFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, SignUpFragment())
                            .commitAllowingStateLoss()
                    }
                    "ForgotPasswordFragment" -> if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) !is ForgotPasswordFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, ForgotPasswordFragment())
                            .commitAllowingStateLoss()
                    }
                }
            } else if (currentUser == null) {
                // No saved fragment and no logged in user, ensure we're at login
                navigateToLogin()
            }
            // Dialog state is handled by each fragment (see MCFragment, MonitoringFragment)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring navigation state: ${e.message}")
            // On error, default to login
            navigateToLogin()
        }
    }
    // --- END: State Persistence for Fragment & Dialog ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()

        // Initialize views
        initializeViews()

        // Add back stack change listener
        supportFragmentManager.addOnBackStackChangedListener(this)

        // Check if this is a fresh start (not a configuration change)
        if (savedInstanceState == null) {
            // This is a fresh start, ensure user is logged out
            auth.signOut()
            clearAllPreferences()
            // Clear navigation state
            getSharedPreferences(NAV_STATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            Log.d(TAG, "Fresh app start: User logged out and navigation state cleared")
        }

        // Ensure biometric authentication is disabled during splash
        AppFlags.allowBiometricAuthentication = false

        // Make sure splash screen is visible
        mainContent?.visibility = View.VISIBLE
        fragmentContainer?.visibility = View.GONE

        // Show splash for 3 seconds, then go to login
        Handler(Looper.getMainLooper()).postDelayed({
            // Enable biometric authentication only after splash is complete
            AppFlags.allowBiometricAuthentication = true
            navigateToLogin()
        }, 3000)

        // Ensure proper visibility at startup
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (currentFragment == null) {
            mainContent?.visibility = View.VISIBLE
            fragmentContainer?.visibility = View.GONE
        } else {
            mainContent?.visibility = View.GONE
            fragmentContainer?.visibility = View.VISIBLE
        }
    }

    private fun initializeViews() {
        mainContent = findViewById(R.id.mainContent)
        fragmentContainer = findViewById(R.id.fragmentContainer)

        // Ensure splash/main screen is visible, hide fragment container by default
        if (mainContent != null) {
            mainContent?.visibility = View.VISIBLE
            fragmentContainer?.visibility = View.GONE
        } else {
            Log.e(TAG, "Failed to initialize mainContent view")
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
            if (mainContent == null || fragmentContainer == null) {
                initializeViews()
            }

            // Clear any existing fragments
            if (!isFinishing && !isDestroyed) {
                supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }

            // Update UI visibility
            mainContent?.visibility = View.GONE
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
            // Clear back stack
            val fragmentManager = supportFragmentManager
            val backStackCount = fragmentManager.backStackEntryCount
            for (i in 0 until backStackCount) {
                fragmentManager.popBackStack()
            }

            // Remove any fragments in the container
            val currentFragment = fragmentManager.findFragmentById(R.id.fragmentContainer)
            if (currentFragment != null) {
                fragmentManager.beginTransaction()
                    .remove(currentFragment)
                    .commit()
                fragmentManager.executePendingTransactions()
            }

            // Show main content, hide fragment container
            mainContent?.visibility = View.VISIBLE
            fragmentContainer?.visibility = View.GONE

            Log.d(TAG, "Showing main screen")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing main screen: ${e.message}")
        }
    }

    fun clearBackStackAndNavigateToLogin() {
        try {
            // Sign out from Firebase first
            auth.signOut()

            // Clear preferences
            clearAllPreferences()

            // Make sure views are initialized
            if (mainContent == null || fragmentContainer == null) {
                initializeViews()
            }

            // Immediately update UI visibility to prevent flashing
            mainContent?.visibility = View.GONE
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
            Log.d(TAG, "Navigating to login")

            // Clear any existing fragments from the back stack
            val fragmentManager = supportFragmentManager
            val backStackCount = fragmentManager.backStackEntryCount
            for (i in 0 until backStackCount) {
                fragmentManager.popBackStack()
            }

            // Make sure fragment container is visible and main content is hidden
            fragmentContainer?.visibility = View.VISIBLE
            mainContent?.visibility = View.GONE

            // Create and show login fragment
            val loginFragment = LoginFragment.newInstance()
            fragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, loginFragment)
                .commit()

            // Execute pending transactions to ensure fragment is added immediately
            fragmentManager.executePendingTransactions()

            Log.d(TAG, "Navigated to login successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to login: ${e.message}")
            Toast.makeText(this, "Error navigating to login", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
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

            // Ensure user is logged out when app is destroyed
            auth.signOut()
            clearAllPreferences()

            // Clear navigation state to prevent auto-redirect on app restart
            getSharedPreferences(NAV_STATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()

            Log.d(TAG, "App destroyed, user logged out and navigation state cleared")
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
                // Log out user completely
                auth.signOut()

                // Clear all preferences
                clearAllPreferences()

                // Clear navigation state to prevent auto-redirect on app restart
                getSharedPreferences(NAV_STATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()

                // Actually exit the app
                finishAndRemoveTask()
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
                    fragmentContainer?.visibility = View.GONE
                }
                is LoginFragment, is MCFragment, is MonitoringFragment, is SignUpFragment, is ForgotPasswordFragment -> {
                    // Any app fragment - hide main screen
                    mainContent?.visibility = View.GONE
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

    fun navigateToSignUp() {
        try {
            Log.d(TAG, "Navigating to SignUpFragment")

            // Make sure fragment container is visible
            fragmentContainer?.visibility = View.VISIBLE
            mainContent?.visibility = View.GONE

            // Create and show sign up fragment
            val signUpFragment = SignUpFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, signUpFragment)
                .addToBackStack("to_signup")
                .commit()

            Log.d(TAG, "Navigated to SignUpFragment successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to SignUpFragment: ${e.message}")
            Toast.makeText(this, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun navigateToForgotPassword() {
        try {
            Log.d(TAG, "Navigating to ForgotPasswordFragment")

            // Make sure fragment container is visible
            fragmentContainer?.visibility = View.VISIBLE
            mainContent?.visibility = View.GONE

            // Create and show forgot password fragment
            val forgotPasswordFragment = ForgotPasswordFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, forgotPasswordFragment)
                .addToBackStack("to_forgot_password")
                .commit()

            Log.d(TAG, "Navigated to ForgotPasswordFragment successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to ForgotPasswordFragment: ${e.message}")
            Toast.makeText(this, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}