package com.example.limo_safe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.limo_safe.utils.DialogManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException

class ForgotPasswordFragment : Fragment() {
    private lateinit var emailEditText: EditText
    private lateinit var resetPasswordButton: Button
    private lateinit var backButton: Button
    private lateinit var auth: FirebaseAuth
    private lateinit var dialogManager: DialogManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_forgot_password, container, false)

        // Initialize Firebase Auth and DialogManager
        auth = FirebaseAuth.getInstance()
        dialogManager = DialogManager(requireContext())

        // Initialize views
        emailEditText = view.findViewById(R.id.emailEditText)
        resetPasswordButton = view.findViewById(R.id.resetPasswordButton)
        backButton = view.findViewById(R.id.backButton)

        // Set click listeners
        resetPasswordButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            if (email.isEmpty()) {
                emailEditText.error = "Please enter your email"
                emailEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                return@setOnClickListener
            }

            // Show loading dialog with session management
            val loadingDialog = dialogManager.createLoadingDialog("Checking email...")
            loadingDialog.show()

            // First check if the email exists in Firebase Auth
            auth.fetchSignInMethodsForEmail(email)
                .addOnCompleteListener { fetchTask ->
                    if (fetchTask.isSuccessful) {
                        val signInMethods = fetchTask.result?.signInMethods ?: emptyList<String>()
                        if (signInMethods.isNotEmpty()) {
                            // Email exists, send password reset email
                            auth.sendPasswordResetEmail(email)
                                .addOnCompleteListener { resetTask ->
                                    dialogManager.dismissActiveDialog()
                                    if (resetTask.isSuccessful) {
                                        Toast.makeText(
                                            context,
                                            "Password reset email sent. Please check your inbox.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        // Navigate back to login
                                        parentFragmentManager.popBackStack()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Failed to send reset email: ${resetTask.exception?.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                        } else {
                            // Email not registered
                            dialogManager.dismissActiveDialog()
                            Toast.makeText(
                                context,
                                "This email is not registered in LIMOSafe. Please check your email or sign up.",
                                Toast.LENGTH_LONG
                            ).show()
                            emailEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                        }
                    } else {
                        // Error checking email
                        dialogManager.dismissActiveDialog()
                        val errorMessage = when (fetchTask.exception) {
                            is FirebaseAuthInvalidUserException -> "This email is not registered in LIMOSafe."
                            else -> "Error checking email: ${fetchTask.exception?.message}"
                        }
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                        emailEditText.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.shake))
                    }
                }
        }

        backButton.setOnClickListener {
            // Navigate back to login
            parentFragmentManager.popBackStack()
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Ensure any active dialogs are dismissed
        dialogManager.dismissActiveDialog()
    }
}
