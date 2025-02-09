package com.example.limo_safe.Object

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

object FirebaseAuthHelper {

    private val auth: FirebaseAuth = Firebase.auth

    suspend fun signUpWithEmailAndPassword(email: String, password: String): Boolean {
        return try {
            auth.createUserWithEmailAndPassword(email, password).await()
            true
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            // Invalid email or password format
            Log.e("FirebaseAuthHelper", "Sign-up failed: ${e.message}")
            false
        } catch (e: FirebaseAuthUserCollisionException) {
            // Email already in use
            Log.e("FirebaseAuthHelper", "Sign-up failed: ${e.message}")
            false
        } catch (e: Exception) {
            // Other errors
            Log.e("FirebaseAuthHelper", "Sign-up failed: ${e.message}")
            false
        }
    }

    suspend fun signInWithEmailAndPassword(email: String, password: String): Boolean {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            true
        } catch (e: FirebaseAuthInvalidUserException) {
            // Invalid user (e.g., user deleted, disabled)
            Log.e("FirebaseAuthHelper", "Sign-in failed: ${e.message}")
            false
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            // Invalid email or password
            Log.e("FirebaseAuthHelper", "Sign-in failed: ${e.message}")
            false
        } catch (e: Exception) {
            // Other errors
            Log.e("FirebaseAuthHelper", "Sign-in failed: ${e.message}")
            false
        }
    }
}