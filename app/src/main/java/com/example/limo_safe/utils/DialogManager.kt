package com.example.limo_safe.utils

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.limo_safe.MainActivity
import com.example.limo_safe.Object.SessionManager
import com.example.limo_safe.R

class DialogManager(private val context: Context) {

    private var activeDialog: AlertDialog? = null
    private var triesTextView: TextView? = null
    private val sessionManager: SessionManager by lazy {
        (context as MainActivity).sessionManager
    }

    init {
        // Set up session timeout listener
        sessionManager.setOnSessionTimeoutListener {
            dismissActiveDialog()
        }
    }

    private fun setupTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                sessionManager.userActivityDetected()
            }
            false
        }
    }

    private fun setupDialogTouchListener(dialog: AlertDialog) {
        dialog.window?.decorView?.let { decorView ->
            setupTouchListener(decorView)
        }
    }

    fun createMorseCodeDialog(
        code: String,
        remainingTries: Int,
        remainingCooldown: Long = 0,
        onPlayClick: (Button, TextView) -> Unit
    ): AlertDialog {
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
    ): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_morse_code, null)
        setupTouchListener(dialogView)

        val codeDisplayText = dialogView.findViewById<TextView>(R.id.codeDisplayText)
        val triesText = dialogView.findViewById<TextView>(R.id.triesText)
        val cooldownText = dialogView.findViewById<TextView>(R.id.cooldownText)
        val playButton = dialogView.findViewById<Button>(R.id.playButton)

        codeDisplayText.text = code
        triesText.text = "Remaining tries: $remainingTries"
        this.triesTextView = triesText

        if (remainingCooldown > 0) {
            cooldownText.visibility = View.VISIBLE
            cooldownText.text = "Cooldown: ${remainingCooldown}s"
            playButton.isEnabled = false
        } else {
            cooldownText.visibility = View.GONE
            playButton.isEnabled = true
        }

        playButton.setOnClickListener {
            onPlayClick(playButton, cooldownText)
            sessionManager.userActivityDetected()
        }

        val builder = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)

        return builder.create()
    }

    fun createCustomDialog(
        layoutResId: Int
    ): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(layoutResId, null)
        setupTouchListener(dialogView)

        val dialog = AlertDialog.Builder(context)
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

        val dialog = AlertDialog.Builder(context)
            .setTitle("Maximum Tries Reached")
            .setMessage("You have reached the maximum number of tries.")
            .setPositiveButton("OK") { dialog, _ ->
                sessionManager.userActivityDetected()
                dialog.dismiss()
            }
            .create()
        setupDialogTouchListener(dialog)
        dialog.show()
    }

    fun showExitConfirmationDialog(onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Exit Confirmation")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { dialog, _ ->
                sessionManager.userActivityDetected()
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton("No") { dialog, _ ->
                sessionManager.userActivityDetected()
                dialog.dismiss()
            }
            .create()
        setupDialogTouchListener(dialog)
        dialog.show()
    }

    fun showErrorDialog(title: String, message: String) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                sessionManager.userActivityDetected()
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

        val dialog = AlertDialog.Builder(context)
            .setView(layout)
            .setCancelable(false)
            .create()
        setupDialogTouchListener(dialog)
        activeDialog = dialog
        return dialog
    }

    fun dismissActiveDialog() {
        activeDialog?.dismiss()
        activeDialog = null
        triesTextView = null
    }

    companion object {
        fun createLoadingDialog(context: Context): AlertDialog {
            return DialogManager(context).createLoadingDialog()
        }
    }
}