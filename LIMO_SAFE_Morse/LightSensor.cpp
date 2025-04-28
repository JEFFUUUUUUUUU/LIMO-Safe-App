#include "LightSensor.h"
#include "MorseDecoder.h"
#include "FirebaseHandler.h"
#include "RGBLed.h"

// Define global variables (only once)
String receivedMorse = "";
String receivedOTP = "";
unsigned long lastChangeTime = 0;
bool lastState = false;
int currentThreshold = THRESHOLD_BASE;
unsigned long lastAdaptiveUpdate = 0;

void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT);
    lastChangeTime = millis();
    // Initialize threshold with first reading
    currentThreshold = getSmoothReading();
    if (currentThreshold < 100) currentThreshold = THRESHOLD_BASE; // Fallback if readings are too low
}

// Gets a smoothed sensor reading by taking multiple samples
int getSmoothReading() {
    int total = 0;
    for (int i = 0; i < 3; i++) {
        total += analogRead(LIGHT_SENSOR_PIN);
        delay(2);  // Short delay between readings
    }
    return total / 3;
}

// Adaptive threshold adjustment function
void updateAdaptiveThreshold() {
    unsigned long currentTime = millis();
    // Only update every 5 seconds to prevent rapid changes
    if (currentTime - lastAdaptiveUpdate > 5000) {
        int ambientLight = getSmoothReading();
        // Apply a moving average to the threshold (20% new, 80% old)
        currentThreshold = (currentThreshold * 0.8) + (ambientLight * 0.2);
        
        // Ensure threshold stays within reasonable bounds
        if (currentThreshold < 50) currentThreshold = 50;
        if (currentThreshold > 3000) currentThreshold = 3000;
        
        lastAdaptiveUpdate = currentTime;
    }
}

void processLightInput() {
    // These should NOT be static - they're already global!
    // Static variables for function state
    static bool receivingMorse = false;
    static unsigned long lastProcessTime = 0;
    
    // Only read sensor every few milliseconds to reduce CPU load
    unsigned long currentTime = millis();
    if (currentTime - lastProcessTime < 5) { // 5ms sampling rate is sufficient for Morse
        return;
    }
    lastProcessTime = currentTime;
    
    // Update threshold adaptively
    updateAdaptiveThreshold();
    
    // Read light sensor with smoothing
    int lightValue = getSmoothReading();
    bool currentState = (lightValue > currentThreshold);
    
    // Ignore state changes that are too quick (debounce)
    if (currentState != lastState) {
        unsigned long duration = currentTime - lastChangeTime;
        
        // Apply debounce
        if (duration < DEBOUNCE_TIME) {
            return; // Skip processing if within debounce time
        }
        
        // Valid state change detected
        if (lastState) { // ON → OFF (End of Pulse)
            // Add dot or dash based on pulse duration
            receivedMorse += (duration >= 175) ? "-" : ".";
            receivingMorse = true;  // Mark that Morse input is active
        } else if (receivingMorse) { // OFF → ON (Start of Pause) - only process if already receiving
            // Add letter gap if duration is long enough
            if (duration >= LETTER_GAP_DURATION) {
                receivedMorse += " ";    // Letter gap
            }
        }
        
        lastChangeTime = currentTime;
        lastState = currentState;
    }
    
    // Process completed message after timeout
    if (receivingMorse && (currentTime - lastChangeTime) > MESSAGE_TIMEOUT) {
        // Avoid unnecessary String operations if morse is empty
        if (receivedMorse.length() > 0) {
            Serial.print(F("Full Morse Sequence: "));
            Serial.println(receivedMorse);
            
            // Check morse code quality to prevent decoding errors
            if (receivedMorse.indexOf("  ") != -1 || receivedMorse.length() > 50) {
                Serial.println(F("⚠ Suspicious Morse pattern detected. Please try again."));
                setLEDStatus(STATUS_OTP_ERROR);
            } else {
                // Decode only if we have actual content
                String decodedOTP = decodeMorse(receivedMorse);
                
                // Early validation to avoid further processing
                if (decodedOTP.length() == 7) {
                    Serial.print(F("Decoded OTP: "));
                    Serial.println(decodedOTP);
                    
                    // Process OTP
                    if (verifyOTP(decodedOTP)) {
                        Serial.println(F("✅ Access Granted!"));
                    } else {
                        Serial.println(F("❌ Invalid Code!"));
                        setLEDStatus(STATUS_OTP_ERROR);
                    }
                } else {
                    Serial.println(F("⚠ Invalid Code Length! Must be 7 characters."));
                    setLEDStatus(STATUS_OTP_ERROR);
                }
            }
        }
        
        // Reset states more efficiently
        receivedMorse = "";
        receivingMorse = false;
    }
}