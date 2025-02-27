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

data class MorsePulse(val flash: Boolean, val duration: Long)

object MorseCodeHelper {
    private const val UNIT_TIME = 70L

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
        CoroutineScope(Dispatchers.Main).launch {
            for (pulse in pulses) {
                if (pulse.flash) {
                    toggleFlashlight(context, true)
                    delay(pulse.duration)
                    toggleFlashlight(context, false)
                } else {
                    delay(pulse.duration)
                }
            }
        }
    }

    // Toggles the flashlight ON/OFF
    private fun toggleFlashlight(context: Context, turnOn: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cameraManager.setTorchMode(cameraId, turnOn)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(context, "Error accessing camera flash: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
