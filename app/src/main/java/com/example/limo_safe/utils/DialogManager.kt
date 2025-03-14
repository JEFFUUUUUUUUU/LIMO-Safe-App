package com.example.limo_safe.utils

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.limo_safe.R

class DialogManager(private val context: Context) {

    private var activeDialog: AlertDialog? = null
    private var triesTextView: TextView? = null

    fun createMorseCodeDialog(
        code: String,
        remainingTries: Int,
        remainingCooldown: Long = 0,
        onPlayClick: (Button, TextView) -> Unit
    ): AlertDialog {
        val dialog = createMorseCodeDialogInternal(code, remainingTries, remainingCooldown, onPlayClick)
        activeDialog = dialog
        return dialog
    }

    fun createMorseCodeDialogInternal(
        code: String,
        remainingTries: Int,
        remainingCooldown: Long = 0,
        onPlayClick: (Button, TextView) -> Unit
    ): AlertDialog {
        // Inflate the XML layout
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_morse_code, null)

        // Find views from the layout
        val codeDisplayText = dialogView.findViewById<TextView>(R.id.codeDisplayText)
        val triesText = dialogView.findViewById<TextView>(R.id.triesText)
        val cooldownText = dialogView.findViewById<TextView>(R.id.cooldownText)
        val playButton = dialogView.findViewById<Button>(R.id.playButton)

        // Set values
        codeDisplayText.text = code
        triesText.text = "Remaining tries: $remainingTries"

        // Initialize cooldown if needed
        if (remainingCooldown > 0) {
            cooldownText.text = "Please wait ${remainingCooldown / 1000} seconds before next try"
            playButton.isEnabled = false
            playButton.alpha = 0.5f
        } else {
            cooldownText.text = ""
        }

        // Set button click listener
        playButton.setOnClickListener { onPlayClick(playButton, cooldownText) }

        // Store reference for updating tries later
        triesTextView = triesText

        return AlertDialog.Builder(context)
            .setTitle("Play Morse Code")
            .setView(dialogView)
            .setCancelable(false)
            .create()
    }

    fun createCustomDialog(layoutResId: Int): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(layoutResId, null)
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        
        // Apply theme colors to the dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.white)
        
        // Store as active dialog for session management
        activeDialog = dialog
        return dialog
    }

    fun updateRemainingTries(tries: Int) {
        triesTextView?.text = "Remaining tries: $tries"
    }

    fun showMaxTriesDialog() {
        // Dismiss any active morse code dialog
        activeDialog?.dismiss()
        activeDialog = null
        triesTextView = null

        AlertDialog.Builder(context)
            .setTitle("Maximum Tries Reached")
            .setMessage("You have reached the maximum number of tries.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    fun showExitConfirmationDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
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
            .show()
    }

    fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    fun createLoadingDialog(title: String = "Loading..."): AlertDialog {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
            gravity = Gravity.CENTER
        }

        // Progress Bar
        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
            indeterminateTintList = ContextCompat.getColorStateList(context, R.color.orange)
        }

        // Loading text
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

        val dialog = AlertDialog.Builder(context)
            .setView(layout)
            .setCancelable(false)
            .create()

        // Store as active dialog for session management
        activeDialog = dialog
        return dialog
    }

    fun dismissActiveDialog() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    companion object {
        fun createLoadingDialog(context: Context): AlertDialog {
            return DialogManager(context).createLoadingDialog()
        }
    }
}