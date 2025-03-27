#include "NanoCommunicator.h"
#include "FirebaseHandler.h"

HardwareSerial NanoSerial(1); // UART1 for Nano communication

void setupNanoCommunication() {
    NanoSerial.begin(SERIAL2_BAUD, SERIAL_8N1, SERIAL2_RX, SERIAL2_TX);
    Serial.println("✅ Nano UART Initialized");
}

// Define global variables to track previous states
bool prevSafeClosed = false;
bool prevMotionDetected = false;
unsigned long lastLockedChangeTime = 0;
unsigned long lastSecureChangeTime = 0;

void handleNanoData() {
    if (!NanoSerial.available()) return;
    
    String message = NanoSerial.readStringUntil('\n');
    Serial.print("[Nano→ESP32] ");
    Serial.println(message);
    
    // Expected format: "Nano:<ID>:<SafeStatus>:<TamperStatus>"
    // Use more efficient parsing
    int indices[3] = {-1, -1, -1};
    int startIdx = 0;
    
    // Find all colons in one pass
    for (int i = 0, colon = 0; i < message.length() && colon < 3; i++) {
        if (message.charAt(i) == ':') {
            indices[colon++] = i;
        }
    }
    
    // Validate format
    if (indices[0] <= 0 || indices[1] <= indices[0] || indices[2] <= indices[1]) {
        Serial.println("❌ Invalid message format from Nano");
        return;
    }
    
    // Direct boolean conversion without temporary strings
    String nanoID = message.substring(indices[0] + 1, indices[1]);
    bool isSafeClosed = message.substring(indices[1] + 1, indices[2]).equals("CLOSED");
    bool motionDetected = message.substring(indices[2] + 1).equals("UNSAFE");
    
    // Only log if needed for debugging
    #ifdef DEBUG_MODE
    Serial.print("Parsed ID: ");
    Serial.println(nanoID);
    Serial.print("Locked (isSafeClosed): ");
    Serial.println(isSafeClosed ? "true" : "false");
    Serial.print("Secure (!motionDetected): ");
    Serial.println(!motionDetected ? "true" : "false");
    #endif
    
    // Send acknowledgment to Nano
    NanoSerial.print("ESP32: Received (");
    NanoSerial.print(message);
    NanoSerial.println(")");
    
    // Check if states have changed
    bool lockedChanged = (isSafeClosed != prevSafeClosed);
    bool secureChanged = (motionDetected != prevMotionDetected);
    
    // Update Firebase only if there's a change
    if ((lockedChanged || secureChanged) && Firebase.ready()) {
        // Capture current time for timestamp
        unsigned long currentTime = millis();
        
        // Update timestamps for changed states
        if (lockedChanged) {
            lastLockedChangeTime = currentTime;
        }
        if (secureChanged) {
            lastSecureChangeTime = currentTime;
        }
        
        String devicePath = String(DEVICE_PATH) + deviceId + "/status";
        
        // Use static JSON to reduce memory fragmentation
        static FirebaseJson json;
        json.clear();
        json.set("locked", isSafeClosed);
        json.set("secure", !motionDetected);
                
        // Add timestamps for state changes
        if (lockedChanged) {
            json.set("lockedChangedAt", lastLockedChangeTime);
        }
        if (secureChanged) {
            json.set("secureChangedAt", lastSecureChangeTime);
        }
        
        if (Firebase.RTDB.updateNode(&fbdo, devicePath.c_str(), &json)) {
            Serial.println("✅ Status updated in Firebase");
            
            // Update previous states after successful Firebase update
            prevSafeClosed = isSafeClosed;
            prevMotionDetected = motionDetected;
        } else {
            Serial.print("❌ Firebase update failed: ");
            Serial.println(fbdo.errorReason());
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
