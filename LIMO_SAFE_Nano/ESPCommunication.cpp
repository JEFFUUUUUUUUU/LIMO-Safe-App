#include <Arduino.h>
#include "ESPCommunication.h"
#include "LockControl.h"
#include <SoftwareSerial.h>

SoftwareSerial espSerial(2, 3); // RX: 2 (Nano receive), TX: 3 (Nano transmit)

void processESPCommand(const char* command);

void initializeESPCommunication() {
    espSerial.begin(9600);
    Serial.println(F("✅ ESP Communication Initialized"));
}

void sendStatusToESP(bool isSafeClosed, bool motionDetected) {
    // Use F() macro for string literals to store them in flash instead of RAM
    espSerial.print(F("Nano:"));
    espSerial.print(isSafeClosed ? F("CLOSED") : F("OPEN"));
    espSerial.print(F(":"));
    espSerial.println(motionDetected ? F("UNSAFE") : F("SAFE"));
    espSerial.flush();  // Ensure complete transmission

    Serial.print(F("📤 Sent to ESP: Nano:"));
    Serial.print(isSafeClosed ? F("CLOSED") : F("OPEN"));
    Serial.print(F(":"));
    Serial.println(motionDetected ? F("UNSAFE") : F("SAFE"));
}

void checkESPResponse() {
    unsigned long startTime = millis();
    char buffer[32] = {0}; // Fixed buffer instead of String
    uint8_t index = 0;

    while (millis() - startTime < 500) {  // 500ms timeout
        if (espSerial.available()) {
            char c = espSerial.read();
            
            if (c == '\n' || index >= sizeof(buffer) - 1) {
                buffer[index] = '\0'; // Null terminate
                
                if (index > 0) {  // Not empty
                    Serial.print(F("📥 Response from ESP: "));
                    Serial.println(buffer);
                    
                    // Process command
                    processESPCommand(buffer);
                }
                return;
            } else if (c != '\r') { // Skip carriage return
                buffer[index++] = c;
            }
        }
    }

    Serial.println(F("⚠️ No response from ESP within timeout!"));
}

void processESPCommand(const char* command) {
    // Check if it's just an acknowledgment message
    if (strncmp(command, "ESP32: Received", 15) == 0) {
        Serial.println(F("📥 Acknowledgment from ESP received"));
        return;
    }
    
    // Process actual commands
    if (strcmp(command, "UNLOCK") == 0) {
        Serial.println(F("🔓 Received 'UNLOCK' command! Unlocking safe..."));
        unlockSafe();  // Call function from LockControl

        delay(500);

        Serial.println(F("🔒 Relocking safe..."));
        lockSafe();  // Lock back after delay

        // Send confirmation to ESP32
        espSerial.println(F("Nano:SAFE_UNLOCKED"));
    } else {
        Serial.print(F("⚠️ Unknown command from ESP: "));
        Serial.println(command);
    }
}