package com.example.limo_safe

import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class SignUpActivity : AppCompatActivity() {
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var confirmPasswordEditText: EditText
    private lateinit var signUpButton: Button
    private lateinit var backButton: Button
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_signup)

        // Initialize Firebase Auth and Database
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("users") // Reference to /users

        // Initialize views
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        confirmPasswordEditText = findViewById(R.id.confirmPasswordEditText)
        signUpButton = findViewById(R.id.signUpButton)
        backButton = findViewById(R.id.backButton)

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
                                saveUserToDatabase(user.uid, email)
                                Toast.makeText(
                                    this,
                                    "Registration successful! Please check your email for verification.",
                                    Toast.LENGTH_LONG
                                ).apply { setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 150) }
                                    .show()

                                finish()
                            }
                        }
                } else {
                    Toast.makeText(this, "Registration failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun saveUserToDatabase(userId: String, email: String) {
        generateUniqueTag { uniqueTag ->
            val userMap = mapOf(
                "email" to email,
                "tag" to uniqueTag,  // Assign the unique tag
                "device_registered" to false
            )

            database.child(userId).setValue(userMap)
                .addOnSuccessListener {
                    Toast.makeText(this, "User registered with tag: $uniqueTag", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save user data.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // Function to generate a unique single-character tag
    private fun generateUniqueTag(callback: (String) -> Unit) {
        val characters = ('A'..'Z') + ('0'..'9')
        val databaseReference = FirebaseDatabase.getInstance().getReference("users")

        fun attemptTagGeneration() {
            val newTag = characters.random().toString()

            // Check if the tag already exists in the database
            databaseReference.orderByChild("tag").equalTo(newTag).get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        // If the tag already exists, try again
                        attemptTagGeneration()
                    } else {
                        // If unique, pass it to the callback
                        callback(newTag)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error checking tag uniqueness", Toast.LENGTH_SHORT).show()
                    callback(characters.random().toString())  // Fallback random tag
                }
        }

        attemptTagGeneration()
    }
}
