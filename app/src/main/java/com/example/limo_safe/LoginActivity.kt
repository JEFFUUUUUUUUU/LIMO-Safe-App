package com.example.limo_safe

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var signInButton: Button
    private lateinit var signUpButton: Button
    private lateinit var exitButton: Button
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_login)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Initialize views
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        signInButton = findViewById(R.id.signInButton)
        signUpButton = findViewById(R.id.signUpButton)
        exitButton = findViewById(R.id.exitButton)

        // Set click listeners
        signInButton.setOnClickListener {
            attemptLogin()
        }

        signUpButton.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }

        exitButton.setOnClickListener {
            showExitConfirmationDialog()
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ ->
                // Exit the app
                finishAffinity()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun attemptLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (email.isNotEmpty() && password.isNotEmpty()) {
            if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            if (user?.isEmailVerified == true) {
                                try {
                                    // Create a custom Toast and position it higher
                                    val toast =
                                        Toast.makeText(this, "Welcome back!", Toast.LENGTH_SHORT)
                                    toast.setGravity(
                                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                                        0,
                                        150
                                    )
                                    toast.show()

                                    // Navigate to MC Activity with clear task flag
                                    val intent = Intent(this, MCActivity::class.java).apply {
                                        flags =
                                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    startActivity(intent)
                                    finish() // Close the login activity
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        this, "Error navigating to main screen: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    this,
                                    "Please verify your email first.",
                                    Toast.LENGTH_LONG
                                ).show()
                                auth.signOut()
                            }
                        } else {
                            Toast.makeText(
                                this, "Authentication failed: ${task.exception?.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            } else {
                Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_SHORT)
                    .show()
            }
        } else {
            Toast.makeText(this, "Email and password cannot be empty.", Toast.LENGTH_SHORT).show()
        }
    }
}