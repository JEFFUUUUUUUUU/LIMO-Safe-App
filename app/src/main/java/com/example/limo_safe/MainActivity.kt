
package com.example.limo_safe

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var pressToEnterButton: Button
    private lateinit var logoImageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        pressToEnterButton = findViewById(R.id.pressToEnterButton)
        logoImageView = findViewById(R.id.logoImageView)

        // Set the logo image
        logoImageView.setImageResource(R.drawable.limosafelogo)

        pressToEnterButton.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
