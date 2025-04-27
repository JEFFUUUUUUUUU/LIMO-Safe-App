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

void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT);
    lastChangeTime = millis();
    // Calibration removed
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

// Calibration function removed

void processLightInput() {
    static unsigned long lastChangeTime = 0;
    static bool lastState = false;
    static String receivedMorse = "";
    static bool receivingMorse = false;
    static unsigned long lastProcessTime = 0;
    
    // Only read sensor every few milliseconds to reduce CPU load
    unsigned long currentTime = millis();
    if (currentTime - lastProcessTime < 5) { // 5ms sampling rate is sufficient for Morse
        return;
    }
    lastProcessTime = currentTime;
    
    // Auto-recalibration code removed
    
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
            // Add appropriate spacing based on pause duration
            if (duration >= WORD_GAP_DURATION) {
               receivedMorse += " / ";  // Word gap
            }
             else if (duration >= LETTER_GAP_DURATION) {
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
        
        // Reset states more efficiently
        receivedMorse = "";
        receivingMorse = false;
    }
}