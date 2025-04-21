package com.example.limo_safe.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.limo_safe.R

class DialogManager(private val context: Context) {

    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var triesTextView: TextView? = null

    fun dismissActiveDialog() {
        try {
            if (activeDialog?.isShowing == true) {
                activeDialog?.dismiss()
            }
            activeDialog = null
            triesTextView = null  // Added from the removed duplicate method
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun setupTouchListener(view: View) {
        // Touch listener simplified without session management
        view.setOnTouchListener { _, _ -> false }
    }

    private fun setupDialogTouchListener(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.decorView?.let { decorView ->
            setupTouchListener(decorView)
        }
    }

    fun createMorseCodeDialog(
        code: String,
        remainingTries: Int,
        remainingCooldown: Long = 0,
        onPlayClick: (Button, TextView) -> Unit
    ): androidx.appcompat.app.AlertDialog {
        val dialog = createMorseCodeDialogInternal(code, remainingTries, remainingCooldown, onPlayClick)
        setupDialogTouchListener(dialog)
        activeDialog = dialog
        return dialog
    }

    fun createMorseCodeDialogInternal(
        code: String,
        remainingTries: Int,
        remainingCooldown: Long = 0,
        onPlayClick: (Button, TextView) -> Unit
    ): androidx.appcompat.app.AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_morse_code, null)
        setupTouchListener(dialogView)

        val codeDisplayText = dialogView.findViewById<TextView>(R.id.codeDisplayText)
        val triesText = dialogView.findViewById<TextView>(R.id.triesText)
        val cooldownText = dialogView.findViewById<TextView>(R.id.cooldownText)
        val playButton = dialogView.findViewById<Button>(R.id.playButton)
        val closeButton = dialogView.findViewById<Button>(R.id.closeButton)

        codeDisplayText.text = code
        triesText.text = "Remaining tries: $remainingTries"
        this.triesTextView = triesText

        if (remainingCooldown > 0) {
            // Calculate seconds remaining (round up to ensure we don't show 0 when there's still time)
            val seconds = (remainingCooldown + 999) / 1000
            
            // Only update button text with cooldown, hide the cooldown text
            cooldownText.visibility = View.GONE
            playButton.text = "PLAY MORSE CODE (${seconds})"
            playButton.isEnabled = false
        } else {
            cooldownText.visibility = View.GONE
            playButton.text = "PLAY MORSE CODE"
            playButton.isEnabled = true
        }

        playButton.setOnClickListener {
            onPlayClick(playButton, cooldownText)
        }

        closeButton.setOnClickListener {
            dismissActiveDialog()
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)

        return builder.create()
    }

    fun createCustomDialog(
        layoutResId: Int
    ): androidx.appcompat.app.AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(layoutResId, null)
        setupTouchListener(dialogView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.white)

        setupDialogTouchListener(dialog)
        activeDialog = dialog
        return dialog
    }

    fun updateTriesText(remainingTries: Int) {
        triesTextView?.text = "Remaining tries: $remainingTries"
    }

    fun showMaxTriesDialog() {
        activeDialog?.dismiss()
        activeDialog = null
        triesTextView = null

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Maximum Tries Reached")
            .setMessage("You have reached the maximum number of tries.")
            .setPositiveButton("OK") { dialog, _ ->

                dialog.dismiss()
            }
            .create()
        setupDialogTouchListener(dialog)
        dialog.show()
    }

    fun showExitConfirmationDialog(onConfirm: () -> Unit) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Exit Confirmation")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { dialog, _ ->

                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton("No") { dialog, _ ->

                dialog.dismiss()
            }
            .create()
        setupDialogTouchListener(dialog)
        dialog.show()
    }

    fun showLogoutConfirmationDialog(onConfirm: () -> Unit) {
        try {
            // Inflate custom layout
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.fragment_logout, null)

            // Create dialog with custom view
            val dialog = android.app.Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(view)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Make dialog more compact (80% of screen width)
            val displayMetrics = context.resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.8).toInt()
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.setCancelable(false) // Prevent dismissing by tapping outside

            // Set up button click listeners
            val cancelButton = view.findViewById<Button>(R.id.cancelButton)
            val logoutButton = view.findViewById<Button>(R.id.logoutButton)

            cancelButton.setOnClickListener {
                dialog.dismiss()
            }

            logoutButton.setOnClickListener {
                dialog.dismiss()

                try {
                    // Sign out from Firebase first
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

                    // Get the activity context
                    val activity = context as? com.example.limo_safe.MainActivity
                    if (activity != null && !activity.isFinishing) {
                        // Simply create and show the login fragment
                        val loginFragment = com.example.limo_safe.LoginFragment()

                        // Make sure fragment container is visible
                        activity.findViewById<View>(R.id.fragmentContainer)?.visibility = View.VISIBLE

                        // Replace current fragment with login fragment
                        activity.supportFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer, loginFragment)
                            .commit()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DialogManager", "Error during logout: ${e.message}")
                }
            }

            // Show the dialog
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("DialogManager", "Error showing logout dialog: ${e.message}")
            e.printStackTrace()

            // Fallback to standard dialog if custom one fails
            val fallbackDialog = androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Logout Confirmation")
                .setMessage("Are you sure you want to log out of your account?")
                .setPositiveButton("Yes, Log Out") { dialog, _ ->
                    dialog.dismiss()

                    try {
                        // Sign out from Firebase first
                        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

                        // Get the activity context
                        val activity = context as? com.example.limo_safe.MainActivity
                        if (activity != null && !activity.isFinishing) {
                            // Simply create and show the login fragment
                            val loginFragment = com.example.limo_safe.LoginFragment()

                            // Make sure fragment container is visible
                            activity.findViewById<View>(R.id.fragmentContainer)?.visibility = View.VISIBLE

                            // Replace current fragment with login fragment
                            activity.supportFragmentManager.beginTransaction()
                                .replace(R.id.fragmentContainer, loginFragment)
                                .commit()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DialogManager", "Error during logout (fallback): ${e.message}")
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()
            setupDialogTouchListener(fallbackDialog)
            fallbackDialog.show()
        }
    }

    fun showErrorDialog(title: String, message: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->

                dialog.dismiss()
            }
            .create()
        setupDialogTouchListener(dialog)
        dialog.show()
    }

    fun createLoadingDialog(title: String = "Loading..."): AlertDialog {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
            gravity = Gravity.CENTER
        }

        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
            indeterminateTintList = ContextCompat.getColorStateList(context, R.color.orange)
        }

        val loadingText = TextView(context).apply {
            text = title
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.maroon))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.apply {
            addView(progressBar)
            addView(loadingText)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(layout)
            .setCancelable(false)
            .create()
        setupDialogTouchListener(dialog)
        activeDialog = dialog
        return dialog
    }

    /**
     * Shows a dialog asking the user if they want to enable biometric authentication
     */
    fun showBiometricEnrollmentDialog(
        email: String,
        onEnable: () -> Unit,
        onCancel: () -> Unit
    ): androidx.appcompat.app.AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_biometric_prompt, null)
        setupTouchListener(dialogView)

        val enableButton = dialogView.findViewById<Button>(R.id.biometricEnableButton)
        val cancelButton = dialogView.findViewById<Button>(R.id.biometricCancelButton)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        enableButton.setOnClickListener {
            dialog.dismiss()
            onEnable()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        setupDialogTouchListener(dialog)
        activeDialog = dialog
        return dialog
    }

    companion object {
        fun createLoadingDialog(context: Context): androidx.appcompat.app.AlertDialog {
            return DialogManager(context).createLoadingDialog()
        }
    }
}