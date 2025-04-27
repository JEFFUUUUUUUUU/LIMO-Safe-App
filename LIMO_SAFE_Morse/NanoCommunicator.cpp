#include "NanoCommunicator.h"
#include "FirebaseHandler.h"
#include "WiFiSetup.h"
#include "RGBLed.h"
#include "FingerprintSensor.h"                                            

//#define NanoSerial Serial
HardwareSerial NanoSerial(1); // UART2 for Nano communication

// Define global variables to track previous states
bool prevSafeClosed = false;
bool prevMotionDetected = false;
unsigned long lastStatusUpdateTime = 0;

// Constants defined using F() macro to store in flash
const unsigned long STATUS_UPDATE_INTERVAL = 2000;

// Define event types
#define EVENT_LOCKED      1
#define EVENT_UNLOCKED    2
#define EVENT_SECURED     3
#define EVENT_COMPROMISED 4

// Queue for pending log entries to prevent blocking
#define MAX_LOG_QUEUE 10  // Increased size to handle separate events
struct LogEntry {
    bool isValid;
    uint8_t eventType;     // Use event type constants
    unsigned long timestamp;
};
LogEntry logQueue[MAX_LOG_QUEUE];
uint8_t logQueueHead = 0;
uint8_t logQueueTail = 0;

// Flag for pending status update
bool pendingStatusUpdate = false;
bool pendingStatusValues[3]; // online, locked, secure

void setupNanoCommunication() {
    //NanoSerial.begin(115200);
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

// Add an event to the log queue
bool queueLogEvent(uint8_t eventType) {
    // If full, overwrite oldest entry
    if (isLogQueueFull()) {
        // Move head forward, effectively discarding oldest entry
        logQueueHead = (logQueueHead + 1) % MAX_LOG_QUEUE;
        Serial.println(F("⚠️ Log queue full! Overwriting oldest entry."));
    }
    
    logQueue[logQueueTail].isValid = true;
    logQueue[logQueueTail].eventType = eventType;
    logQueue[logQueueTail].timestamp = isTimeSynchronized();
    
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
    
    // Update LED status based on specific conditions
    if (!fingerprintEnrollmentInProgress) {
        if (motionDetected) {
           setLEDStatus(STATUS_TAMPERED);
        } else if (!isSafeClosed) {
            setLEDStatus(STATUS_OPEN);
        } else {
           setLEDStatus(STATUS_NORMAL);
        }
    }
    
    // Log state transitions separately
    // Lock state transitions
    if (isSafeClosed && !prevSafeClosed) {
        queueLogEvent(EVENT_LOCKED);
        Serial.println(F("Event logged: LOCKED"));
    } else if (!isSafeClosed && prevSafeClosed) {
        queueLogEvent(EVENT_UNLOCKED);
        Serial.println(F("Event logged: UNLOCKED"));
    }
    
    // Security state transitions
    if (!motionDetected && prevMotionDetected) {
        queueLogEvent(EVENT_SECURED);
        Serial.println(F("Event logged: SECURED"));
    } else if (motionDetected && !prevMotionDetected) {
        queueLogEvent(EVENT_COMPROMISED);
        Serial.println(F("Event logged: COMPROMISED"));
    }
    
    // Define current device status
    bool online = true;  // Device is active when receiving Nano messages
    bool locked = isSafeClosed;
    bool secure = !motionDetected;
    
    // Handle periodic status updates separately from event logging
    unsigned long currentTime = millis();
    if (currentTime - lastStatusUpdateTime >= STATUS_UPDATE_INTERVAL) {
        lastStatusUpdateTime = currentTime;
        
        // Queue status update instead of immediately updating
        pendingStatusUpdate = true;
        pendingStatusValues[0] = online;
        pendingStatusValues[1] = locked;
        pendingStatusValues[2] = secure;
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
            // Construct Firebase path - use existing logs node
            char logsPath[64];
            snprintf(logsPath, sizeof(logsPath), "%s%s/logs", DEVICE_PATH, deviceId);
            
            // Use static FirebaseJson to avoid repeated allocations
            static FirebaseJson logJson;
            logJson.clear();
            logJson.set("timestamp", isTimeSynchronized());
            
            // Set appropriate fields based on event type
            switch(entry.eventType) {
                case EVENT_LOCKED:
                    logJson.set("locked", true);
                    logJson.set("event", "lock");
                    break;
                case EVENT_UNLOCKED:
                    logJson.set("locked", false);
                    logJson.set("event", "lock");
                    break;
                case EVENT_SECURED:
                    logJson.set("secure", true);
                    logJson.set("event", "security");
                    break;
                case EVENT_COMPROMISED:
                    logJson.set("secure", false);
                    logJson.set("event", "security");
                    break;
            }
            
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