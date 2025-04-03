package com.example.limo_safe.Object

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class SessionManager(private val activity: Activity, private val onSessionTimeout: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var sessionTimeoutRunnable: Runnable? = null
    private val SESSION_TIMEOUT = 150000L // 2.5 minutes
    private val WARNING_TIME = 140000L // 2.5 minutes - show warning 10 seconds before timeout
    private val prefs = activity.getSharedPreferences("SessionPrefs", Context.MODE_PRIVATE)
    private val auth = FirebaseAuth.getInstance()

    init {
        checkSession()
    }

    fun onLoginSuccess() {
        prefs.edit().apply {
            putBoolean("isLoggedIn", true)
            putLong("lastActivityTime", System.currentTimeMillis())
            apply()
        }
        resetSessionTimeout()
    }

    fun resetSessionTimeout() {
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        
        // First set up warning
        handler.postDelayed({
            if (isLoggedIn()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Session will timeout in 10 seconds due to inactivity", Toast.LENGTH_LONG).show()
                }
            }
        }, WARNING_TIME)
        
        // Then set up timeout
        sessionTimeoutRunnable = Runnable {
            if (isLoggedIn()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Session timeout. Please login again.", Toast.LENGTH_LONG).show()
                    performTimeout()
                }
            }
        }
        
        handler.postDelayed(sessionTimeoutRunnable!!, SESSION_TIMEOUT)
        
        // Update last activity time
        prefs.edit().putLong("lastActivityTime", System.currentTimeMillis()).apply()
    }

    private fun performTimeout() {
        // Sign out from Firebase
        auth.signOut()
        
        // Clear session state
        logout()
        
        // Notify activity of timeout
        onSessionTimeout.invoke()
    }

    fun userActivityDetected() {
        if (isLoggedIn()) {
            resetSessionTimeout()
        }
    }

    fun checkSession() {
        val isLoggedIn = prefs.getBoolean("isLoggedIn", false)
        val lastActivityTime = prefs.getLong("lastActivityTime", 0)
        val currentTime = System.currentTimeMillis()

        if (isLoggedIn && (currentTime - lastActivityTime > SESSION_TIMEOUT)) {
            performTimeout()
        } else if (isLoggedIn) {
            resetSessionTimeout()
        }
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean("isLoggedIn", false)
    }

    fun logout() {
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        prefs.edit().apply {
            putBoolean("isLoggedIn", false)
            putLong("lastActivityTime", 0)
            apply()
        }
    }
}