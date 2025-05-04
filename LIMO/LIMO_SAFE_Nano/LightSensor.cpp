#include "LightSensor.h"
#include "MorseDecoder.h"
#include "ESPCommunication.h"

// Define global variables
String receivedMorse = ""; // Stores the received Morse code sequence
String receivedOTP = ""; // Stores the decoded OTP
unsigned long lastChangeTime = 0; // Timestamp of the last light state change
bool lastState = false; // Previous state of the light sensor
int currentThreshold = THRESHOLD_BASE; // Current threshold level for light detection
unsigned long lastAdaptiveUpdate = 0; // Timestamp of the last threshold adjustment

/**
 * Sets up the light sensor hardware and initial values
 * Called once during system initialization
 */
void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT); // Configure pin as input
    lastChangeTime = millis(); // Initialize timestamp
    // Use fixed threshold
    currentThreshold = THRESHOLD_BASE;
}

/**
 * Gets a smoothed sensor reading by taking multiple samples
 * @return Average light sensor reading value (higher = brighter)
 */
int getSmoothReading() {
    int total = 0; // Accumulator for readings
    for (int i = 0; i < 3; i++) { // Take 3 samples
        total += analogRead(LIGHT_SENSOR_PIN); // Add each reading to total
        delay(2);  // Short delay between readings for stability
    }
    return total / 3; // Return the average value
}

/**
 * Fixed threshold implementation (no calibration)
 */
void updateAdaptiveThreshold() {
    // Use fixed threshold - no calibration
    currentThreshold = THRESHOLD_BASE;
}

/**
 * Main processing function for light sensor input
 * Detects Morse code patterns, decodes them to OTP and sends to ESP32
 * Called repeatedly from main loop
 */
void processLightInput() {
    // Variables for function state that persist between calls
    static bool receivingMorse = false; // Flag indicating active Morse reception
    static unsigned long lastProcessTime = 0; // Timestamp for rate limiting
    
    // Rate limiting - only read sensor every few milliseconds to reduce CPU load
    unsigned long currentTime = millis();
    if (currentTime - lastProcessTime < 5) { // 5ms sampling rate is sufficient
        return; // Exit early if called too frequently
    }
    lastProcessTime = currentTime; // Update processing timestamp
    
    // Read light sensor with smoothing for stable readings
    int lightValue = getSmoothReading();
    bool currentState = (lightValue > currentThreshold); // Compare with threshold
    
    // Process light level changes (potential Morse signals)
    if (currentState != lastState) { // Light state changed
        unsigned long duration = currentTime - lastChangeTime; // Calculate duration
        
        // Apply debounce to filter out rapid fluctuations
        if (duration < DEBOUNCE_TIME) {
            return; // Skip processing if change happened too quickly
        }
        
        // Valid state change detected - process based on transition type
        if (lastState) {
            // Add dot or dash based on pulse duration
            // Longer pulses (>=175ms) are dashes, shorter are dots
            receivedMorse += (duration >= 175) ? "-" : ".";
            receivingMorse = true; // Mark that Morse input is active
        } else if (receivingMorse) {
            // Add letter gap if pause duration is long enough
            if (duration >= LETTER_GAP_DURATION) {
                receivedMorse += " "; // Add space to separate letters
            }
        }
        
        // Update state tracking variables
        lastChangeTime = currentTime;
        lastState = currentState;
    }
    
    // Process completed message after timeout (no activity for a period)
    if (receivingMorse && (currentTime - lastChangeTime) > MESSAGE_TIMEOUT) {
        // Avoid unnecessary String operations if morse is empty
        if (receivedMorse.length() > 0) {
            // Output the full Morse sequence for debugging
            Serial.print(F("Full Morse Sequence: "));
            Serial.println(receivedMorse);
            
            // Validate Morse code format before decoding
            // Reject suspicious patterns like multiple spaces or extremely long codes
            if (receivedMorse.indexOf("  ") != -1 || receivedMorse.length() > 50) {
                Serial.println(F("⚠ Suspicious Morse pattern detected. Please try again."));
            } else {
                // Decode valid Morse pattern to text
                String decodedOTP = decodeMorse(receivedMorse);
                
                // Validate OTP length (should be exactly 5 characters)
                if (decodedOTP.length() == 5) {
                    Serial.print(F("Decoded OTP: "));
                    Serial.println(decodedOTP);
                    
                    // Send OTP to ESP32 for verification
                    espSerial.print(F("OTP:"));
                    espSerial.println(decodedOTP);
                    Serial.println(F("📤 Sent OTP to ESP32 for verification"));
                    
                    // Check response from ESP32
                    checkESPResponse();
                } else {
                    // Invalid OTP length - reject immediately
                    Serial.println(F("⚠ Invalid Code Length! Must be 5 characters."));
                }
            }
        }
        
        // Reset states to prepare for next Morse code sequence
        receivedMorse = ""; // Clear received Morse code
        receivingMorse = false; // Reset reception flag
    }
} 