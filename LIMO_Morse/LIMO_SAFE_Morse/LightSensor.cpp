// LightSensor.cpp

#include "LightSensor.h"                 // Include light sensor header
#include "MorseDecoder.h"               // Include Morse code decoder
#include "FirebaseHandler.h"            // Include Firebase handler
#include "RGBLed.h"                     // Include RGB LED control

// Define global variables for Morse input parsing
String receivedMorse = "";               // Accumulated Morse code symbols
String receivedOTP = "";                 // Decoded OTP string
unsigned long lastChangeTime = 0;       // Timestamp of last sensor state change
bool lastState = false;                  // Previous sensor state (light ON/OFF)

// Added for optimization: Light sensor reading buffer
#define LIGHT_BUFFER_SIZE 10             // Size of the circular buffer
int lightReadings[LIGHT_BUFFER_SIZE];    // Buffer for readings
int lightReadIndex = 0;                  // Current index in buffer
int lightAverage = 0;                    // Average light reading

// Added for optimization: Timing control
unsigned long lastLightSensorRead = 0;   // Last time sensor was read
const unsigned long LIGHT_READ_INTERVAL = 10; // 10ms between reads (was 5ms)

// Setup function to initialize light sensor input pin
void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT);   // Configure light sensor pin as input
    lastChangeTime = millis();           // Initialize last change time to current time
    
    // Initialize light reading buffer
    for (int i = 0; i < LIGHT_BUFFER_SIZE; i++) {
        lightReadings[i] = 0;
    }
}

// Optimized function to read light sensor and update buffer
int readLightSensor() {
    // Read current value and store in buffer
    int currentReading = analogRead(LIGHT_SENSOR_PIN);
    
    // Update running average
    lightAverage -= lightReadings[lightReadIndex] / LIGHT_BUFFER_SIZE;
    lightReadings[lightReadIndex] = currentReading;
    lightAverage += currentReading / LIGHT_BUFFER_SIZE;
    
    // Move to next buffer position
    lightReadIndex = (lightReadIndex + 1) % LIGHT_BUFFER_SIZE;
    
    return lightAverage;
}

// Process a complete Morse sequence (separated from real-time input handling)
void processMorseSequence(const String& morseSequence) {
    if (morseSequence.length() == 0) {
        return;
    }
    
    Serial.print(F("Full Morse Sequence: "));
    Serial.println(morseSequence);

    String decodedOTP = decodeMorse(morseSequence);  // Decode Morse to string

    if (decodedOTP.length() == 7) {          // Verify OTP expected length
        Serial.print(F("Decoded OTP: "));
        Serial.println(decodedOTP);

        if (verifyOTP(decodedOTP)) {          // Attempt OTP verification
            Serial.println(F("✅ Access Granted!"));
        } else {
            Serial.println(F("❌ Invalid Code!"));
            setLEDStatus(STATUS_OTP_ERROR);   // Indicate error with LED
        }
    } else {
        Serial.println(F("⚠ Invalid Code Length! Must be 7 characters."));
        setLEDStatus(STATUS_OTP_ERROR);
    }
}

// Process toggling of light sensor input to capture Morse code symbols
void processLightInput() {
    unsigned long currentTime = millis();
    
    // Only read sensor at specified intervals to reduce CPU load
    if (currentTime - lastLightSensorRead < LIGHT_READ_INTERVAL) {
        return;
    }
    lastLightSensorRead = currentTime;

    // Read and filter light sensor value
    int lightValue = readLightSensor();
    bool currentState = (lightValue > THRESHOLD);

    if (currentState != lastState) {              // Detect sensor state change
        unsigned long duration = currentTime - lastChangeTime;

        if (duration < DEBOUNCE_TIME) {           // Ignore quick state changes: debounce
            return;                               // Skip if within debounce period
        }

        static bool receivingMorse = false;         // Whether currently receiving Morse message

        if (lastState) { // ON → OFF (end of pulse)
            receivedMorse += (duration >= 175) ? "-" : ".";  // Add dash or dot per pulse length
            receivingMorse = true;               // Mark receiving Morse sequence
        } else if (receivingMorse) {             // OFF → ON (start of pause); if receiving Morse
            if (duration >= WORD_GAP_DURATION) {
                receivedMorse += " / ";           // Word gap for long pause
            } else if (duration >= LETTER_GAP_DURATION) {
                receivedMorse += " ";             // Letter gap for medium pause
            }
        }

        lastChangeTime = currentTime;              // Update last state change timestamp
        lastState = currentState;                   // Update last sensor state
    }

    // If Morse sequence active and timeout expired, decode message
    if ((currentTime - lastChangeTime) > MESSAGE_TIMEOUT && receivedMorse.length() > 0) {
        // Make a copy of the received sequence
        String completeSequence = receivedMorse;
        // Reset the buffer immediately to allow new input
        receivedMorse = "";
        
        // Process the sequence in a separate function to improve timing
        processMorseSequence(completeSequence);
    }
}