#include "LightSensor.h"
#include "MorseDecoder.h"
#include "FirebaseHandler.h"
#include "RGBLed.h"

// Define global variables (only once)
String receivedMorse = ""; // Stores the received Morse code sequence
unsigned long lastChangeTime = 0; // Timestamp of the last light state change
bool lastState = false; // Previous state of the light sensor
int currentThreshold = THRESHOLD_BASE; // Fixed threshold value

// Critical section tracking for precise timing
unsigned long criticalSectionStartTime = 0;
bool inCriticalSection = false;

/**
 * Sets up the light sensor hardware and initial values
 * Called once during system initialization
 */
void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT); // Configure GPIO pin as input
    lastChangeTime = millis(); // Initialize timestamp
    currentThreshold = THRESHOLD_BASE; // Use fixed threshold
    
    // Add pull-down resistor to improve stability
    pinMode(LIGHT_SENSOR_PIN, INPUT_PULLDOWN);
}

/**
 * Gets a smoothed sensor reading by taking multiple samples
 * Using micros() instead of delay() for precise timing
 * @return Average light sensor reading value (higher = brighter)
 */
int getSmoothReading() {
    int total = 0;
    
    // Track when we enter critical section
    criticalSectionStartTime = micros();
    inCriticalSection = true;
    
    // Simple averaging for stability - using micros() for timing
    for (int i = 0; i < 3; i++) {
        total += analogRead(LIGHT_SENSOR_PIN);
        
        // Use micros for precise timing instead of delay
        // Adding 150 microseconds between readings (more precise than delay(2))
        unsigned long startWait = micros();
        while (micros() - startWait < 150) {
            // Tight loop but very brief
        }
    }
    
    // Mark end of critical section
    inCriticalSection = false;
    
    return total / 3;
}

/**
 * Checks if the light sensor is currently in a critical reading section
 * @return True if in critical section, false otherwise
 */
bool isLightSensorCriticalSection() {
    // If not in critical section, return false immediately
    if (!inCriticalSection) {
        return false;
    }
    
    // Check if we've exceeded the maximum block time
    if (micros() - criticalSectionStartTime > MAX_BLOCK_TIME) {
        inCriticalSection = false; // Auto-release if taking too long
        return false;
    }
    
    return true;
}

/**
 * Main processing function for light sensor input
 * Detects Morse code patterns and converts them to OTP codes
 * Non-blocking implementation that minimizes impact on other processes
 */
void processLightInput() {
    static unsigned long lastProcessTime = 0;
    static bool receivingMorse = false;
    
    // Rate limiting - read sensor at fixed intervals
    unsigned long currentTime = millis();
    if (currentTime - lastProcessTime < SENSOR_READ_INTERVAL) {
        return; // Only process at specified intervals to reduce system load
    }
    lastProcessTime = currentTime;
    
    // Simple debounced light sensing
    int lightValue = getSmoothReading();
    bool currentState = (lightValue > currentThreshold);
    
    // Process state changes after debounce period
    if (currentState != lastState && (currentTime - lastChangeTime) > DEBOUNCE_TIME) {
        // Valid state change detected
        if (lastState) {
            // Light to dark transition (end of pulse)
            unsigned long duration = currentTime - lastChangeTime;
            receivedMorse += (duration >= 175) ? "-" : ".";
            receivingMorse = true;
        } else if (receivingMorse) {
            // Dark to light transition (potential letter gap)
            unsigned long duration = currentTime - lastChangeTime;
            if (duration >= LETTER_GAP_DURATION) {
                receivedMorse += " "; // Add space for letter separation
            }
        }
        
        // Update state tracking
        lastChangeTime = currentTime;
        lastState = currentState;
    }
    
    // Process completed message after timeout
    if (receivingMorse && (currentTime - lastChangeTime) > MESSAGE_TIMEOUT) {
        if (receivedMorse.length() > 0) {
            Serial.print(F("Morse Code: "));
            Serial.println(receivedMorse);
            
            // Validate basic format
            if (receivedMorse.indexOf("  ") != -1 || receivedMorse.length() > 50) {
                Serial.println(F("⚠ Invalid Morse pattern. Please try again."));
                setLEDStatus(STATUS_OTP_ERROR);
            } else {
                String decodedOTP = decodeMorse(receivedMorse);
                
                if (decodedOTP.length() == 7) {
                    Serial.print(F("Decoded OTP: "));
                    Serial.println(decodedOTP);
                    
                    if (verifyOTP(decodedOTP)) {
                        Serial.println(F("✅ Access Granted!"));
                    } else {
                        Serial.println(F("❌ Invalid Code!"));
                        setLEDStatus(STATUS_OTP_ERROR);
                    }
                } else {
                    Serial.println(F("⚠ Invalid Code Length!"));
                    setLEDStatus(STATUS_OTP_ERROR);
                }
            }
        }
        
        // Reset for next sequence
        receivedMorse = "";
        receivingMorse = false;
    }
}