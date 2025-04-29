package com.example.limo_safe.utils

import android.content.SharedPreferences
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Centralized manager for handling all timer-related functionality
 * This class manages cooldown timers, expiration timers, and UI updates
 */
class TimerManager(private val sharedPreferences: SharedPreferences) {
    
    // Timer instances
    private var generateTimer: CountDownTimer? = null
    private var morseTimer: CountDownTimer? = null
    private var expirationTimer: CountDownTimer? = null
    
    // SharedPreferences keys
    private val GENERATE_COOLDOWN_END_KEY = "generate_cooldown_end"
    private val MORSE_COOLDOWN_END_KEY = "morse_cooldown_end_time"
    private val EXPIRATION_END_TIME_KEY = "expiration_end_time"
    
    // Time values
    private var generateCooldownEndTime: Long = 0
    private var morseCooldownEndTime: Long = 0
    private var expirationEndTime: Long = 0
    
    /**
     * Starts the generate button cooldown timer
     * @param duration The duration of the cooldown in milliseconds
     * @param button The generate button to update
     * @param onFinish Callback when the timer finishes
     */
    fun startGenerateButtonCooldown(
        duration: Long,
        button: Button,
        onFinish: () -> Unit
    ) {
        // Cancel any existing timer
        generateTimer?.cancel()
        
        // Update button state
        button.isEnabled = false
        button.alpha = 0.5f
        
        // Set and save the cooldown end time
        generateCooldownEndTime = System.currentTimeMillis() + duration
        sharedPreferences.edit().putLong(GENERATE_COOLDOWN_END_KEY, generateCooldownEndTime).apply()
        
        // Create and start the timer
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
                generateCooldownEndTime = 0
                sharedPreferences.edit().putLong(GENERATE_COOLDOWN_END_KEY, 0).apply()
                onFinish()
            }
        }.start()
        
        Log.d("TimerManager", "Started generate button cooldown: ${duration}ms")
    }
    
    /**
     * Starts the Morse code cooldown timer
     * @param duration The duration of the cooldown in milliseconds
     * @param playButton The play button to update
     * @param cooldownText The cooldown text view to update
     * @param onFinish Callback when the timer finishes
     */
    fun startMorseCooldown(
        duration: Long,
        playButton: Button,
        cooldownText: TextView? = null,
        onFinish: () -> Unit
    ) {
        // Cancel any existing timer
        morseTimer?.cancel()
        
        // Set and save the cooldown end time
        morseCooldownEndTime = System.currentTimeMillis() + duration
        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, morseCooldownEndTime).apply()
        
        // Update UI for cooldown state
        playButton.isEnabled = false
        playButton.alpha = 0.5f
        
        if (cooldownText != null) {
            cooldownText.visibility = View.GONE
        }
        
        // Calculate seconds remaining (round up to ensure we don't show 0 when there's still time)
        val secondsRemaining = (duration + 999) / 1000
        playButton.text = "PLAY MORSE CODE (${secondsRemaining})"
        
        // Create and start the timer
        morseTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished + 999) / 1000 // Round up
                playButton.text = "PLAY MORSE CODE (${seconds})"
                Log.d("TimerManager", "Morse cooldown tick: ${seconds}s remaining")
            }
            
            override fun onFinish() {
                playButton.isEnabled = true
                playButton.alpha = 1.0f
                playButton.text = "PLAY MORSE CODE"
                morseCooldownEndTime = 0
                sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0).apply()
                onFinish()
                Log.d("TimerManager", "Morse cooldown timer finished")
            }
        }.start()
        
        Log.d("TimerManager", "Started Morse cooldown: ${duration}ms")
    }
    
    /**
     * Starts the expiration timer for the generated code
     * @param duration The duration before the code expires in milliseconds
     * @param titleText Optional dialog title text to update with countdown
     * @param onFinish Callback when the timer finishes (code expires)
     */
    fun startExpirationTimer(
        duration: Long,
        titleText: TextView? = null,
        onFinish: () -> Unit
    ) {
        // Cancel any existing timer
        expirationTimer?.cancel()
        
        // Set and save the expiration end time
        expirationEndTime = System.currentTimeMillis() + duration
        sharedPreferences.edit().putLong(EXPIRATION_END_TIME_KEY, expirationEndTime).apply()
        
        // Initialize the title with the starting time
        if (titleText != null) {
            val initialSeconds = duration / 1000
            titleText.text = "Play Morse Code (${initialSeconds}s)"
            Log.d("TimerManager", "** TIMER START: Setting title text to: ${titleText.text}, titleText object: $titleText")
        } else {
            Log.d("TimerManager", "** TIMER START: titleText is null, can't update UI")
        }
        
        Log.d("TimerManager", "Setting expiration end time to: $expirationEndTime (${duration}ms from now)")
        
        // Create and start the timer
        expirationTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Update UI if titleText is provided
                if (titleText != null) {
                    val seconds = (millisUntilFinished + 999) / 1000 // Round up
                    val newText = "Play Morse Code (${seconds}s)"
                    titleText.text = newText
                    Log.d("TimerManager", "** TIMER TICK: Setting title to: $newText, titleText=$titleText, remaining=${millisUntilFinished}ms")
                    
                    try {
                        // Check if the TextView is attached to a window (visible)
                        val isAttached = titleText.isAttachedToWindow
                        Log.d("TimerManager", "** TIMER TICK: TextView attached to window: $isAttached")
                    } catch (e: Exception) {
                        Log.e("TimerManager", "** TIMER TICK: Error checking TextView attachment: ${e.message}")
                    }
                } else {
                    Log.d("TimerManager", "** TIMER TICK: titleText is null, can't update UI. Remaining: ${millisUntilFinished}ms")
                }
            }
            
            override fun onFinish() {
                expirationEndTime = 0
                sharedPreferences.edit().putLong(EXPIRATION_END_TIME_KEY, 0).apply()
                Log.d("TimerManager", "** TIMER FINISH: Expiration timer finished, calling onFinish")
                onFinish()
            }
        }.start()
        
        Log.d("TimerManager", "Started expiration timer with ${duration}ms remaining")
    }
    
    /**
     * Gets the remaining time for the generate button cooldown
     * @return remaining time in milliseconds, 0 if expired or not started
     */
    fun getRemainingGenerateCooldownTime(): Long {
        val currentTime = System.currentTimeMillis()
        val savedCooldownTime = sharedPreferences.getLong(GENERATE_COOLDOWN_END_KEY, 0)
        return if (savedCooldownTime > currentTime) savedCooldownTime - currentTime else 0
    }
    
    /**
     * Resumes timers if they were active when the app was paused
     * @param generateButton The generate button to update
     * @param playButton Optional Morse play button to update
     * @param cooldownText Optional cooldown text to update
     * @param titleText Optional dialog title to update
     * @param onGenerateFinish Callback when generate timer finishes
     * @param onMorseFinish Callback when Morse timer finishes
     * @param onExpirationFinish Callback when expiration timer finishes
     */
    fun resumeTimersIfNeeded(
        generateButton: Button? = null,
        playButton: Button? = null,
        cooldownText: TextView? = null,
        titleText: TextView? = null,
        onGenerateFinish: () -> Unit = {},
        onMorseFinish: () -> Unit = {},
        onExpirationFinish: () -> Unit = {}
    ) {
        val currentTime = System.currentTimeMillis()
        
        // Load saved timers from SharedPreferences
        generateCooldownEndTime = sharedPreferences.getLong(GENERATE_COOLDOWN_END_KEY, 0)
        morseCooldownEndTime = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
        expirationEndTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        
        // Resume generate button cooldown if needed
        if (generateCooldownEndTime > currentTime && generateButton != null) {
            val remainingTime = generateCooldownEndTime - currentTime
            startGenerateButtonCooldown(remainingTime, generateButton, onGenerateFinish)
            Log.d("TimerManager", "Resumed generate button cooldown with ${remainingTime}ms remaining")
        }
        
        // Resume Morse cooldown if needed
        if (morseCooldownEndTime > currentTime && playButton != null) {
            val remainingTime = morseCooldownEndTime - currentTime
            startMorseCooldown(remainingTime, playButton, cooldownText, onMorseFinish)
            Log.d("TimerManager", "Resumed Morse cooldown with ${remainingTime}ms remaining")
        }
        
        // Resume expiration timer if needed
        if (expirationEndTime > currentTime) {
            val remainingTime = expirationEndTime - currentTime
            // Set initial title text immediately before starting the timer
            if (titleText != null) {
                val initialSeconds = remainingTime / 1000
                titleText.text = "Play Morse Code (${initialSeconds}s)"
                Log.d("TimerManager", "Setting initial expiration title: ${initialSeconds}s remaining")
            }
            startExpirationTimer(remainingTime, titleText, onExpirationFinish)
            Log.d("TimerManager", "Resumed expiration timer with ${remainingTime}ms remaining")
        }
    }
    
    /**
     * Checks if the code has expired
     * @return true if the code has expired, false otherwise
     */
    fun isCodeExpired(): Boolean {
        val currentTime = System.currentTimeMillis()
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        return savedExpirationTime > 0 && savedExpirationTime <= currentTime
    }
    
    /**
     * Checks if the generate button is in cooldown
     * @return true if in cooldown, false otherwise
     */
    fun isGenerateInCooldown(): Boolean {
        val currentTime = System.currentTimeMillis()
        val savedCooldownTime = sharedPreferences.getLong(GENERATE_COOLDOWN_END_KEY, 0)
        return savedCooldownTime > currentTime
    }
    
    /**
     * Checks if the Morse code is in cooldown
     * @return true if in cooldown, false otherwise
     */
    fun isMorseInCooldown(): Boolean {
        val currentTime = System.currentTimeMillis()
        val savedCooldownTime = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
        return savedCooldownTime > currentTime
    }
    
    /**
     * Gets the remaining time for the expiration timer
     * @return remaining time in milliseconds, 0 if expired or not started
     */
    fun getRemainingExpirationTime(): Long {
        val currentTime = System.currentTimeMillis()
        val savedExpirationTime = sharedPreferences.getLong(EXPIRATION_END_TIME_KEY, 0)
        return if (savedExpirationTime > currentTime) savedExpirationTime - currentTime else 0
    }
    
    /**
     * Gets the remaining time for the Morse cooldown
     * @return remaining time in milliseconds, 0 if expired or not started
     */
    fun getRemainingMorseCooldownTime(): Long {
        val currentTime = System.currentTimeMillis()
        val savedCooldownTime = sharedPreferences.getLong(MORSE_COOLDOWN_END_KEY, 0)
        return if (savedCooldownTime > currentTime) savedCooldownTime - currentTime else 0
    }
    
    /**
     * Resets the expiration timer
     */
    fun resetExpirationTimer() {
        expirationTimer?.cancel()
        expirationEndTime = 0
        sharedPreferences.edit().putLong(EXPIRATION_END_TIME_KEY, 0).apply()
        Log.d("TimerManager", "Reset expiration timer")
    }
    
    /**
     * Resets the Morse cooldown timer
     */
    fun resetMorseCooldown() {
        morseTimer?.cancel()
        morseCooldownEndTime = 0
        sharedPreferences.edit().putLong(MORSE_COOLDOWN_END_KEY, 0).apply()
        Log.d("TimerManager", "Reset Morse cooldown")
    }
    
    /**
     * Resets the generate button cooldown
     */
    fun resetGenerateCooldown() {
        generateTimer?.cancel()
        generateCooldownEndTime = 0
        sharedPreferences.edit().putLong(GENERATE_COOLDOWN_END_KEY, 0).apply()
        Log.d("TimerManager", "Reset generate cooldown")
    }
    
    /**
     * Cancels all timers and cleans up
     */
    fun cancelAllTimers() {
        generateTimer?.cancel()
        morseTimer?.cancel()
        expirationTimer?.cancel()
        Log.d("TimerManager", "Canceled all timers")
    }
}