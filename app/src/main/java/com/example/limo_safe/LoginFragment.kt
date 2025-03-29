package com.example.limo_safe

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.example.limo_safe.utils.DialogManager
import com.example.limo_safe.base.BaseFragment

class LoginFragment : BaseFragment() {
    private lateinit var auth: FirebaseAuth
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var signUpText: TextView
    private lateinit var forgotPasswordText: TextView
    private lateinit var dialogManager: DialogManager

    companion object {
        fun newInstance() = LoginFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        dialogManager = DialogManager(requireContext())
        initializeViews(view)
        setupClickListeners()

        // Clear all preferences to ensure fresh state
        clearAllPreferences()
    }

    private fun clearAllPreferences() {
        requireActivity().apply {
            getSharedPreferences("MorseCodePrefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            getSharedPreferences("LIMOSafePrefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    private fun initializeViews(view: View) {
        emailEditText = view.findViewById(R.id.emailEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        loginButton = view.findViewById(R.id.loginButton)
        signUpText = view.findViewById(R.id.signUpText)
        forgotPasswordText = view.findViewById(R.id.forgotPasswordText)
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill in all fields", Toast.LENGTH_SHORT).show()
                if (email.isEmpty()) emailEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                if (password.isEmpty()) passwordEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                return@setOnClickListener
            }

            // Show loading dialog
            val loadingDialog = dialogManager.createLoadingDialog("Logging in...")
            loadingDialog.show()

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    dialogManager.dismissActiveDialog()
                    if (task.isSuccessful) {
                        handleSuccessfulLogin()
                    } else {
                        Toast.makeText(requireContext(),
                            "Login failed: ${task.exception?.message}",
                            Toast.LENGTH_LONG).show()
                        emailEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                        passwordEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                    }
                }
        }

        signUpText.setOnClickListener {
            navigateToSignUp()
        }

        forgotPasswordText.setOnClickListener {
            navigateToForgotPassword()
        }
    }

    private fun handleSuccessfulLogin() {
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            // Clear any existing preferences
            clearAllPreferences()

            // Start a fresh session
            sessionManager.userActivityDetected()
            
            // Navigate to MC fragment
            navigateToMC()
        } else {
            Toast.makeText(requireContext(), "Please verify your email first", Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateToMC() {
        val mcFragment = MCFragment()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, mcFragment)
            .commit()
    }

    private fun navigateToSignUp() {
        val signUpFragment = SignUpFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, signUpFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToForgotPassword() {
        val forgotPasswordFragment = ForgotPasswordFragment()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, forgotPasswordFragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dialogManager.dismissActiveDialog()
    }
}