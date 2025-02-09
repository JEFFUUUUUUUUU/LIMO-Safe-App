package com.example.limo_safe.Object

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

    private val handler = Handler(Looper.getMainLooper())
    private var idleTimeoutRunnable: Runnable? = null
    private val idleTimeoutMillis: Long = 300000 // 5 minutes
    private val warningTimeMillis: Long = 10000 // 10 seconds before timeout

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    init {
        activity.lifecycle.addObserver(this)
        resetIdleTimeout()
    }

    private fun isUserSignedIn(): Boolean {
        return firebaseAuth.currentUser != null
    }

    private fun resetIdleTimeout() {
        if (!isUserSignedIn()) {
            cancelIdleTimeout()
            return
        }
        cancelIdleTimeout()
        idleTimeoutRunnable = Runnable {
            showIdleWarning()
        }
        handler.postDelayed(idleTimeoutRunnable!!, idleTimeoutMillis - warningTimeMillis)
    }

    private fun cancelIdleTimeout() {
        idleTimeoutRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun showIdleWarning() {
        if (!isUserSignedIn()) {
            cancelIdleTimeout()
            return
        }
        Toast.makeText(activity, "You will be logged out in 10 seconds due to inactivity.", Toast.LENGTH_LONG).show()
        handler.postDelayed({
            if (isUserSignedIn()) {
                firebaseAuth.signOut()
                activity.finishAffinity() // This will close the app
            }
        }, warningTimeMillis)
    }

    fun userActivityDetected() {
        if (isUserSignedIn()) {
            resetIdleTimeout()
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
        cancelIdleTimeout()
        activity.lifecycle.removeObserver(this)
    }
}