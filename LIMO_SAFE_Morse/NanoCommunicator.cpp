#include "NanoCommunicator.h"
#include "FirebaseHandler.h"
#include "WiFiSetup.h"
#include "RGBLed.h"

HardwareSerial NanoSerial(1); // UART2 for Nano communication

// Define global variables to track previous states
bool prevSafeClosed = false;
bool prevMotionDetected = false;
unsigned long lastStatusUpdateTime = 0;

// Constants defined using F() macro to store in flash
const unsigned long STATUS_UPDATE_INTERVAL = 30000;

// Queue for pending log entries to prevent blocking
#define MAX_LOG_QUEUE 5
struct LogEntry {
    bool isValid;
    bool isClosed;
    bool isSecure;
    unsigned long timestamp;
};
LogEntry logQueue[MAX_LOG_QUEUE];
uint8_t logQueueHead = 0;
uint8_t logQueueTail = 0;

// Flag for pending status update
bool pendingStatusUpdate = false;
bool pendingStatusValues[3]; // online, locked, secure

void setupNanoCommunication() {
    NanoSerial.begin(SERIAL2_BAUD, SERIAL_8N1, SERIAL2_RX, SERIAL2_TX);
    Serial.println(F("✅ Nano UART Initialized"));
    
    // Initialize log queue
    for (int i = 0; i < MAX_LOG_QUEUE; i++) {
        logQueue[i].isValid = false;
    }
}

// Check if log queue is full
bool isLogQueueFull() {
    return (logQueueTail + 1) % MAX_LOG_QUEUE == logQueueHead;
}

// Check if log queue is empty
bool isLogQueueEmpty() {
    return logQueueHead == logQueueTail;
}

// Add an entry to the log queue
bool queueLogEntry(bool isClosed, bool isSecure) {
    if (isLogQueueFull()) {
        Serial.println(F("⚠️ Log queue full! Entry discarded."));
        return false;
    }
    
    logQueue[logQueueTail].isValid = true;
    logQueue[logQueueTail].isClosed = isClosed;
    logQueue[logQueueTail].isSecure = isSecure;
    logQueue[logQueueTail].timestamp = millis();
    
    logQueueTail = (logQueueTail + 1) % MAX_LOG_QUEUE;
    return true;
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
    
    // Queue status updates instead of blocking immediately
    unsigned long currentTime = millis();
    if (lockedChanged || secureChanged || (currentTime - lastStatusUpdateTime >= STATUS_UPDATE_INTERVAL)) {
        lastStatusUpdateTime = currentTime;
        
        // Queue status update instead of immediately updating
        pendingStatusUpdate = true;
        pendingStatusValues[0] = online;
        pendingStatusValues[1] = locked;
        pendingStatusValues[2] = secure;
        
        // Queue log entry if states changed (doesn't block)
        static unsigned long lastLogEntryTime = 0;
        if (millis() - lastLogEntryTime > 1000) {
            if (lockedChanged || secureChanged) {
                queueLogEntry(isSafeClosed, !motionDetected);
                lastLogEntryTime = millis();
            }
        }
    }
    // Update previous states immediately so we don't queue duplicates
    prevSafeClosed = isSafeClosed;
    prevMotionDetected = motionDetected;
}

// Process any pending Firebase operations
// Call this function regularly from your main loop
void processFirebaseQueue() {
    static unsigned long lastFirebaseOpTime = 0;
    unsigned long currentTime = millis();
    
    // Add a small delay between Firebase operations to prevent overwhelming
    if (currentTime - lastFirebaseOpTime < 200) {
        return;
    }
    
    // Process pending status update first (higher priority)
    if (pendingStatusUpdate) {
        if (updateDeviceStatus(pendingStatusValues[0], pendingStatusValues[1], pendingStatusValues[2])) {
            Serial.println(F("✅ Status updated in Firebase"));
            pendingStatusUpdate = false;
            lastFirebaseOpTime = currentTime;
        } else {
            Serial.println(F("⚠️ Firebase status update failed!"));
            // Will retry on next call
        }
        return; // Process one operation per call to avoid blocking
    }
    
    // Process log queue if not empty and no status update is pending
    if (!isLogQueueEmpty()) {
        LogEntry &entry = logQueue[logQueueHead];
        
        if (entry.isValid) {
            // Construct Firebase path
            char logsPath[64];
            snprintf(logsPath, sizeof(logsPath), "%s%s/logs", DEVICE_PATH, deviceId);
            
            // Use static FirebaseJson to avoid repeated allocations
            static FirebaseJson logJson;
            logJson.clear();
            logJson.set("timestamp", isTimeSynchronized());
            logJson.set("locked", entry.isClosed);
            logJson.set("secure", entry.isSecure);
            
            if (Firebase.RTDB.pushJSON(&fbdo, logsPath, &logJson)) {
                Serial.println(F("✅ Log entry added to Firebase"));
                
                // Mark as processed and move head
                entry.isValid = false;
                logQueueHead = (logQueueHead + 1) % MAX_LOG_QUEUE;
                lastFirebaseOpTime = currentTime;
            } else {
                Serial.print(F("❌ Firebase log update failed: "));
                Serial.println(fbdo.errorReason());
                // Will retry on next call
            }
        } else {
            // Invalid entry, just skip it
            logQueueHead = (logQueueHead + 1) % MAX_LOG_QUEUE;
        }
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