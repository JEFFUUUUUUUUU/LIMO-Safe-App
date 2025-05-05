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
bool receivingMorse = false; // Flag indicating active Morse reception

// Morse Code mapping table
const char* const morseMap[][2] = {
    // Letters
    {".-", "A"},   {"-...", "B"}, {"-.-.", "C"}, {"-..", "D"},   {".", "E"},
    {"..-.", "F"}, {"--.", "G"},  {"....", "H"}, {"..", "I"},    {".---", "J"},
    {"-.-", "K"},  {".-..", "L"}, {"--", "M"},   {"-.", "N"},    {"---", "O"},
    {".--.", "P"}, {"--.-", "Q"}, {".-.", "R"},  {"...", "S"},   {"-", "T"},
    {"..-", "U"},  {"...-", "V"}, {".--", "W"},  {"-..-", "X"},  {"-.--", "Y"},
    {"--..", "Z"},
    
    // Numbers
    {".----", "1"}, {"..---", "2"}, {"...--", "3"}, {"....-", "4"},
    {".....", "5"}, {"-....", "6"}, {"--...", "7"}, {"---..", "8"},
    {"----.", "9"}, {"-----", "0"}
};

/**
 * Sets up the light sensor hardware and initial values
 * Called once during system initialization
 */
void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT); // Configure pin as input
    lastChangeTime = millis(); // Initialize timestamp
    
    // Perform initial calibration
    Serial.println(F("Calibrating sensor..."));
    calibrateSensor();
    Serial.println(F("Calibration complete!"));
    Serial.println(F("Ready to detect Morse code..."));
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
 * Calibrate the sensor based on ambient light
 */
void calibrateSensor() {
    long total = 0;
    int minVal = 4095; // Max ADC value for ESP32
    int maxVal = 0;
    
    // Collect samples
    for (int i = 0; i < 10; i++) {
        int reading = analogRead(LIGHT_SENSOR_PIN);
        total += reading;
        
        // Track min and max values
        if (reading < minVal) minVal = reading;
        if (reading > maxVal) maxVal = reading;
        
        delay(50);
    }
    
    // Calculate average and range
    int avgReading = total / 10;
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
    
    Serial.print(F("Ambient light level: "));
    Serial.print(avgReading);
    Serial.print(F(" | Threshold set to: "));
    Serial.println(currentThreshold);
    
    // Update timestamp
    lastAdaptiveUpdate = millis();
}

/**
 * Update threshold based on ambient conditions
 * Now uses the calibration function
 */
void updateAdaptiveThreshold() {
    // Use calibration instead of fixed threshold
    calibrateSensor();
}

/**
 * Translate Morse code to alphanumeric characters
 */
String translateMorse(String morse) {
    // Check each entry in the morse code mapping table
    for (int i = 0; i < sizeof(morseMap) / sizeof(morseMap[0]); i++) {
        if (morse == morseMap[i][0]) {
            return morseMap[i][1];
        }
    }
    
    // Return empty string if no match found
    return "";
}

/**
 * Main processing function for light sensor input
 * Detects Morse code patterns, decodes them to OTP and sends to ESP32
 * Called repeatedly from main loop
 */
void processLightInput() {
    static unsigned long lastProcessTime = 0; // Timestamp for rate limiting
    //static unsigned long lastCalibrationTime = 0; // Timestamp for auto-calibration
    
    // Auto-calibrate every 30 seconds
    unsigned long currentTime = millis();
    /*if (currentTime - lastCalibrationTime > 30000) {
        Serial.println(F("\nPerforming auto-calibration..."));
        calibrateSensor();
        Serial.println(F("Calibration complete!"));
        lastCalibrationTime = currentTime;
    }*/
    
    // Rate limiting - only read sensor every few milliseconds to reduce CPU load
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
        
        // Valid state change detected
        if (lastState) { // ON → OFF (End of Pulse)
            // Add dot or dash based on pulse duration
            if (duration >= 175) {
                receivedMorse += "-";
                Serial.print("-");
            } else {
                receivedMorse += ".";
                Serial.print(".");
            }
            receivingMorse = true; // Mark that Morse input is active
        }
        
        // Update state tracking variables
        lastState = currentState;
        lastChangeTime = currentTime;
    }
    
    // Check for breaks between letters or end of message
    if (receivingMorse && !currentState) {
        unsigned long silenceDuration = currentTime - lastChangeTime;
        
        // Check if this is a gap between letters
        if (silenceDuration >= LETTER_GAP_DURATION && receivedMorse.length() > 0) {
            // End of a letter detected
            String translated = translateMorse(receivedMorse);
            if (translated != "") {
                Serial.print(F(" ["));
                Serial.print(translated);
                Serial.print(F("] "));
                
                // Append to OTP
                receivedOTP += translated;
            }
            
            // Reset for next letter
            receivedMorse = "";
        }
        
        // Check for message timeout (end of transmission)
        if (silenceDuration >= MESSAGE_TIMEOUT) {
            if (receivingMorse) {
                // Final processing of any remaining morse code
                if (receivedMorse.length() > 0) {
                    String translated = translateMorse(receivedMorse);
                    if (translated != "") {
                        Serial.print(F(" ["));
                        Serial.print(translated);
                        Serial.print(F("] "));
                        
                        // Append to OTP
                        receivedOTP += translated;
                    }
                }
                
                // End of message
                Serial.println(F("\n--- End of Message ---"));
                
                // Validate OTP length (should be exactly 5 characters)
                if (receivedOTP.length() == 5) {
                    Serial.print(F("Decoded OTP: "));
                    Serial.println(receivedOTP);
                    
                    // Send OTP to ESP32 for verification
                    espSerial.print(F("OTP:"));
                    espSerial.println(receivedOTP);
                    Serial.println(F("📤 Sent OTP to ESP32 for verification"));
                    
                    // Check response from ESP32
                    if (espSerial.available()) {
                        checkESPResponse();
                    }
                } else {
                    // Invalid OTP length - reject immediately
                    Serial.println(F("⚠ Invalid Code Length! Must be 5 characters."));
                }
                
                // Reset states to prepare for next Morse code sequence
                receivedMorse = "";
                receivedOTP = "";
                receivingMorse = false;
            }
        }
    }
} 