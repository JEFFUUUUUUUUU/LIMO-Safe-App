#include "LightSensor.h"
#include "MorseDecoder.h"
#include "FirebaseHandler.h"

// Define global variables (only once)
String receivedMorse = "";
String receivedOTP = "";
unsigned long lastChangeTime = 0;
bool lastState = false;

void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT);
    lastChangeTime = millis();
}

void processLightInput() {
    int lightValue = analogRead(LIGHT_SENSOR_PIN);
    bool currentState = (lightValue > THRESHOLD);
    unsigned long currentTime = millis();

    static bool receivingMorse = false;  // Tracks if Morse input is ongoing

    if (currentState != lastState) {
        unsigned long duration = currentTime - lastChangeTime;

        if (duration < DEBOUNCE_TIME) {
            lastChangeTime = currentTime;
            lastState = currentState;
            return;
        }

        lastChangeTime = currentTime;

        if (lastState) { // ON → OFF (End of Pulse)
            receivedMorse += (duration >= 175) ? "-" : ".";
            receivingMorse = true;  // Morse input is active
        } else { // OFF → ON (Start of Pause)
            if (receivedMorse.length() > 0) {
                if (duration >= WORD_GAP_DURATION) {
                    receivedMorse += " / ";  // Word gap
                } else if (duration >= LETTER_GAP_DURATION) {
                    receivedMorse += " ";    // Letter gap
                }
            }
        }
    }

    lastState = currentState;

    // 🔥 FIX: Decode only if Morse input was active and a long pause is detected
    if (receivingMorse && (currentTime - lastChangeTime) > MESSAGE_TIMEOUT) {
        Serial.print(F("Full Morse Sequence Received: "));
        Serial.println(receivedMorse);

        receivedOTP = decodeMorse(receivedMorse);  // Store OTP

        // Check OTP length before verifying
        if (receivedOTP.length() == 7) {  // Exactly 7 characters required
            Serial.print(F("Decoded OTP: "));
            Serial.println(receivedOTP);

            // Pass the full OTP directly for verification
            if (verifyOTP(receivedOTP)) {
                Serial.println("✅ Access Granted!");
                // Send success signal to Nano instead of resetting
                // TODO: Implement success signal handling
            } else {
                Serial.println("❌ Invalid Code!");
            }
        } else {
            Serial.println("⚠ Invalid Code Length! Must be exactly 7 characters.");
        }

        // Reset states
        receivedMorse = "";  
        receivedOTP = "";
        receivingMorse = false;
    }
}
