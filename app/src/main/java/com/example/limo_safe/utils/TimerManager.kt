package com.example.limo_safe.utils

import android.os.CountDownTimer
import android.widget.Button
import android.widget.TextView

class TimerManager {
    private var generateTimer: CountDownTimer? = null
    private var morseTimer: CountDownTimer? = null

    fun startGenerateButtonCooldown(
        duration: Long,
        button: Button,
        onFinish: () -> Unit
    ) {
        generateTimer?.cancel()
        button.isEnabled = false
        button.alpha = 0.5f

        generateTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = (millisUntilFinished / 1000) % 60
                button.text = "Generate Code (${minutes}:${String.format("%02d", seconds)})"
            }

            override fun onFinish() {
                button.isEnabled = true
                button.alpha = 1.0f
                button.text = "Generate Code"
                onFinish()
            }
        }.start()
    }

    fun startMorseCooldown(
        duration: Long,
        button: Button,
        cooldownText: TextView,
        onFinish: () -> Unit
    ) {
        morseTimer?.cancel()
        button.isEnabled = false
        cooldownText.text = "Please wait ${duration / 1000} seconds before next try"

        morseTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                cooldownText.text = "Please wait ${seconds} seconds before next try"
            }

            override fun onFinish() {
                cooldownText.text = "No cooldown - Ready to play"
                button.isEnabled = true
                onFinish()
            }
        }.start()
    }

    fun cancelTimers() {
        generateTimer?.cancel()
        morseTimer?.cancel()
    }
}