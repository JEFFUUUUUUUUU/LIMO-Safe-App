package com.example.limo_safe

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.google.firebase.auth.FirebaseAuth
import com.example.limo_safe.Object.SessionManager

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var auth: FirebaseAuth
    lateinit var sessionManager: SessionManager
    private lateinit var mainContent: LinearLayout
    private lateinit var pressToEnterButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        supportFragmentManager.addOnBackStackChangedListener(this)

        mainContent = findViewById(R.id.mainContent)
        pressToEnterButton = findViewById(R.id.pressToEnterButton)

        // Initialize session manager
        sessionManager = SessionManager(this) {
            // On session timeout/logout
            runOnUiThread {
                auth.signOut()
                clearAllPreferences()
                showMainScreen()
            }
        }

        // Set up click listener for the enter button
        pressToEnterButton.setOnClickListener {
            sessionManager.userActivityDetected()
            navigateToLogin()
        }

        // Set up touch listener for the entire activity
        window.decorView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                sessionManager.userActivityDetected()
            }
            false
        }

        // Ensure fragment container exists and is initially GONE
        findViewById<View>(R.id.fragmentContainer).visibility = View.GONE

        // Check initial state
        if (savedInstanceState == null) {
            checkInitialState()
        }
    }

    private fun checkInitialState() {
        // On fresh app open, always show main screen first
        showMainScreen()

        // Check if we have a valid session
        if (!sessionManager.isLoggedIn()) {
            clearAllPreferences()
            auth.signOut()
            return
        }

        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            // User is logged in and verified, go to MC
            mainContent.visibility = View.GONE
            sessionManager.onLoginSuccess()
            onLoginSuccessful()
        } else {
            // Email not verified or no user
            clearAllPreferences()
            auth.signOut()
        }
    }

    fun onLoginSuccessful() {
        // Clear any existing fragments first
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // Initialize session
        sessionManager.onLoginSuccess()

        // Hide main content
        mainContent.visibility = View.GONE
        pressToEnterButton.visibility = View.GONE

        // Show fragment container
        findViewById<View>(R.id.fragmentContainer).visibility = View.VISIBLE

        // Create and show MC fragment
        val mcFragment = MCFragment()
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragmentContainer, mcFragment)
            .commitNow()

        // Post-transaction cleanup
        supportFragmentManager.executePendingTransactions()
    }

    private fun showMainScreen() {
        // Clear the entire back stack
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        
        // Show main content with enter button
        mainContent.visibility = View.VISIBLE
        pressToEnterButton.visibility = View.VISIBLE
        
        // Hide fragment container
        val fragmentContainer = findViewById<View>(R.id.fragmentContainer)
        fragmentContainer.visibility = View.GONE
    }

    fun clearBackStackAndNavigateToLogin() {
        runOnUiThread {
            // Clear the entire back stack
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

            // Hide main content
            mainContent.visibility = View.GONE
            pressToEnterButton.visibility = View.GONE

            // Show and setup fragment container
            val fragmentContainer = findViewById<View>(R.id.fragmentContainer)
            fragmentContainer.visibility = View.VISIBLE

            // Navigate to login
            val loginFragment = LoginFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
                .replace(R.id.fragmentContainer, loginFragment)
                .commitNow()

            // Clear all preferences and sign out
            auth.signOut()
            clearAllPreferences()
        }
    }

    private fun clearAllPreferences() {
        getSharedPreferences("MorseCodePrefs", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("LIMOSafePrefs", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("UserPrefs", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("SessionPrefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun navigateToLogin() {
        mainContent.visibility = View.GONE
        val loginFragment = LoginFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commitAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()
        sessionManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        sessionManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        supportFragmentManager.removeOnBackStackChangedListener(this)
        sessionManager.onPause() // Treat destroy as a pause to detect force-close
    }

    override fun onBackStackChanged() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        val fragmentContainer = findViewById<View>(R.id.fragmentContainer)
        
        when {
            currentFragment == null -> {
                // No fragments - show main screen
                mainContent.visibility = View.VISIBLE
                pressToEnterButton.visibility = View.VISIBLE
                fragmentContainer.visibility = View.GONE
            }
            currentFragment is MCFragment -> {
                // MC Fragment - hide main screen
                mainContent.visibility = View.GONE
                pressToEnterButton.visibility = View.GONE
                fragmentContainer.visibility = View.VISIBLE
            }
            else -> {
                // Other fragments - hide main screen
                mainContent.visibility = View.GONE
                pressToEnterButton.visibility = View.GONE
                fragmentContainer.visibility = View.VISIBLE
            }
        }
    }
}