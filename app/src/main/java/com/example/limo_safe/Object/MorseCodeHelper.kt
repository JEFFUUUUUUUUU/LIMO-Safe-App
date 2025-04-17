package com.example.limo_safe.utils

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.CountDownTimer
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MorsePulse(val flash: Boolean, val duration: Long)

object MorseCodeHelper {
    private const val UNIT_TIME = 70L
    private var cameraManager: CameraManager? = null
    private var activeContext: Context? = null

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

    // Play morse code for a given text
    fun playMorseCode(text: String) {
        if (activeContext == null) {
            return
        }

        val pulses = convertToMorsePulseSequence(text)
        playMorsePulseSequence(activeContext!!, pulses)
    }

    // Turn flashlight on
    fun flashlightOn() {
        if (activeContext == null) {
            return
        }
        toggleFlashlight(activeContext!!, true)
    }

    // Turn flashlight off
    fun flashlightOff() {
        if (activeContext == null) {
            return
        }
        toggleFlashlight(activeContext!!, false)
    }

    // Convert text to morse code
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

    // Initialize with context
    fun initialize(context: Context) {
        activeContext = context
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // Cleanup resources
    fun cleanup() {
        try {
            if (activeContext != null) {
                toggleFlashlight(activeContext!!, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cameraManager = null
            activeContext = null
        }
    }

    // Converts a string to Morse code pulses
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
                pulses.add(MorsePulse(false, UNIT_TIME * 5))
            }
        }
        return pulses
    }

    // Plays Morse pulses using flashlight
    fun playMorsePulseSequence(context: Context, pulses: List<MorsePulse>) {
        CoroutineScope(Dispatchers.Default).launch {
            // Calculate total duration
            val totalDuration = pulses.sumOf { it.duration }

            // Execute all pulses
            for (pulse in pulses) {
                if (pulse.flash) {
                    toggleFlashlight(context, true)
                    delay(pulse.duration)
                    toggleFlashlight(context, false)
                } else {
                    delay(pulse.duration)
                }
            }

            // After all pulses are complete, show toast on main thread
            withContext(Dispatchers.Main) {
                delay(100) // Small delay to ensure all flashes are complete
                Toast.makeText(context, "Code Transmission complete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Toggles the flashlight ON/OFF
    private fun toggleFlashlight(context: Context, turnOn: Boolean) {
        val cm = cameraManager ?: context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cm.cameraIdList) {
                val characteristics = cm.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cm.setTorchMode(cameraId, turnOn)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(context, "Error accessing camera flash: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}