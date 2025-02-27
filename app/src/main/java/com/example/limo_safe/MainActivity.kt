package com.example.limo_safe

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var auth: FirebaseAuth
    private lateinit var mainContent: LinearLayout
    private lateinit var pressToEnterButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        supportFragmentManager.addOnBackStackChangedListener(this)

        mainContent = findViewById(R.id.mainContent)
        pressToEnterButton = findViewById(R.id.pressToEnterButton)

        pressToEnterButton.setOnClickListener {
            navigateToLogin()
        }

        // Check if user is already logged in
        if (savedInstanceState == null) {
            val currentUser = auth.currentUser
            if (currentUser != null && !currentUser.isEmailVerified) {
                // If email is not verified, show login screen
                navigateToLogin()
            }
        }
    }

    private fun navigateToLogin() {
        mainContent.visibility = View.GONE
        val loginFragment = LoginFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, loginFragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToMC() {
        mainContent.visibility = View.GONE
        val mcFragment = MCFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, mcFragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onBackStackChanged() {
        mainContent.visibility = if (supportFragmentManager.backStackEntryCount == 0) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        supportFragmentManager.removeOnBackStackChangedListener(this)
    }
}
