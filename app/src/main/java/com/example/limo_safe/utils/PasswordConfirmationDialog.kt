package com.example.limo_safe.utils

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.limo_safe.R
import com.google.firebase.auth.FirebaseAuth

/**
 * Utility class to handle password confirmation for biometric setup
 */
class PasswordConfirmationDialog(
    private val context: Context,
    private val dialogManager: DialogManager
) {

    /**
     * Show a dialog to confirm the user's password for biometric setup
     */
    fun showPasswordConfirmationDialog(
        email: String,
        biometricManager: BiometricManager,
        fragment: Fragment,
        onSuccess: () -> Unit,
        onCancel: () -> Unit
    ) {
        // Create a dialog with password input
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_password_confirm, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)

        // Create an AlertDialog builder
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Confirm Password")
        builder.setView(dialogView)
        builder.setCancelable(false)

        // Set up positive and negative buttons
        builder.setPositiveButton("Enable Biometric") { _, _ ->
            // This will be overridden below to prevent automatic dismissal
        }

        builder.setNegativeButton("Cancel") { _, _ ->
            // This will be overridden below
        }

        // Create and show the dialog
        val alertDialog = builder.create()
        alertDialog.show()

        // Override the positive button click listener to prevent automatic dismissal
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = passwordEditText.text.toString()
            if (password.isNotEmpty()) {
                // Verify password with Firebase before enabling biometric
                val loadingDialog = dialogManager.createLoadingDialog("Verifying password...")
                loadingDialog.show()

                FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        dialogManager.dismissActiveDialog() // Dismiss loading dialog

                        if (task.isSuccessful) {
                            // Password verified, now show biometric enrollment
                            alertDialog.dismiss()
                            biometricManager.showBiometricEnrollmentPrompt(
                                fragment = fragment,
                                email = email,
                                password = password,
                                onSuccess = {
                                    Toast.makeText(context, "Biometric login enabled successfully!", Toast.LENGTH_SHORT).show()
                                    onSuccess()
                                },
                                onCancel = {
                                    Toast.makeText(context, "Biometric enrollment canceled", Toast.LENGTH_SHORT).show()
                                    onCancel()
                                }
                            )
                        } else {
                            // Password verification failed
                            Toast.makeText(context, "Incorrect password. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Password cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Override the negative button click listener
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            alertDialog.dismiss()
            Toast.makeText(context, "Biometric setup canceled", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }
}