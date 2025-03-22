#include <Arduino.h>
#include "ESPCommunication.h"
#include "LockControl.h"
#include <SoftwareSerial.h>

SoftwareSerial espSerial(2, 3); // RX: 2 (Nano receive), TX: 3 (Nano transmit)
int messageCount = 0;

// Function declaration to avoid 'not declared in this scope' error
void processESPCommand(const String& command);

void initializeESPCommunication() {
    espSerial.begin(9600);
    Serial.println("✅ ESP Communication Initialized");
}

void sendStatusToESP(bool isSafeClosed, bool motionDetected) {
    String tamperStatus = motionDetected ? "UNSAFE" : "SAFE";

    String message = "Nano:" + String(messageCount++) + ":" +
                     (isSafeClosed ? "CLOSED" : "OPEN") + ":" + tamperStatus;

    espSerial.println(message);
    espSerial.flush();  // Ensure complete transmission

    Serial.print("📤 Sent to ESP: ");
    Serial.println(message);
    
    // Don't call checkESPResponse() here - we check continuously in the loop
}

void checkESPResponse() {
    unsigned long startTime = millis();
    bool responseReceived = false;

    while (millis() - startTime < 500) {  // 500ms timeout
        if (espSerial.available()) {
            String response = espSerial.readStringUntil('\n');
            response.trim();  // Remove extra whitespace

            if (response.length() > 0) {  // ✅ Fixed `isEmpty()` error
                Serial.print("📥 Response from ESP: ");
                Serial.println(response);
                responseReceived = true;

                // Check if response is an unlock command
                processESPCommand(response);
            }
            break;
        }
    }

    if (!responseReceived) {
        Serial.println("⚠️ No response from ESP within timeout!");
    }
}

void processESPCommand(const String& command) {
    // Check if it's just an acknowledgment message
    if (command.startsWith("ESP32: Received")) {
        // It's just an acknowledgment, no action needed
        Serial.println("📥 Acknowledgment from ESP received");
        return;
    }
    
    // Process actual commands
    if (command == "UNLOCK") {
        Serial.println("🔓 Received 'UNLOCK' command! Unlocking safe...");
        unlockSafe();  // Call function from LockControl

        delay(3000);  // Keep it unlocked for 3 seconds (adjustable)

        Serial.println("🔒 Relocking safe...");
        lockSafe();  // Lock back after delay

        // Send confirmation to ESP32
        espSerial.println("Nano:SAFE_UNLOCKED");
    } else {
        Serial.print("⚠️ Unknown command from ESP: ");
        Serial.println(command);
    }
}
