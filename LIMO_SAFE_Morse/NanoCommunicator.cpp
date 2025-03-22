#include "NanoCommunicator.h"
#include "FirebaseHandler.h"

HardwareSerial NanoSerial(1); // UART1 for Nano communication

void setupNanoCommunication() {
    NanoSerial.begin(SERIAL2_BAUD, SERIAL_8N1, SERIAL2_RX, SERIAL2_TX);
    Serial.println("✅ Nano UART Initialized");
}

void handleNanoData() {
    if (NanoSerial.available()) {
        String message = NanoSerial.readStringUntil('\n');
        Serial.print("[Nano→ESP32] ");
        Serial.println(message);

        // Expected format: "Nano:<ID>:<SafeStatus>:<TamperStatus>"
        int firstColon = message.indexOf(':');
        int secondColon = message.indexOf(':', firstColon + 1);
        int thirdColon = message.indexOf(':', secondColon + 1);

        if (firstColon > 0 && secondColon > firstColon && thirdColon > secondColon) {
            String nanoID = message.substring(firstColon + 1, secondColon);
            String safeStatus = message.substring(secondColon + 1, thirdColon);
            String tamperStatus = message.substring(thirdColon + 1);

            // Convert received values to boolean:
            bool isSafeClosed = (safeStatus == "CLOSED");     // 🔒 Locked if safe is "CLOSED"
            bool motionDetected = (tamperStatus == "UNSAFE"); // 🛡️ Unsecure if tampering is detected

            Serial.print("Parsed ID: ");
            Serial.println(nanoID);
            Serial.print("Locked (isSafeClosed): ");
            Serial.println(isSafeClosed ? "true" : "false");
            Serial.print("Secure (!motionDetected): ");
            Serial.println(!motionDetected ? "true" : "false");

            // Send acknowledgment to Nano
            String response = "ESP32: Received (" + message + ")";
            NanoSerial.println(response);

            // 🔥 Update Firebase with correct values
            if (Firebase.ready()) {
                String devicePath = String(DEVICE_PATH) + deviceId + "/status";
                FirebaseJson json;
                json.set("locked", isSafeClosed);      // 🔒 Set locked status
                json.set("secure", !motionDetected);  // 🛡️ Set secure status (inverse of tampering)
                json.set("lastUpdated", millis());

                if (Firebase.RTDB.updateNode(&fbdo, devicePath.c_str(), &json)) {
                    Serial.println("✅ Locked and secure status updated in Firebase");
                } else {
                    Serial.print("❌ Firebase update failed: ");
                    Serial.println(fbdo.errorReason());
                }
            }
        } else {
            Serial.println("❌ Invalid message format from Nano");
        }
    }
}

void sendCommandToNano(const String& command) {
    // Send the raw command exactly as the Nano expects it
    NanoSerial.print(command);  // Use print instead of println
    NanoSerial.write('\n');     // Explicitly add newline
    NanoSerial.flush();         // Ensure complete transmission
    
    Serial.print("[ESP32→Nano] Command sent: '");
    Serial.print(command);
    Serial.println("'");
    
    // Add a delay to give Nano time to process
    delay(50);
}
