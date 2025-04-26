#include "LightSensor.h"
#include "MorseDecoder.h"
#include "FirebaseHandler.h"
#include "RGBLed.h"

// Signal processing variables
float smoothedValue = 0;                      // Current smoothed sensor reading
int highThreshold = 500;                      // Dynamic high threshold
int lowThreshold = 400;                       // Dynamic low threshold
bool isTransmissionActive = false;            // Flag for active transmission
unsigned long lastActiveTime = 0;             // Last time activity was detected
unsigned long lastCalibrationTime = 0;        // Last time calibration occurred

// Signal history tracking
unsigned long transitionTimes[SIGNAL_HISTORY_SIZE]; // Timestamps of recent transitions
int transitionIndex = 0;                     // Current index in transition buffer
int recentMax = 0;                           // Recent maximum light value
int recentMin = 4095;                        // Recent minimum light value (ESP32 ADC max)

// Morse processing state
String receivedMorse = "";                   // Current morse sequence
bool receivingMorse = false;                 // Flag to indicate active reception
unsigned long lastChangeTime = 0;            // Time of last state change
bool lastState = false;                      // Previous light state
unsigned long lastProcessTime = 0;           // Last processing time for throttling

void setupLightSensor() {
    pinMode(LIGHT_SENSOR_PIN, INPUT);
    lastChangeTime = millis();
    
    // Initialize transition times array
    for (int i = 0; i < SIGNAL_HISTORY_SIZE; i++) {
        transitionTimes[i] = 0;
    }
    
    // Initial calibration
    calibrateSensor();
}

void calibrateSensor() {
    Serial.println(F("Calibrating light sensor..."));
    
    // Take multiple readings to establish baseline
    int maxVal = 0, minVal = 4095;
    const int samples = 50;
    
    for (int i = 0; i < samples; i++) {
        int reading = analogRead(LIGHT_SENSOR_PIN);
        maxVal = max(maxVal, reading);
        minVal = min(minVal, reading);
        delay(10);  // Short delay between samples
    }
    
    // Establish thresholds with hysteresis
    int midpoint = (maxVal + minVal) / 2;
    highThreshold = midpoint + THRESHOLD_MARGIN;
    lowThreshold = midpoint - THRESHOLD_MARGIN;
    
    // Initialize smoothed value
    smoothedValue = analogRead(LIGHT_SENSOR_PIN);
    
    // Reset signal range tracking
    recentMax = maxVal;
    recentMin = minVal;
    
    Serial.print(F("Calibration results - Low: "));
    Serial.print(lowThreshold);
    Serial.print(F(", High: "));
    Serial.print(highThreshold);
    Serial.print(F(", Range: "));
    Serial.println(maxVal - minVal);
    
    lastCalibrationTime = millis();
}

// Updates signal level tracking with new reading
void updateSignalLevels(int reading) {
    // Gradually decay max and grow min to adapt to changing conditions
    const int MAX_DECAY_RATE = 2;  // How quickly max decays
    const int MIN_GROWTH_RATE = 2; // How quickly min increases
    
    if (reading > recentMax) {
        recentMax = reading;
    } else {
        recentMax = max(reading, recentMax - MAX_DECAY_RATE);
    }
    
    if (reading < recentMin) {
        recentMin = reading;
    } else {
        recentMin = min(reading, recentMin + MIN_GROWTH_RATE);
    }
}

// Calculates the average period between recent transitions
float calculateAveragePeriod() {
    float sum = 0;
    int validIntervals = 0;
    
    for (int i = 1; i < SIGNAL_HISTORY_SIZE; i++) {
        unsigned long interval = transitionTimes[i] - transitionTimes[i-1];
        if (interval > 0 && interval < MAX_MORSE_PERIOD) {
            sum += interval;
            validIntervals++;
        }
    }
    
    return (validIntervals > 0) ? (sum / validIntervals) : 0;
}

// Counts recent transitions within time window
int countRecentTransitions(unsigned long currentTime, unsigned long windowSize) {
    int count = 0;
    
    for (int i = 0; i < SIGNAL_HISTORY_SIZE; i++) {
        if (transitionTimes[i] > 0 && 
            currentTime - transitionTimes[i] < windowSize) {
            count++;
        }
    }
    
    return count;
}

