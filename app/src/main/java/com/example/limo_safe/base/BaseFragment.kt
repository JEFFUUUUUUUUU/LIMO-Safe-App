package com.example.limo_safe.base

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.Fragment
import com.example.limo_safe.MainActivity
import com.example.limo_safe.Object.SessionManager

abstract class BaseFragment : Fragment() {
    protected lateinit var sessionManager: SessionManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize session manager
        sessionManager = (requireActivity() as MainActivity).sessionManager

        // Set up touch listener for session activity tracking
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                sessionManager.userActivityDetected()
            }
            false
        }
    }

    override fun onResume() {
        super.onResume()
        sessionManager.userActivityDetected()
    }
}