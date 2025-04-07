package com.example.limo_safe.base

import androidx.fragment.app.Fragment
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.MainActivity

open class BaseFragment : Fragment() {
    protected open val sessionManager: SessionManager by lazy {
        (requireActivity() as MainActivity).sessionManager
    }

    override fun onResume() {
        super.onResume()
        sessionManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        sessionManager.onPause()
    }
}