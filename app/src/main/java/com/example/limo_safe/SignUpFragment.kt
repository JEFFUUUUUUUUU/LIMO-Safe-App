package com.example.limo_safe

import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class SignUpFragment : Fragment() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var confirmPasswordEditText: EditText
    private lateinit var signUpButton: Button
    private lateinit var loginButton: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_signup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("users")

        initializeViews(view)
        setupClickListeners()
    }

    private fun initializeViews(view: View) {
        emailEditText = view.findViewById(R.id.emailEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        confirmPasswordEditText = view.findViewById(R.id.confirmPasswordEditText)
        signUpButton = view.findViewById(R.id.signUpButton)
        loginButton = view.findViewById(R.id.loginButton)
    }

    private fun setupClickListeners() {
        signUpButton.setOnClickListener {
            attemptSignUp()
        }

        loginButton.setOnClickListener {
            navigateToLogin()
        }
    }

    private fun attemptSignUp() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()
        val confirmPassword = confirmPasswordEditText.text.toString().trim()

        if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            showToast("Please fill in all fields")
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showToast("Please enter a valid email address")
            return
        }

        if (password != confirmPassword) {
            showToast("Passwords do not match")
            return
        }

        if (password.length < 6) {
            showToast("Password should be at least 6 characters")
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.sendEmailVerification()
                        ?.addOnCompleteListener { verificationTask ->
                            if (verificationTask.isSuccessful) {
                                saveUserToDatabase(user.uid, email)
                                showToast("Registration successful! Please check your email for verification.", true)
                                navigateToLogin()
                            }
                        }
                } else {
                    showToast("Registration failed: ${task.exception?.message}")
                }
            }
    }

    private fun saveUserToDatabase(userId: String, email: String) {
        generateUserTag { userTag ->
            val userMap = mapOf(
                "email" to email,
                "tag" to userTag,
                "created_at" to System.currentTimeMillis()
            )

            database.child(userId).setValue(userMap)
                .addOnSuccessListener {
                    showToast("Your user tag is: $userTag\nKeep this for reference!", true)
                }
                .addOnFailureListener {
                    showToast("Failed to save user data.")
                }
        }
    }

    private fun generateUserTag(callback: (String) -> Unit) {
        // Single character A-Z, 0-9
        val characters = ('A'..'Z') + ('0'..'9')

        fun attemptTagGeneration() {
            val newTag = characters.random().toString()

            database.orderByChild("tag").equalTo(newTag).get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        attemptTagGeneration()
                    } else {
                        callback(newTag)
                    }
                }
                .addOnFailureListener {
                    showToast("Error checking tag uniqueness")
                    callback(characters.random().toString())
                }
        }

        attemptTagGeneration()
    }

    private fun showToast(message: String, isLong: Boolean = false) {
        Toast.makeText(
            requireContext(),
            message,
            if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).apply {
            if (isLong) {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 150)
            }
        }.show()
    }

    private fun navigateToLogin() {
        val loginFragment = LoginFragment.newInstance()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .commit()
    }

    companion object {
        fun newInstance() = SignUpFragment()
    }
}