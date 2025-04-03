#line 1 "E:\\Arduino IDE\\Arduino\\LIMO_SAFE_Morse\\NanoCommunicator.cpp"
#include "NanoCommunicator.h"
#include "FirebaseHandler.h"
#include "WiFiSetup.h"
#include "RGBLed.h"

HardwareSerial NanoSerial(1); // UART1 for Nano communication

void setupNanoCommunication() {
    NanoSerial.begin(SERIAL2_BAUD, SERIAL_8N1, SERIAL2_RX, SERIAL2_TX);
    Serial.println("✅ Nano UART Initialized");
}

// Define global variables to track previous states
bool prevSafeClosed = false;  // Changed initial value to false to ensure first state is detected
bool prevMotionDetected = false;  // Changed initial value to false to ensure first state is detected
bool statesInitialized = false;  // Flag to track if we've received initial state
unsigned long lastLockedChangeTime = 0;
unsigned long lastSecureChangeTime = 0;
unsigned long lastMessageTime = 0;  // To track message timing for debouncing
unsigned long lastStatusUpdateTime = 0;

// Debounce time in milliseconds
const unsigned long DEBOUNCE_TIME = 500;
const unsigned long STATUS_UPDATE_INTERVAL = 30000;

void handleNanoData() {
    if (!NanoSerial.available()) return;
    
    String message = NanoSerial.readStringUntil('\n');
    Serial.print("[Nano→ESP32] ");
    Serial.println(message);
    
    // Expected format: "Nano:<ID>:<SafeStatus>:<TamperStatus>"
    int indices[3] = {-1, -1, -1};
    
    // Locate colons efficiently
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
    
    // Extract values efficiently
    String nanoID = message.substring(indices[0] + 1, indices[1]);
    bool isSafeClosed = message.substring(indices[1] + 1, indices[2]).equals("CLOSED");
    String tamperStatus = message.substring(indices[2] + 1);
    tamperStatus.trim();  // Trim it separately
    bool motionDetected = tamperStatus.equals("UNSAFE");
    
    // Send acknowledgment to Nano
    NanoSerial.print("ESP32: Received (");
    NanoSerial.print(message);
    NanoSerial.println(")");
    
    // Update LED status immediately for security indication
    if (!isSafeClosed || motionDetected) {
        setLEDStatus(STATUS_TAMPERED);
    }
    
    // Track changes
    bool lockedChanged = (isSafeClosed != prevSafeClosed);
    bool secureChanged = (motionDetected != prevMotionDetected);

    // Define current device status
    bool online = true;  // Device is active when receiving Nano messages
    bool locked = isSafeClosed;
    bool secure = !motionDetected;
    bool otpStatus = false;
    bool fingerprintStatus = false;
    
    // Update Firebase via updateDeviceStatus
    bool firebaseUpdateSuccess = false;
    if (updateDeviceStatus(online, locked, secure)) {
        Serial.println("✅ Status updated in Firebase via updateDeviceStatus");
        firebaseUpdateSuccess = true;
        
        // Log changes if the safe is unlocked, tampered, or states changed
        if (lockedChanged || secureChanged || !isSafeClosed || motionDetected) {
            String logsPath = String(DEVICE_PATH) + deviceId + "/logs";
            
            static FirebaseJson logJson;
            logJson.clear();
            logJson.set("timestamp", isTimeSynchronized());

            if (lockedChanged) logJson.set("locked", isSafeClosed);
            if (secureChanged) logJson.set("secure", !motionDetected);

            if (Firebase.RTDB.pushJSON(&fbdo, logsPath.c_str(), &logJson)) {
                Serial.println("✅ Log entry added to Firebase");
            } else {
                Serial.print("❌ Firebase log update failed: ");
                Serial.println(fbdo.errorReason());
            }
        }
        
        // Update previous states only after successful Firebase update
        prevSafeClosed = isSafeClosed;
        prevMotionDetected = motionDetected;
    } else {
        Serial.println("⚠️ Firebase status update failed!");
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