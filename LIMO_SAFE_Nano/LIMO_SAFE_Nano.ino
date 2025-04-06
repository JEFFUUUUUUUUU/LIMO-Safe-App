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
    initializeFingerprint();

    Serial.println("Nano-ESP Secure Safe System Started");
    
    // Setup functions (commented out for normal operation)
    //enrollFingerprint(1);
    //deleteAllFingerprints();
}

void loop() {
    // Read sensors
    bool safeClosed = isSafeClosed();
    bool tamperDetected = checkForTamper();

    // Send status every 2 seconds
    static unsigned long lastStatusTime = 0;
    if (millis() - lastStatusTime > 2000) {
        // Update ESP
        sendStatusToESP(safeClosed, tamperDetected);
        lastStatusTime = millis();
    }
    
    // Check for ESP commands (high priority)
    if (espSerial.available()) {
        String command = espSerial.readStringUntil('\n');
        command.trim();
        Serial.print(F("📥 Command: "));
        Serial.println(command);
        processESPCommand(command.c_str());
    }

    // Handle fingerprint authentication
    if (authenticateUser()) {
        Serial.println(F("🔓 Auth success! Unlocking..."));
        unlockSafe();
        delay(5000);
        Serial.println(F("🔒 Relocking..."));
        lockSafe();
    }
    
    delay(50); // Prevent serial buffer overflow
}