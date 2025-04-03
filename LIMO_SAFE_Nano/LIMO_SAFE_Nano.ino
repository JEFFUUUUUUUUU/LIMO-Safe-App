#include <Arduino.h>
#include "ReedSensor.h"
#include "Accelerometer.h"
#include "ESPCommunication.h"
#include "LockControl.h"
#include "FingerprintSensor.h"

// Flag to track if tamper was detected
bool tamperDetected = false;

void setup() {
    Serial.begin(9600);
    pinMode(LED_BUILTIN, OUTPUT); 
    initializeReedSensor();
    initializeAccelerometer();
    initializeESPCommunication();
    initializeLock();
    initializeFingerprint();

    Serial.println("Nano-ESP Secure Safe System Started");

    // Uncomment to enroll a new fingerprint (only run once for setup)
    //enrollFingerprint(1);
    // enrollFingerprint(2);
    // enrollFingerprint(3);
    // deleteAllFingerprints(); // To reset fingerprints
}

void loop() {
    // Read reed sensor state
    bool safeClosed = isSafeClosed();
    
    // Check for tamper using different motion detection methods
    bool currentTamperDetected = isMotionDetected() || detectMotionByReading() || detectSustainedMotion();

    if (currentTamperDetected && !tamperDetected) {  // Log only on state change
        Serial.println("⚠️ Tampering detected!");

        if (detectSustainedMotion()) {  // Log orientation only when significant motion occurs
            float pitch, roll;
            getOrientation(&pitch, &roll);
            Serial.print("Orientation - Pitch: ");
            Serial.print(pitch);
            Serial.print("° Roll: ");
            Serial.println(roll);
        }

        resetMotionDetection();  // Reset internal motion detection if triggered
    }

    // Update tamper flag
    tamperDetected = currentTamperDetected;

    // Send status periodically
    static unsigned long lastStatusTime = 0;
    if (millis() - lastStatusTime > 2000) {  // Every 2 seconds
        // Debug output for reed sensor
        Serial.print("Reed sensor status: ");
        Serial.print(digitalRead(reedSwitchPin));
        Serial.print(" | Safe status: ");
        Serial.println(safeClosed ? "CLOSED" : "OPEN");
        
        // Send to ESP as normal
        sendStatusToESP(safeClosed, tamperDetected);
        lastStatusTime = millis();
    }
    
    // ALWAYS check for ESP commands - no timing window
    if (espSerial.available()) {
        String command = espSerial.readStringUntil('\n');
        command.trim();  // Remove whitespace
        Serial.print("📥 Command from ESP: ");
        Serial.println(command);
        processESPCommand(command);
    }

    if (authenticateUser()) {
        unlockSafe();
        delay(5000);  // Keep the safe unlocked for 5 seconds
        lockSafe();
    } else {
        // Don't print every loop
        static unsigned long lastAccessDeniedTime = 0;
        if (millis() - lastAccessDeniedTime > 2000) {
            Serial.println("Access denied.");
            lastAccessDeniedTime = millis();
        }
    }
    
    // Short delay is crucial - gives time to read Serial without missing commands
    delay(50);
}
