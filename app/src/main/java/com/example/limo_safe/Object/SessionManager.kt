package com.example.limo_safe.Object

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.google.firebase.auth.FirebaseAuth

class SessionManager(
    private val activity: ComponentActivity,
    private val onLogout: () -> Unit
) : LifecycleObserver {

    private var handler: Handler = Handler(Looper.getMainLooper())
    private var sessionTimeoutRunnable: Runnable? = null
    private var warningRunnable: Runnable? = null
    private val SESSION_TIMEOUT = 300000L // 5 minutes in milliseconds
    private val WARNING_TIME = 290000L // Show warning 10 seconds before timeout (5 minutes - 10 seconds)
    private val PREFS_NAME = "LIMOSafePrefs"
    private val LAST_GENERATE_TIME_KEY = "last_generate_time"
    private val KEY_MORSE_STATE_ACTIVE = "morse_state_active"

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    private val sharedPreferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        activity.lifecycle.addObserver(this)
        resetSessionTimeout()
    }

    private fun isUserSignedIn(): Boolean {
        return firebaseAuth.currentUser != null
    }

    private fun resetSessionTimeout() {
        // Remove existing callbacks
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        warningRunnable?.let { handler.removeCallbacks(it) }

        // Create warning runnable
        warningRunnable = Runnable {
            if (isUserSignedIn()) {
                Toast.makeText(
                    activity,
                    "Warning: Session will timeout in 10 seconds due to inactivity",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Create timeout runnable
        sessionTimeoutRunnable = Runnable {
            if (isUserSignedIn()) {
                // Save morse code state before logout
                val morseStateActive = sharedPreferences.getBoolean(KEY_MORSE_STATE_ACTIVE, false)
                if (morseStateActive) {
                    // Keep the morse state active so it can be restored after login
                    sharedPreferences.edit().putBoolean(KEY_MORSE_STATE_ACTIVE, true).apply()
                }

                Toast.makeText(activity, "Session timeout due to inactivity", Toast.LENGTH_LONG).show()
                onLogout.invoke()
            }
        }

        // Schedule both runnables
        warningRunnable?.let {
            handler.postDelayed(it, WARNING_TIME)
        }

        sessionTimeoutRunnable?.let {
            handler.postDelayed(it, SESSION_TIMEOUT)
        }
    }

    fun userActivityDetected() {
        if (isUserSignedIn()) {
            resetSessionTimeout()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        // Keep session active when app is minimized
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        warningRunnable?.let { handler.removeCallbacks(it) }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        // Restore session timeout when app is brought back to foreground
        if (isUserSignedIn()) {
            resetSessionTimeout()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        endSession()
        activity.lifecycle.removeObserver(this)
    }

    fun endSession() {
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        warningRunnable?.let { handler.removeCallbacks(it) }
    }

    fun setLastGenerateTime(time: Long) {
        sharedPreferences.edit().putLong(LAST_GENERATE_TIME_KEY, time).apply()
    }

    fun getLastGenerateTime(): Long {
        return sharedPreferences.getLong(LAST_GENERATE_TIME_KEY, 0L)
    }
}