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
    private val SESSION_TIMEOUT = 180000L // 3 minute in milliseconds
    private val WARNING_TIME = 170000L // Show warning 10 seconds before timeout (3 minutes - 10 seconds)

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

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
        if (isUserSignedIn()) {
            firebaseAuth.signOut()
            activity.finishAffinity()
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
}