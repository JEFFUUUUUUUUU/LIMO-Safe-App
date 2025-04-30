package com.example.limo_safe.utils

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MorsePulse(val flash: Boolean, val duration: Long)

/**
 * Helper class for Morse code functionality.
 * This class is designed to be instantiated when needed rather than used as a singleton
 * to avoid context-related memory leaks.
 */
class MorseCodeHelper(context: Context) {
    // Use application context to prevent memory leaks
    private val applicationContext = context.applicationContext
    private val cameraManager: CameraManager? = applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Morse code timing constants
    companion object {
        private const val UNIT_TIME = 60L

        // Morse Code Mapping
        private val morseCodeMap = mapOf(
            // Uppercase letters
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
            'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
            'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
            'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
            'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
            'Z' to "--..",

            // Numbers
            '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
            '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..",
            '9' to "----.", '0' to "-----",

            // Symbols
            '_' to "..--.-"
        )

        /**
         * Convert text to morse code string
         */
        fun textToMorse(text: String): String {
            val result = StringBuilder()
            for (char in text.uppercase()) {
                if (char == ' ') {
                    result.append("   ")
                } else {
                    val morse = morseCodeMap[char]
                    if (morse != null) {
                        result.append(morse).append(" ")
                    }
                }
            }
            return result.toString().trim()
        }

        /**
         * Converts a string to Morse code pulses
         */
        fun convertToMorsePulseSequence(input: String): List<MorsePulse> {
            val pulses = mutableListOf<MorsePulse>()

            for (char in input.uppercase()) {
                if (char == ' ') {
                    pulses.add(MorsePulse(false, UNIT_TIME * 7))
                } else {
                    val morse = morseCodeMap[char] ?: continue
                    for ((index, symbol) in morse.withIndex()) {
                        val onDuration = if (symbol == '.') UNIT_TIME else UNIT_TIME * 3
                        pulses.add(MorsePulse(true, onDuration))
                        if (index < morse.length - 1) {
                            pulses.add(MorsePulse(false, UNIT_TIME))
                        }
                    }
                    pulses.add(MorsePulse(false, UNIT_TIME * 4))
                }
            }
            return pulses
        }
    }

    /**
     * Play morse code for a given text
     */
    fun playMorseCode(text: String) {
        val pulses = convertToMorsePulseSequence(text)
        playMorsePulseSequence(pulses)
    }

    /**
     * Clean up resources and ensure flashlight is turned off
     */
    fun cleanup() {
        try {
            toggleFlashlight(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Plays Morse pulses using flashlight
     */
    fun playMorsePulseSequence(pulses: List<MorsePulse>) {
        CoroutineScope(Dispatchers.Default).launch {
            // Calculate total duration
            val totalDuration = pulses.sumOf { it.duration }

            // Execute all pulses
            for (pulse in pulses) {
                if (pulse.flash) {
                    toggleFlashlight(true)
                    delay(pulse.duration)
                    toggleFlashlight(false)
                } else {
                    delay(pulse.duration)
                }
            }

            // After all pulses are complete, show toast on main thread
            withContext(Dispatchers.Main) {
                delay(100) // Small delay to ensure all flashes are complete
                showToast("Code Transmission complete")
            }
        }
    }

    /**
     * Shows toast on the main thread
     */
    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Toggles the flashlight ON/OFF
     */
    private fun toggleFlashlight(turnOn: Boolean) {
        try {
            cameraManager?.let { cm ->
                for (cameraId in cm.cameraIdList) {
                    val characteristics = cm.getCameraCharacteristics(cameraId)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    if (hasFlash) {
                        cm.setTorchMode(cameraId, turnOn)
                        break
                    }
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            showToast("Error accessing camera flash: ${e.message}")
        }
    }
}