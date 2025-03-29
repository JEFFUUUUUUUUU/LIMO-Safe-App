package com.example.limo_safe.Object

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.example.limo_safe.LoginFragment
import com.google.firebase.auth.FirebaseAuth

class SessionManager(
    private val activity: FragmentActivity,
    private val onLogout: () -> Unit
) : LifecycleObserver {

    private var handler: Handler = Handler(Looper.getMainLooper())
    private var sessionTimeoutRunnable: Runnable? = null
    private var warningRunnable: Runnable? = null
    private val SESSION_TIMEOUT = 300000L // 5 minutes in milliseconds
    private val WARNING_TIME = 290000L // Show warning 10 seconds before timeout (5 minutes - 10 seconds)
    private val PREFS_NAME = "LIMOSafePrefs"

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    private var isWarningShown = false
    private var isTimeoutShown = false

    init {
        activity.lifecycle.addObserver(this)
        resetSessionTimeout()
    }

    private fun isUserSignedIn(): Boolean {
        return firebaseAuth.currentUser != null
    }

    private fun clearAllRunnables() {
        sessionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        warningRunnable?.let { handler.removeCallbacks(it) }
        sessionTimeoutRunnable = null
        warningRunnable = null
        isWarningShown = false
        isTimeoutShown = false
    }

    private fun clearAllPreferences() {
        // Clear LIMOSafe preferences
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
            
        // Clear Morse code preferences
        activity.getSharedPreferences("MorseCodePrefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
            
        // Clear any other app preferences
        activity.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun resetSessionTimeout() {
        clearAllRunnables()

        // Only set up timeouts if user is signed in
        if (!isUserSignedIn()) {
            return
        }

        // Create warning runnable
        warningRunnable = Runnable {
            if (isUserSignedIn() && !isWarningShown) {
                isWarningShown = true
                Toast.makeText(
                    activity,
                    "Warning: Session will timeout in 10 seconds due to inactivity",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Create timeout runnable
        sessionTimeoutRunnable = Runnable {
            if (isUserSignedIn() && !isTimeoutShown) {
                isTimeoutShown = true
                
                // Clear all preferences first
                clearAllPreferences()
                
                // Sign out the user
                firebaseAuth.signOut()
                
                Toast.makeText(activity, "Session timeout due to inactivity", Toast.LENGTH_LONG).show()

                // Clear the back stack and navigate to login
                activity.supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                
                // Create a new instance of LoginFragment
                val loginFragment = LoginFragment.newInstance()
                
                // Replace current fragment with login
                activity.supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, loginFragment)
                    .commit()
                
                // Notify the activity
                onLogout.invoke()
                
                // Clear all runnables after timeout
                clearAllRunnables()
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
        clearAllRunnables()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        if (isUserSignedIn()) {
            resetSessionTimeout()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun cleanup() {
        clearAllRunnables()
        activity.lifecycle.removeObserver(this)
    }
}