package com.example.limo_safe

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.example.limo_safe.Object.SessionManager
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var auth: FirebaseAuth
    private lateinit var mainContent: LinearLayout
    private lateinit var pressToEnterButton: Button
    lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        supportFragmentManager.addOnBackStackChangedListener(this)

        // Initialize session manager
        sessionManager = SessionManager(this) {
            // On session timeout/logout
            auth.signOut()
            clearBackStackAndNavigateToLogin()
        }

        mainContent = findViewById(R.id.mainContent)
        pressToEnterButton = findViewById(R.id.pressToEnterButton)

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

        // Check if user is already logged in
        if (savedInstanceState == null) {
            val currentUser = auth.currentUser
            if (currentUser != null && !currentUser.isEmailVerified) {
                // If email is not verified, show login screen
                clearBackStackAndNavigateToLogin()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sessionManager.userActivityDetected()
    }

    private fun clearBackStackAndNavigateToLogin() {
        // Clear the entire back stack
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        
        // Clear any session-related preferences
        getSharedPreferences("MorseCodePrefs", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("LIMOSafePrefs", Context.MODE_PRIVATE).edit().clear().apply()
        
        // Show the main content
        mainContent.visibility = View.GONE
        
        // Navigate to login
        val loginFragment = LoginFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commit()
    }

    private fun navigateToLogin() {
        mainContent.visibility = View.GONE
        val loginFragment = LoginFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToMC() {
        mainContent.visibility = View.GONE
        val mcFragment = MCFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, mcFragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onBackStackChanged() {
        sessionManager.userActivityDetected()
        if (supportFragmentManager.backStackEntryCount == 0) {
            mainContent.visibility = View.VISIBLE
        } else {
            mainContent.visibility = View.GONE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            if (it.action == MotionEvent.ACTION_DOWN) {
                sessionManager.userActivityDetected()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        supportFragmentManager.removeOnBackStackChangedListener(this)
    }
}