// Calculate confidence score for the signal quality
float calculateSignalConfidence() {
    float confidence = 0.0;
    
    // Factor 1: Signal contrast ratio
    int signalRange = recentMax - recentMin;
    float contrastRatio = (float)signalRange / recentMax;
    if (contrastRatio > MIN_CONTRAST_RATIO) {
        confidence += 0.3;
    }
    
    // Factor 2: Consistent timing (standard deviation of periods)
    float avgPeriod = calculateAveragePeriod();
    if (avgPeriod > 0 && avgPeriod < MAX_MORSE_PERIOD) {
        confidence += 0.3;
    }
    
    // Factor 3: Sufficient number of transitions
    int transitionCount = countRecentTransitions(millis(), TRANSITION_TIME_WINDOW);
    if (transitionCount >= TRANSITION_COUNT_THRESHOLD) {
        confidence += 0.4;
    }
    
    return confidence;
}

// Check if enough time has passed for recalibration
bool shouldRecalibrate(unsigned long currentTime) {
    // Don't recalibrate if transmission is active
    if (isTransmissionActive) {
        return false;
    }
    
    // Check if it's been long enough since last calibration and activity
    return (currentTime - lastCalibrationTime > CALIBRATION_INTERVAL) && 
           (currentTime - lastActiveTime > INACTIVE_TIMEOUT);
}

void processLightInput() {
    unsigned long currentTime = millis();
    
    // Only read sensor every few milliseconds to reduce CPU load
    if (currentTime - lastProcessTime < 5) {
        return;
    }
    lastProcessTime = currentTime;
    
    // Read light sensor
    int rawReading = analogRead(LIGHT_SENSOR_PIN);
    
    // Apply exponential smoothing
    smoothedValue = SMOOTHING_ALPHA * rawReading + (1 - SMOOTHING_ALPHA) * smoothedValue;
    
    // Update min/max signal tracking
    updateSignalLevels(rawReading);
    
    // Check for recalibration opportunity
    if (shouldRecalibrate(currentTime)) {
        calibrateSensor();
        return;
    }
    
    // Determine current state with hysteresis to prevent flickering
    bool currentState;
    if (lastState) {
        // Currently ON - only turn OFF if below low threshold
        currentState = (smoothedValue > lowThreshold);
    } else {
        // Currently OFF - only turn ON if above high threshold
        currentState = (smoothedValue > highThreshold);
    }
    
    // Handle state changes
    if (currentState != lastState) {
        // Record transition time
        transitionTimes[transitionIndex] = currentTime;
        transitionIndex = (transitionIndex + 1) % SIGNAL_HISTORY_SIZE;
        
        // Mark as active
        lastActiveTime = currentTime;
        
        // Calculate confidence score for signal quality
        float signalConfidence = calculateSignalConfidence();
        
        // Only process high-confidence signals
        if (signalConfidence >= MIN_CONFIDENCE_THRESHOLD) {
            isTransmissionActive = true;
            
            unsigned long duration = currentTime - lastChangeTime;
            
            // Apply debounce
            if (duration < DEBOUNCE_TIME) {
                return; // Skip processing if within debounce time
            }
            
            // Process the state change for Morse code
            if (lastState) { // ON → OFF (End of Pulse)
                // Add dot or dash based on pulse duration
                receivedMorse += (duration >= 175) ? "-" : ".";
                receivingMorse = true;  // Mark that Morse input is active
            } else if (receivingMorse) { // OFF → ON (Start of Pause) - only process if already receiving
                // Add appropriate spacing based on pause duration
                if (duration >= WORD_GAP_DURATION) {
                    receivedMorse += " / ";  // Word gap
                } else if (duration >= LETTER_GAP_DURATION) {
                    receivedMorse += " ";    // Letter gap
                }
            }
        } else {
            // Low confidence signal - treat as potential environmental change
            // but still track the state change
            Serial.print(F("Low confidence signal ("));
            Serial.print(signalConfidence);
            Serial.println(F(") - ignoring"));
        }
        
        lastChangeTime = currentTime;
        lastState = currentState;
    }
    
    // Detect end of transmission
    if (receivingMorse && (currentTime - lastChangeTime > MESSAGE_TIMEOUT)) {
        // Check if we've received anything
        if (receivedMorse.length() > 0) {
            Serial.print(F("Full Morse Sequence: "));
            Serial.println(receivedMorse);
            
            // Decode morse
            String decodedOTP = decodeMorse(receivedMorse);
            
            // Validate OTP length
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
        
        // Reset states
        receivedMorse = "";
        receivingMorse = false;
        isTransmissionActive = false;
    }
}