package com.example.limo_safe.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Utility class to manage biometric authentication in the app
 */
class BiometricManager(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("BiometricPrefs", Context.MODE_PRIVATE)
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyName = "limo_safe_biometric_key"
    
    companion object {
        const val BIOMETRIC_ENABLED_KEY = "biometric_enabled"
        const val USER_EMAIL_KEY = "user_email"
        const val PASSWORD_SUFFIX = "_password"
    }
    
    /**
     * Check if biometric authentication is available on the device
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = androidx.biometric.BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
    
    /**
     * Check if biometric authentication is enabled for the current user
     */
    fun isBiometricEnabled(): Boolean {
        return sharedPreferences.getBoolean(BIOMETRIC_ENABLED_KEY, false)
    }
    
    /**
     * Enable biometric authentication for the current user
     */
    fun enableBiometric(email: String, password: String = "") {
        sharedPreferences.edit()
            .putBoolean(BIOMETRIC_ENABLED_KEY, true)
            .putString(USER_EMAIL_KEY, email)
            .putString("${email}${PASSWORD_SUFFIX}", password)
            .apply()
        
        // Generate a key for biometric authentication if it doesn't exist
        if (!keyStore.containsAlias(keyName)) {
            generateSecretKey()
        }
    }
    
    /**
     * Disable biometric authentication for the current user
     */
    fun disableBiometric() {
        sharedPreferences.edit()
            .putBoolean(BIOMETRIC_ENABLED_KEY, false)
            .remove(USER_EMAIL_KEY)
            .apply()
    }
    
    /**
     * Get the email of the user who enabled biometric authentication
     */
    fun getBiometricEmail(): String? {
        return sharedPreferences.getString(USER_EMAIL_KEY, null)
    }
    
    /**
     * Get the stored password for the given email
     */
    fun getStoredPassword(email: String): String? {
        return sharedPreferences.getString("${email}${PASSWORD_SUFFIX}", null)
    }
    
    /**
     * Show biometric prompt for authentication
     */
    fun showBiometricPrompt(
        fragment: Fragment,
        title: String,
        subtitle: String,
        description: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
            
        val biometricPrompt = BiometricPrompt(
            fragment,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString)
                }
            }
        )
        
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Toast.makeText(context, "Biometric authentication error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Show biometric prompt for enrollment
     */
    fun showBiometricEnrollmentPrompt(
        fragment: Fragment,
        email: String,
        password: String = "",
        onSuccess: () -> Unit,
        onCancel: () -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Set Up Biometric Login")
            .setSubtitle("Use your fingerprint or face for quick login")
            .setDescription("This will allow you to log in to the app without entering your password")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
            
        val biometricPrompt = BiometricPrompt(
            fragment,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    enableBiometric(email, password)
                    onSuccess()
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || 
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        onCancel()
                    } else {
                        Toast.makeText(context, "Biometric enrollment error: $errString", Toast.LENGTH_SHORT).show()
                        onCancel()
                    }
                }
            }
        )
        
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Toast.makeText(context, "Biometric enrollment error: ${e.message}", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }
    
    /**
     * Generate a secret key for biometric authentication
     */
    private fun generateSecretKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyName,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
            
        keyGenerator.init(keyGenParameterSpec)
        keyGenerator.generateKey()
    }
    
    /**
     * Get the cipher for biometric authentication
     */
    fun getCipher(): Cipher {
        return Cipher.getInstance(
            KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7
        )
    }
}
