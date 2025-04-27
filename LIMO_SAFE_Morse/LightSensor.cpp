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
    calibrateLightSensor(); // Perform initial calibration
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

// Auto-calibrate the light sensor based on ambient light
void calibrateLightSensor() {
    setLEDStatus(STATUS_SCANNING); // Visual indicator that calibration is happening
    
    // Take multiple readings to determine ambient light level
    long total = 0;
    int minVal = 4095; // Max ADC value for ESP32
    int maxVal = 0;
    
    // Collect samples
    for (int i = 0; i < CALIBRATION_SAMPLES; i++) {
        int reading = analogRead(LIGHT_SENSOR_PIN);
        total += reading;
        
        // Track min and max values
        if (reading < minVal) minVal = reading;
        if (reading > maxVal) maxVal = reading;
        
        delay(CALIBRATION_DELAY);
    }
    
    // Calculate average and range
    int avgReading = total / CALIBRATION_SAMPLES;
    int range = maxVal - minVal;
    
    // Set threshold based on the readings
    if (range < 100) {
        // Stable lighting - use average plus 20% margin
        currentThreshold = avgReading + (avgReading * 0.2);
    } else {
        // Variable lighting - use more conservative threshold
        currentThreshold = avgReading + (range / 2);
    }
    
    // Ensure threshold is within reasonable bounds
    if (currentThreshold < 100) currentThreshold = 100;
    if (currentThreshold > 3900) currentThreshold = 3900;
    
    setLEDStatus(STATUS_IDLE); // Return to normal status
}

void processLightInput() {
    static unsigned long lastChangeTime = 0;
    static bool lastState = false;
    static String receivedMorse = "";
    static bool receivingMorse = false;
    static unsigned long lastProcessTime = 0;
    static unsigned long lastCalibrationTime = 0;
    
    // Only read sensor every few milliseconds to reduce CPU load
    unsigned long currentTime = millis();
    if (currentTime - lastProcessTime < 5) { // 5ms sampling rate is sufficient for Morse
        return;
    }
    lastProcessTime = currentTime;
    
    // Auto-recalibrate every 10 minutes
    if (currentTime - lastCalibrationTime > 30000) { // 10 minutes in ms
        calibrateLightSensor();
        lastCalibrationTime = currentTime;
    }
    
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