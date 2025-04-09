#include <Arduino.h>
#include "ReedSensor.h"
#include "Accelerometer.h"
#include "ESPCommunication.h"
#include "LockControl.h"

bool tamperDetected = false;

void setup() {
    Serial.begin(9600);
    pinMode(LED_BUILTIN, OUTPUT); 
    
    // Initialize all components
    initializeReedSensor();
    initializeAccelerometer();
    initializeESPCommunication();
    initializeLock();

    Serial.println("Nano-ESP Secure Safe System Started");
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
    // Small delay to prevent CPU overload
    delay(10);
}