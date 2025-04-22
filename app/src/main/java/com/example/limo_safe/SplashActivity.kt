package com.example.limo_safe

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.limo_safe.utils.AppFlags

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // Disable biometric auth during splash
        AppFlags.allowBiometricAuthentication = false

        // Enable biometric auth and immediately redirect to MainActivity
        AppFlags.allowBiometricAuthentication = true

        // Start MainActivity directly
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)

        // Finish this activity so user can't go back to it
        finish()
    }
}