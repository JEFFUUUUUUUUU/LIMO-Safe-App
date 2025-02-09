package com.example.limo_safe.Object

import java.util.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object CodeGenerator {

    fun generateCode(length: Int = 6): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    fun generateAndSaveCodeIfSignedIn(): String {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser == null) {
            return "Please sign in"
        }

        val code = generateCode()
        val userId = currentUser.uid
        val db = FirebaseFirestore.getInstance()
        val userDocument = db.collection("users").document(userId)

        val otpData = hashMapOf(
            "otp" to code,
            "timestamp" to System.currentTimeMillis()
        )

        userDocument.set(otpData)
            .addOnSuccessListener {
                println("OTP saved for user: $userId")
            }
            .addOnFailureListener { e ->
                println("Error saving OTP: ${e.message}")
            }

        return code
    }
}