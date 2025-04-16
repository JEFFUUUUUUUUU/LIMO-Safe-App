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

class LoginFragment : Fragment() {
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var signUpText: TextView
    private lateinit var forgotPasswordText: TextView
    private lateinit var dialogManager: DialogManager
    private lateinit var auth: FirebaseAuth

    companion object {
        fun newInstance() = LoginFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_login, container, false)
        view.visibility = View.VISIBLE
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ensure fragment container is visible
        requireActivity().findViewById<View>(R.id.fragmentContainer).visibility = View.VISIBLE

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

        // Ensure all views are visible
        emailEditText.visibility = View.VISIBLE
        passwordEditText.visibility = View.VISIBLE
        loginButton.visibility = View.VISIBLE
        signUpText.visibility = View.VISIBLE
        forgotPasswordText.visibility = View.VISIBLE

        // Set proper background colors
        view.setBackgroundColor(resources.getColor(android.R.color.transparent, null))
        view.findViewById<View>(R.id.topBar)?.setBackgroundColor(resources.getColor(android.R.color.holo_orange_light, null))
        view.findViewById<View>(R.id.bottomBar)?.setBackgroundColor(resources.getColor(android.R.color.holo_orange_light, null))
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
        try {
            val currentUser = auth.currentUser
            if (currentUser != null && currentUser.isEmailVerified) {
                // Dismiss any active dialogs
                try {
                    dialogManager.dismissActiveDialog()
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Continue even if dialog dismissal fails
                }

                // Default navigation - no timeout handling needed

                // Default navigation through MainActivity
                try {
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null && !mainActivity.isFinishing && isAdded) {
                        mainActivity.onLoginSuccessful()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Please verify your email first", Toast.LENGTH_LONG).show()
                auth.signOut()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                Toast.makeText(context, "Login error: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
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