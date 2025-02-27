package com.example.limo_safe.utils

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

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

    private fun createMorseCodeDialogInternal(
        code: String,
        remainingTries: Int,
        remainingCooldown: Long = 0,
        onPlayClick: (Button, TextView) -> Unit
    ): AlertDialog {
        // Create a custom layout for the dialog
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        // Code display text - only show OTP part
        val codeDisplayTextView = TextView(context).apply {
            val otp = code.drop(1)  // Remove tag character
            val fullText = "Code: $otp"
            
            val spannableString = SpannableString(fullText)
            spannableString.setSpan(
                StyleSpan(Typeface.BOLD),
                fullText.indexOf(otp),
                fullText.indexOf(otp) + otp.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            text = spannableString
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(context.resources.getColor(android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        // Main instruction text
        val instructionText = TextView(context).apply {
            text = "Align first your phone flashlight to the Sensor of your LIMO-Safe while transmitting data. Thank you"
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        // Remaining tries text
        val triesText = TextView(context).apply {
            text = "Remaining tries: $remainingTries"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(context.resources.getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        triesTextView = triesText  // Store reference to update later

        // Cooldown text
        val cooldownText = TextView(context).apply {
            visibility = android.view.View.VISIBLE
            text = if (remainingCooldown > 0) "Please wait ${remainingCooldown / 1000} seconds before next try" else ""
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(context.resources.getColor(android.R.color.holo_orange_dark))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
            }
        }

        // Create custom button layout
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(50, 20, 50, 20)
        }

        val playButton = Button(context).apply {
            text = "Play Morse Code"
            isEnabled = remainingCooldown <= 0
            alpha = if (remainingCooldown <= 0) 1.0f else 0.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onPlayClick(this, cooldownText) }
        }

        layout.apply {
            addView(codeDisplayTextView)
            addView(instructionText)
            addView(triesText)
            addView(cooldownText)
            buttonLayout.addView(playButton)
            addView(buttonLayout)
        }

        return AlertDialog.Builder(context)
            .setTitle("Play Morse Code")
            .setView(layout)
            .setCancelable(false)
            .create()
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
}
