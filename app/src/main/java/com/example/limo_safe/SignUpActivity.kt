package com.example.limo_safe

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SignUpActivity : AppCompatActivity() {
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var confirmPasswordEditText: EditText
    private lateinit var signUpButton: Button
    private lateinit var backButton: Button
    private lateinit var wifiButton: ImageButton
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_signup)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Initialize views
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        confirmPasswordEditText = findViewById(R.id.confirmPasswordEditText)
        signUpButton = findViewById(R.id.signUpButton)
        backButton = findViewById(R.id.backButton)
        wifiButton = findViewById(R.id.wifiButton)

        // Set click listeners
        wifiButton.setOnClickListener {
            showWifiCredentialsDialog()
        }

        signUpButton.setOnClickListener {
            attemptSignUp()
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun attemptSignUp() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()
        val confirmPassword = confirmPasswordEditText.text.toString().trim()

        if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.length < 6) {
            Toast.makeText(this, "Password should be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.sendEmailVerification()
                        ?.addOnCompleteListener { verificationTask ->
                            if (verificationTask.isSuccessful) {
                                val toast = Toast.makeText(this, 
                                    "Registration successful! Please check your email for verification.", 
                                    Toast.LENGTH_LONG)
                                toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 150)
                                toast.show()
                                finish()
                            }
                        }
                } else {
                    Toast.makeText(this, "Registration failed: ${task.exception?.message}", 
                        Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun showWifiCredentialsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        val ssidInput = EditText(this).apply {
            hint = "SSID"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        val passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(ssidInput)
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("EPS Credentials")
            .setView(layout)
            .setPositiveButton("Enter") { dialog, _ ->
                val ssid = ssidInput.text.toString()
                val password = passwordInput.text.toString()
                if (ssid.isNotEmpty() && password.isNotEmpty()) {
                    // TODO: Handle the WiFi credentials
                    Toast.makeText(this, "WiFi credentials saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
} 