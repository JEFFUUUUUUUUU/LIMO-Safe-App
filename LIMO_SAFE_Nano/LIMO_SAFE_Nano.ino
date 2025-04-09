#include <Arduino.h>
#include "ReedSensor.h"
#include "Accelerometer.h"
#include "ESPCommunication.h"
#include "LockControl.h"
#include "FingerprintSensor.h"

bool tamperDetected = false;

void setup() {
    Serial.begin(9600);
    pinMode(LED_BUILTIN, OUTPUT); 
    
    // Initialize all components
    initializeReedSensor();
    initializeAccelerometer();
    initializeESPCommunication();
    initializeLock();
    //initializeFingerprint();

    Serial.println("Nano-ESP Secure Safe System Started");
    
    // Setup functions (commented out for normal operation)
    //enrollFingerprint(1);
    //deleteAllFingerprints();
}

void loop() {
    // Always check for ESP commands first
    checkESPResponse();
    
    // Read sensors (these should be non-blocking too)
    bool safeClosed = isSafeClosed();
    bool tamperDetected = checkForTamper();

    // Send status every 2 seconds
    static unsigned long lastStatusTime = 0;
    if (millis() - lastStatusTime > 2000) {
        // Update ESP
        sendStatusToESP(safeClosed, tamperDetected);
        lastStatusTime = millis();
    }

    // Handle fingerprint authentication (now non-blocking)
    if (authenticateUser()) {
        Serial.println(F("🔓 Auth success! Unlocking..."));
        unlockSafe();
        delay(5000); // Note: This delay could be replaced with a non-blocking timer too
        Serial.println(F("🔒 Relocking..."));
        lockSafe();
    }
    
    // Small delay to prevent CPU overload
    delay(10);
}