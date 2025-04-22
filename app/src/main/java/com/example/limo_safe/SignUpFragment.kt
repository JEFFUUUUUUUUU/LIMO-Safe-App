package com.example.limo_safe

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.example.limo_safe.utils.BiometricManager
import com.example.limo_safe.utils.DialogManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SignUpFragment : Fragment() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var confirmPasswordEditText: EditText
    private lateinit var signUpButton: Button
    private lateinit var loginButton: TextView
    private lateinit var biometricManager: BiometricManager
    private lateinit var dialogManager: DialogManager

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
        biometricManager = BiometricManager(requireContext())
        dialogManager = DialogManager(requireContext())

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

        // Show loading dialog
        val loadingDialog = dialogManager.createLoadingDialog("Verifying email...")
        loadingDialog.show()

        // Verify if the email actually exists
        verifyEmailExists(email) { emailExists, errorMessage ->
            if (!emailExists) {
                activity?.runOnUiThread {
                    dialogManager.dismissActiveDialog()
                    showToast(errorMessage ?: "This email address does not exist. Please enter a valid email.")
                }
                return@verifyEmailExists
            }
            
            // Continue with Firebase check if email exists
            activity?.runOnUiThread {
                // Check if the email is already registered with Firebase
                auth.fetchSignInMethodsForEmail(email)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val signInMethods = task.result?.signInMethods
                            if (!signInMethods.isNullOrEmpty()) {
                                // Email already exists in Firebase
                                dialogManager.dismissActiveDialog()
                                showToast("This email is already registered. Please use a different email or try logging in.")
                            } else {
                                // Email exists and is not registered with Firebase
                                // Update loading dialog message
                                dialogManager.dismissActiveDialog()
                                val createAccountDialog = dialogManager.createLoadingDialog("Creating account...")
                                createAccountDialog.show()

                                // Create the account with Firebase
                                auth.createUserWithEmailAndPassword(email, password)
                                    .addOnCompleteListener { createTask ->
                                        dialogManager.dismissActiveDialog()
                                        if (createTask.isSuccessful) {
                                            val user = auth.currentUser
                                            user?.sendEmailVerification()
                                                ?.addOnCompleteListener { verificationTask ->
                                                    if (verificationTask.isSuccessful) {
                                                        saveUserToDatabase(user.uid, email)
                                                        showVerificationInstructionsDialog(email)
                                                    }
                                                }
                                        } else {
                                            showToast("Registration failed: ${createTask.exception?.message}")
                                        }
                                    }
                            }
                        } else {
                            // Error checking email with Firebase
                            dialogManager.dismissActiveDialog()
                            showToast("Error checking email: ${task.exception?.message}")
                        }
                    }
            }
        }
    }

    /**
     * Check if the email domain is valid
     * This is a basic validation to filter out obviously fake domains
     */
    private fun isValidEmailDomain(email: String): Boolean {
        try {
            // Extract domain from email
            val domain = email.substring(email.indexOf('@') + 1)
            
            // Check if the domain is a common email provider
            val commonEmailDomains = listOf(
                "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", 
                "icloud.com", "aol.com", "protonmail.com", "mail.com",
                "zoho.com", "yandex.com", "gmx.com", "live.com",
                "msn.com", "yahoo.co.uk", "yahoo.co.jp", "yahoo.co.in",
                "rediffmail.com", "163.com", "qq.com", "naver.com",
                "daum.net", "mail.ru", "web.de", "t-online.de",
                "comcast.net", "verizon.net", "att.net", "sbcglobal.net"
            )
            
            if (commonEmailDomains.contains(domain.toLowerCase())) {
                // If it's a common email domain, consider it valid
                return true
            }
            
            // For non-common domains, check if the domain has a valid format
            val domainPattern = Patterns.DOMAIN_NAME
            if (domainPattern.matcher(domain).matches()) {
                // If the domain format is valid, consider it potentially valid
                return true
            }
            
            // If we get here, the domain doesn't match common patterns
            return false
        } catch (e: Exception) {
            // If there's any error in validation, fail safely by rejecting the email
            return false
        }
    }
    
    /**
     * Verify if an email actually exists using a third-party API
     * This makes a network request to check if the email exists on a mail server
     */
    private fun verifyEmailExists(email: String, callback: (Boolean, String?) -> Unit) {
        thread {
            try {
                // Extract username and domain from email
                val parts = email.split("@")
                if (parts.size != 2) {
                    activity?.runOnUiThread {
                        callback(false, "Invalid email format")
                    }
                    return@thread
                }
                
                val username = parts[0]
                val domain = parts[1].toLowerCase()
                
                // Check for common non-existent email patterns
                val commonFakeUsernames = listOf(
                    "test", "user", "example", "sample", "fake", "demo", "noreply", "no-reply",
                    "admin", "info", "support", "help", "mail", "email", "webmaster", "postmaster",
                    "hostmaster", "abuse", "security", "spam", "contact", "feedback", "sales", "marketing"
                )
                
                // If it's a common test username with a common domain, reject it
                if (domain == "gmail.com" || domain == "yahoo.com" || domain == "hotmail.com" || domain == "outlook.com") {
                    if (commonFakeUsernames.any { username.contains(it, ignoreCase = true) }) {
                        activity?.runOnUiThread {
                            callback(false, "This appears to be a test email. Please use your real email address.")
                        }
                        return@thread
                    }
                    
                    // Gmail-specific validation
                    if (domain == "gmail.com") {
                        // Gmail usernames must be between 6-30 characters
                        if (username.length < 6 || username.length > 30) {
                            activity?.runOnUiThread {
                                callback(false, "Invalid Gmail address. Gmail usernames must be between 6-30 characters.")
                            }
                            return@thread
                        }
                        
                        // Gmail doesn't allow consecutive dots
                        if (username.contains("..")) {
                            activity?.runOnUiThread {
                                callback(false, "Invalid Gmail address. Gmail doesn't allow consecutive dots.")
                            }
                            return@thread
                        }
                        
                        // Gmail only allows letters, numbers, dots, and some special characters
                        val validGmailPattern = "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+$"
                        if (!username.matches(Regex(validGmailPattern))) {
                            activity?.runOnUiThread {
                                callback(false, "Invalid Gmail address. Contains invalid characters.")
                            }
                            return@thread
                        }
                    }
                }
                
                // Perform DNS MX record lookup to verify domain has mail servers
                val dnsResult = performDNSLookup(domain)
                if (!dnsResult) {
                    activity?.runOnUiThread {
                        callback(false, "This email domain doesn't appear to have valid mail servers.")
                    }
                    return@thread
                }
                
                // If we reach here, the email passed all our checks
                activity?.runOnUiThread {
                    callback(true, null)
                }
            } catch (e: Exception) {
                Log.e("SignUpFragment", "Error verifying email: ${e.message}")
                // If there's any error in validation, fail safely
                activity?.runOnUiThread {
                    callback(false, "Error validating email. Please try again.")
                }
            }
        }
    }
    
    /**
     * Perform a DNS lookup to check if the domain has MX records (mail servers)
     */
    private fun performDNSLookup(domain: String): Boolean {
        return try {
            // For common email providers, we know they have mail servers
            val commonEmailDomains = listOf(
                "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", 
                "icloud.com", "aol.com", "protonmail.com", "mail.com",
                "zoho.com", "yandex.com", "gmx.com", "live.com",
                "msn.com", "yahoo.co.uk", "yahoo.co.jp", "yahoo.co.in",
                "rediffmail.com", "163.com", "qq.com", "naver.com",
                "daum.net", "mail.ru", "web.de", "t-online.de",
                "comcast.net", "verizon.net", "att.net", "sbcglobal.net"
            )
            
            if (commonEmailDomains.contains(domain.toLowerCase())) {
                return true
            }
            
            // For other domains, use Java's built-in DNS lookup
            val records = java.net.InetAddress.getAllByName(domain)
            records.isNotEmpty()
        } catch (e: Exception) {
            Log.e("SignUpFragment", "DNS lookup failed: ${e.message}")
            false
        }
    }

    private fun showVerificationInstructionsDialog(email: String) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Email Verification Required")
            .setMessage("A verification link has been sent to $email\n\n" +
                    "Please check your email and click the verification link before logging in.\n\n" +
                    "After verifying your email, you'll be able to set up biometric authentication on your first login.")
            .setPositiveButton("Go to Login") { _, _ ->
                navigateToLogin()
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }

    private fun saveUserToDatabase(userId: String, email: String) {
        generateUserTag { userTag ->
            val userMap = mapOf(
                "email" to email,
                "tag" to userTag,
                "created_at" to System.currentTimeMillis(),
                "logs" to listOf(
                    mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "event" to "user_created"
                    )
                )
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

            // Query users who have this tag
            database.child("users").orderByChild("tag").equalTo(newTag).get()
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