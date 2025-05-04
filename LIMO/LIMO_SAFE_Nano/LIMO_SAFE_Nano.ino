#include <Arduino.h>
#include "ReedSensor.h"
#include "Accelerometer.h"
#include "ESPCommunication.h"
#include "LockControl.h"
#include "LightSensor.h"

// Global state variables
bool safeClosed = true;
bool tamperDetected = false;
unsigned long lastStatusTime = 0;
unsigned long lastLedBlinkTime = 0;
bool ledState = false;

// Timing constants
#define STATUS_UPDATE_INTERVAL 1000    // Send status every 1 second
#define COMMAND_CHECK_INTERVAL 50      // Check commands every 50ms
#define LED_BLINK_INTERVAL_NORMAL 1000 // Normal blink interval in ms
#define LED_BLINK_INTERVAL_ALERT 250   // Fast blink interval for alerts

void setup() {
    Serial.begin(9600);
    pinMode(LED_BUILTIN, OUTPUT); 
    
    // Initialize all components
    initializeReedSensor();
    resetReedSensorState(); // Ensure reed sensor has a clean state
    
    bool accelOk = initializeAccelerometer();
    initializeESPCommunication();
    initializeLock();
    setupLightSensor(); // Initialize light sensor for Morse code reception

    if (!accelOk) {
        Serial.println("⚠️ WARNING: Accelerometer initialization failed!");
        // Blink LED rapidly to indicate error
        for (int i = 0; i < 10; i++) {
            digitalWrite(LED_BUILTIN, HIGH);
            delay(100);
            digitalWrite(LED_BUILTIN, LOW);
            delay(100);
        }
    }

    // Read initial safe state
    safeClosed = isSafeClosed();
    
    Serial.println("✅ Nano-ESP Secure Safe System Started");
}

void loop() {
    unsigned long currentMillis = millis();
    
    // Handle LED status indication
    updateStatusLED(currentMillis);
    
    // Process light sensor input for Morse code OTP
    processLightInput();
    
    // Check for ESP commands with appropriate timing
    static unsigned long lastCommandCheck = 0;
    if (currentMillis - lastCommandCheck >= COMMAND_CHECK_INTERVAL) {
        checkESPResponse();
        lastCommandCheck = currentMillis;
    }
    
    // Read sensors (non-blocking)
    bool newSafeClosed = isSafeClosed();
    bool newTamperDetected = checkForTamper();
    
    // Detect and handle state changes
    if (newSafeClosed != safeClosed || newTamperDetected != tamperDetected) {
        // State changed, update immediately
        safeClosed = newSafeClosed;
        tamperDetected = newTamperDetected;
        sendStatusToESP(safeClosed, tamperDetected);
        lastStatusTime = currentMillis;
    }
    // Periodic status update
    else if (currentMillis - lastStatusTime >= STATUS_UPDATE_INTERVAL) {
        sendStatusToESP(safeClosed, tamperDetected);
        lastStatusTime = currentMillis;
    }
    
    // No delay needed - the timing is handled by millis() comparisons
}

// Update the built-in LED based on system status
void updateStatusLED(unsigned long currentMillis) {
    unsigned long blinkInterval = LED_BLINK_INTERVAL_NORMAL;
    
    // Use faster blink for alert conditions
    if (tamperDetected || !safeClosed) {
        blinkInterval = LED_BLINK_INTERVAL_ALERT;
    }
    
    if (currentMillis - lastLedBlinkTime >= blinkInterval) {
        ledState = !ledState;
        digitalWrite(LED_BUILTIN, ledState ? HIGH : LOW);
        lastLedBlinkTime = currentMillis;
    }
}