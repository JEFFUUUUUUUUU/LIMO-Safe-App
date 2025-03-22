#include "ReedSensor.h"
#include "Accelerometer.h"
#include "ESPCommunication.h"
#include "LockControl.h"
#include "FingerprintSensor.h"

void setup() {
    Serial.begin(115200);
    pinMode(LED_BUILTIN, OUTPUT); 
    initializeReedSensor();
    initializeAccelerometer();
    initializeESPCommunication();
    initializeLock();
    //initializeFingerprint();

    Serial.println("Nano-ESP Secure Safe System Started");

    // Uncomment to enroll a new fingerprint (only run once for setup)
    // enrollFingerprint(1);
    // enrollFingerprint(2);
    // enrollFingerprint(3);
    // deleteAllFingerprints(); // To reset fingerprints
}

void loop() {
    // Read reed sensor state
    bool safeClosed = isSafeClosed();
    
    bool tamperDetected = motionDetected;
    if (motionDetected) {
        motionDetected = false;  // Reset flag
    }

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

    /*if (authenticateUser()) {
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
    }*/
    
    // Short delay is crucial - gives time to read Serial without missing commands
    delay(50);
}
