package com.example.limo_safe

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.limo_safe.utils.AppFlags

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Disable biometric auth during splash
        AppFlags.allowBiometricAuthentication = false

        Handler(Looper.getMainLooper()).postDelayed({
            // Enable biometric auth just before launching MainActivity
            AppFlags.allowBiometricAuthentication = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 3000)
    }
}