package com.example.limo_safe

import android.content.Context
import android.os.Bundle
import android.util.Log
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
import com.example.limo_safe.utils.BiometricManager

class LoginFragment : Fragment() {
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var biometricLoginButton: Button
    private lateinit var signUpText: TextView
    private lateinit var forgotPasswordText: TextView
    private lateinit var dialogManager: DialogManager
    private lateinit var biometricManager: BiometricManager
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
        biometricManager = BiometricManager(requireContext())
        
        initializeViews(view)
        setupClickListeners()
        
        // Hide the biometric login button
        biometricLoginButton.visibility = View.GONE
        
        // Automatically trigger biometric authentication if enabled and available
        if (biometricManager.isBiometricEnabled() && biometricManager.isBiometricAvailable()) {
            authenticateWithBiometric()
        }

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
                
            // Don't clear biometric preferences
            // This ensures biometric login remains enabled between sessions
        }
    }

    private fun initializeViews(view: View) {
        emailEditText = view.findViewById(R.id.emailEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        loginButton = view.findViewById(R.id.loginButton)
        biometricLoginButton = view.findViewById(R.id.biometricLoginButton)
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
    
    private fun updateBiometricButtonVisibility() {
        if (biometricManager.isBiometricAvailable() && biometricManager.isBiometricEnabled()) {
            biometricLoginButton.visibility = View.VISIBLE
            
            // Pre-fill email field if available
            biometricManager.getBiometricEmail()?.let { email ->
                emailEditText.setText(email)
            }
        } else {
            biometricLoginButton.visibility = View.GONE
        }
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
        
        biometricLoginButton.setOnClickListener {
            authenticateWithBiometric()
        }

        signUpText.setOnClickListener {
            Log.d("LoginFragment", "Sign Up text clicked")
            navigateToSignUp()
        }

        forgotPasswordText.setOnClickListener {
            Log.d("LoginFragment", "Forgot Password text clicked")
            navigateToForgotPassword()
        }
    }
    
    private fun authenticateWithBiometric() {
        val email = biometricManager.getBiometricEmail()
        if (email.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Biometric login not properly set up", Toast.LENGTH_SHORT).show()
            biometricManager.disableBiometric()
            updateBiometricButtonVisibility()
            return
        }
        
        // Check if the user is already signed in
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.email == email) {
            // User is already signed in with the correct account
            Log.d("LoginFragment", "User already signed in with correct account")
            handleSuccessfulLogin()
            return
        }
        
        biometricManager.showBiometricPrompt(
            fragment = this,
            title = "Biometric Login",
            subtitle = "Log in using your biometric credential",
            description = "Use your fingerprint or face to quickly access your account",
            negativeButtonText = "Cancel",
            onSuccess = {
                // Biometric authentication successful, now sign in with Firebase
                emailEditText.setText(email)
                
                // Show loading dialog
                val loadingDialog = dialogManager.createLoadingDialog("Logging in...")
                loadingDialog.show()
                
                try {
                    // Get the stored password from BiometricManager
                    val storedPassword = biometricManager.getStoredPassword(email)
                    
                    if (storedPassword.isNullOrEmpty()) {
                        // No stored password, ask user to enter it manually
                        dialogManager.dismissActiveDialog()
                        Toast.makeText(requireContext(), 
                            "Please enter your password to complete login", 
                            Toast.LENGTH_LONG).show()
                        passwordEditText.requestFocus()
                        return@showBiometricPrompt
                    }
                    
                    Log.d("LoginFragment", "Attempting Firebase login with stored credentials")
                    
                    // First sign out any existing user
                    if (auth.currentUser != null) {
                        auth.signOut()
                    }
                    
                    // Sign in with the stored credentials
                    auth.signInWithEmailAndPassword(email, storedPassword)
                        .addOnCompleteListener { task ->
                            dialogManager.dismissActiveDialog()
                            if (task.isSuccessful) {
                                // Login successful
                                Log.d("LoginFragment", "Biometric login successful for $email")
                                handleSuccessfulLogin()
                            } else {
                                // Login failed
                                Log.e("LoginFragment", "Firebase auth failed: ${task.exception?.message}")
                                
                                // Check if the error is due to an upgrade
                                val errorMessage = task.exception?.message ?: ""
                                if (errorMessage.contains("upgrade") || errorMessage.contains("Upgrade")) {
                                    Toast.makeText(requireContext(),
                                        "Your account needs to be updated. Please sign in with your password.",
                                        Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(requireContext(),
                                        "Authentication failed. Please enter your password manually.",
                                        Toast.LENGTH_LONG).show()
                                }
                                
                                passwordEditText.requestFocus()
                            }
                        }
                } catch (e: Exception) {
                    Log.e("LoginFragment", "Error during biometric login: ${e.message}")
                    dialogManager.dismissActiveDialog()
                    Toast.makeText(requireContext(),
                        "Login error: ${e.message}",
                        Toast.LENGTH_LONG).show()
                    passwordEditText.requestFocus()
                }
            },
            onError = { errorCode, errString ->
                if (errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED) {
                    Toast.makeText(requireContext(), 
                        "Authentication error: $errString", 
                        Toast.LENGTH_SHORT).show()
                }
            }
        )
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
        try {
            Log.d("LoginFragment", "Attempting to navigate to SignUpFragment")
            
            // Use MainActivity's navigation method
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                mainActivity.navigateToSignUp()
                Log.d("LoginFragment", "Used MainActivity to navigate to SignUpFragment")
            } else {
                Log.e("LoginFragment", "Activity is not MainActivity")
                Toast.makeText(requireContext(), "Navigation error: Invalid activity", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("LoginFragment", "Navigation error: ${e.message}", e)
            Toast.makeText(requireContext(), "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToForgotPassword() {
        try {
            Log.d("LoginFragment", "Attempting to navigate to ForgotPasswordFragment")
            
            // Use MainActivity's navigation method
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                mainActivity.navigateToForgotPassword()
                Log.d("LoginFragment", "Used MainActivity to navigate to ForgotPasswordFragment")
            } else {
                Log.e("LoginFragment", "Activity is not MainActivity")
                Toast.makeText(requireContext(), "Navigation error: Invalid activity", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("LoginFragment", "Navigation error: ${e.message}", e)
            Toast.makeText(requireContext(), "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dialogManager.dismissActiveDialog()
    }
}