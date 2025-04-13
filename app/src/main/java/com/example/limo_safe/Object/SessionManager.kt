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
    private var onSessionTimeoutListener: (() -> Unit)? = null
    private var isTimeoutLogout = false
    private var hasShownWarning = false
    private var hasShownTimeout = false

    init {
        // Only check session if there's a current user
        if (auth.currentUser != null && !isValidSession()) {
            performTimeout()
        }
    }

    private fun isValidSession(): Boolean {
        val isLoggedIn = prefs.getBoolean("isLoggedIn", false)
        val sessionExpiryTime = prefs.getLong("sessionExpiryTime", 0)
        val currentTime = System.currentTimeMillis()
        val lastPauseTime = prefs.getLong("lastPauseTime", 0)

        // Session is invalid if:
        // 1. Not logged in
        // 2. Session has expired
        // 3. App was paused/destroyed for more than 1 second (force close detection)
        return isLoggedIn &&
                currentTime <= sessionExpiryTime &&
                (lastPauseTime == 0L || currentTime - lastPauseTime <= 1000)
    }

    fun onLoginSuccess() {
        val currentTime = System.currentTimeMillis()
        prefs.edit().apply {
            putBoolean("isLoggedIn", true)
            putLong("lastActivityTime", currentTime)
            putLong("sessionExpiryTime", currentTime + SESSION_TIMEOUT)
            putLong("lastPauseTime", 0)
            apply()
        }
        resetSessionTimeout()
    }

    fun resetSessionTimeout() {
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }

        val currentTime = System.currentTimeMillis()
        prefs.edit().putLong("sessionExpiryTime", currentTime + SESSION_TIMEOUT).apply()

        // Reset toast flags when resetting timeout
        hasShownWarning = false
        hasShownTimeout = false

        // First set up warning
        handler.postDelayed({
            if (isValidSession() && !hasShownWarning) {
                hasShownWarning = true
                activity.runOnUiThread {
                    Toast.makeText(activity, "Session will timeout in 10 seconds due to inactivity", Toast.LENGTH_LONG).show()
                }
            }
        }, WARNING_TIME)

        // Then set up timeout
        sessionTimeoutRunnable = Runnable {
            if (!hasShownTimeout) {
                hasShownTimeout = true
                activity.runOnUiThread {
                    Toast.makeText(activity, "Session timeout. Please login again.", Toast.LENGTH_LONG).show()
                    performTimeout()
                }
            }
        }
        handler.postDelayed(sessionTimeoutRunnable!!, SESSION_TIMEOUT)
    }

    fun onPause() {
        prefs.edit().putLong("lastPauseTime", System.currentTimeMillis()).apply()
    }

    fun onResume() {
        val currentTime = System.currentTimeMillis()
        val sessionExpiryTime = prefs.getLong("sessionExpiryTime", 0)

        if (currentTime > sessionExpiryTime) {
            performTimeout()
        } else {
            resetSessionTimeout()
        }
    }

    fun userActivityDetected() {
        if (isValidSession()) {
            resetSessionTimeout()
        }
    }

    fun checkSession() {
        if (!isValidSession()) {
            performTimeout()
        }
    }

    fun isLoggedIn(): Boolean {
        return isValidSession()
    }

    fun logout() {
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        prefs.edit().apply {
            clear()
            apply()
        }
        isTimeoutLogout = false
        hasShownWarning = false
        hasShownTimeout = false
    }

    fun isSessionTimeout(): Boolean {
        return isTimeoutLogout
    }

    fun clearTimeoutFlag() {
        isTimeoutLogout = false
    }

    fun setOnSessionTimeoutListener(listener: () -> Unit) {
        onSessionTimeoutListener = listener
    }

    private fun performTimeout() {
        isTimeoutLogout = true

        // Clear session state first
        logout()

        // Dismiss any active dialogs through the listener
        onSessionTimeoutListener?.invoke()

        // Sign out from Firebase
        auth.signOut()

        // Notify activity of timeout last
        activity.runOnUiThread {
            onSessionTimeout.invoke()
        }
    }
}