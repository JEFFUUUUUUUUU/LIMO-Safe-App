#include "NanoCommunicator.h"
#include "FirebaseHandler.h"
#include "WiFiSetup.h"
#include "RGBLed.h"

HardwareSerial NanoSerial(1); // UART1 for Nano communication

// Define global variables to track previous states
bool prevSafeClosed = false;
bool prevMotionDetected = false;
unsigned long lastStatusUpdateTime = 0;

// Constants defined using F() macro to store in flash
const unsigned long STATUS_UPDATE_INTERVAL = 30000;

void setupNanoCommunication() {
    NanoSerial.begin(SERIAL2_BAUD, SERIAL_8N1, SERIAL2_RX, SERIAL2_TX);
    Serial.println(F("✅ Nano UART Initialized"));
}

void handleNanoData() {
    if (!NanoSerial.available()) return;
    
    // Use fixed-size buffer instead of String
    char buffer[64] = {0};
    uint8_t index = 0;
    unsigned long startTime = millis();
    
    // Read data with timeout
    while (millis() - startTime < 500 && index < sizeof(buffer) - 1) {
        if (NanoSerial.available()) {
            char c = NanoSerial.read();
            if (c == '\n') {
                buffer[index] = '\0';
                break;
            } else if (c != '\r') {
                buffer[index++] = c;
            }
        }
    }
    
    // If no data read or empty, return
    if (index == 0) return;
    
    Serial.print(F("[Nano→ESP32] "));
    Serial.println(buffer);
    
    // Find delimiters
    char* prefix = strtok(buffer, ":");
    if (!prefix || strcmp(prefix, "Nano") != 0) {
        Serial.println(F("❌ Invalid message format from Nano"));
        return;
    }
    
    char* safeStatus = strtok(NULL, ":");
    if (!safeStatus) {
        Serial.println(F("❌ Missing safe status"));
        return;
    }
    
    char* tamperStatus = strtok(NULL, ":");
    if (!tamperStatus) {
        Serial.println(F("❌ Missing tamper status"));
        return;
    }
    
    // Process statuses
    bool isSafeClosed = (strcmp(safeStatus, "CLOSED") == 0);
    bool motionDetected = (strcmp(tamperStatus, "UNSAFE") == 0);
    
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
    
    // Update Firebase only if state changed or periodic update time reached
    unsigned long currentTime = millis();
    if (lockedChanged || secureChanged || (currentTime - lastStatusUpdateTime >= STATUS_UPDATE_INTERVAL)) {
        lastStatusUpdateTime = currentTime;
        
        if (updateDeviceStatus(online, locked, secure)) {
            Serial.println(F("✅ Status updated in Firebase"));
            
            // Log changes if the safe is unlocked, tampered, or states changed
            if (lockedChanged || secureChanged) {
                logStateChange(isSafeClosed, !motionDetected);
            }
            
            // Update previous states only after successful Firebase update
            prevSafeClosed = isSafeClosed;
            prevMotionDetected = motionDetected;
        } else {
            Serial.println(F("⚠️ Firebase status update failed!"));
        }
    }
}

void logStateChange(bool isClosed, bool isSecure) {
    // Construct Firebase path
    char logsPath[64];
    snprintf(logsPath, sizeof(logsPath), "%s%s/logs", DEVICE_PATH, deviceId);
    
    // Use static FirebaseJson to avoid repeated allocations
    static FirebaseJson logJson;
    logJson.clear();
    logJson.set("timestamp", isTimeSynchronized());
    
    // Only log the state that changed
    if (isClosed != prevSafeClosed) {
        logJson.set("locked", isClosed);
    }
    
    if (isSecure != !prevMotionDetected) {
        logJson.set("secure", isSecure);
    }
    
    if (Firebase.RTDB.pushJSON(&fbdo, logsPath, &logJson)) {
        Serial.println(F("✅ Log entry added to Firebase"));
    } else {
        Serial.print(F("❌ Firebase log update failed: "));
        Serial.println(fbdo.errorReason());
    }
}

void sendCommandToNano(const char* command) {
    // Send the command with explicit newline
    NanoSerial.print(command);
    NanoSerial.write('\n');
    NanoSerial.flush();  // Ensure complete transmission
    
    Serial.print(F("[ESP32→Nano] Command sent: '"));
    Serial.print(command);
    Serial.println(F("'"));
    // Small delay to give Nano time to process
    delay(50);
}