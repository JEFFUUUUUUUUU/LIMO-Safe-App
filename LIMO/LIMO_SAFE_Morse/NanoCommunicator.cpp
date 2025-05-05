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
const unsigned long STATUS_UPDATE_INTERVAL = 1000;

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

// Track Firebase connection attempts
unsigned long lastFirebaseConnectionAttempt = 0;
const unsigned long FIREBASE_RECONNECT_INTERVAL = 5000; // 5 seconds between reconnection attempts

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
    
    // Check if this is an OTP code from Nano
    if (strncmp(buffer, "OTP:", 4) == 0) {
        // Extract the OTP code
        String otpCode = String(buffer + 4); // Skip "OTP:" prefix
        processNanoCommand(otpCode);
        return;
    }
    
    // Process normal status messages
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
        } else if(!isFirebaseReady()){
           setLEDStatus(STATUS_OFFLINE);
           
           // Try to reconnect to Firebase if enough time has passed since last attempt
           unsigned long currentTime = millis();
           if (currentTime - lastFirebaseConnectionAttempt >= FIREBASE_RECONNECT_INTERVAL) {
               lastFirebaseConnectionAttempt = currentTime;
               //checkFirebaseConnection(); // Non-blocking function that tries to reconnect Firebase
           }
        } else{
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
    
    // Update previous states immediately so we don't queue duplicates
    prevSafeClosed = isSafeClosed;
    prevMotionDetected = motionDetected;
    
    // Define current device status
    bool online = isFirebaseReady();  // Device is online only if Firebase is ready
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
}

// Process OTP command received from Nano
void processNanoCommand(const String& command) {
    // For OTP verification
    if (command.length() == 5) { // Assuming 5-digit OTP code
        Serial.print(F("🔑 Received OTP code from Nano: "));
        Serial.println(command);
        
        // Verify OTP code using Firebase
        if (verifyOTP(command)) {
            // Send validation response back to Nano
            sendCommandToNano("UNLOCK");
            setLEDStatus(STATUS_UNLOCKED);
            Serial.println(F("✅ OTP verified successfully, sent confirmation to Nano"));

        } else {
            // Send invalid response back to Nano
            sendCommandToNano("OTP_INVALID");
            Serial.println(F("❌ Invalid OTP code, sent rejection to Nano"));

        }
    } else {
        Serial.println(F("❌ Received invalid command format from Nano"));
    }
}

// Process any pending Firebase operations
// Modified processFirebaseQueue() function with proper Firebase readiness checks
void processFirebaseQueue() {
    static unsigned long lastFirebaseOpTime = 0;
    static unsigned long lastFirebaseRetryTime = 0;
    static unsigned long lastReportTime = 0;
    static bool firebaseErrorLogged = false;
    unsigned long currentTime = millis();
    
    // Define report interval for limiting status messages
    const unsigned long REPORT_INTERVAL = 30000; // Only report every 30 seconds
    
    // Add a small delay between Firebase operations to prevent overwhelming
    if (currentTime - lastFirebaseOpTime < 200) {
        return;
    }
    
    // Skip ALL operations if Firebase is not ready, but retry connection periodically
    if (!isFirebaseReady()) {
        if (currentTime - lastFirebaseRetryTime >= FIREBASE_RECONNECT_INTERVAL) {
            lastFirebaseRetryTime = currentTime;
            
            // Try to reconnect Firebase
            if (!firebaseErrorLogged) {
                Serial.println(F("⚠️ Firebase not ready. Data operations paused."));
                firebaseErrorLogged = true;
            }
            
            checkFirebaseConnection(); // Non-blocking function that tries to reconnect
        }
        return; // Skip ALL Firebase operations when not ready
    }
    
    // Reset error logged flag when Firebase is back online
    if (firebaseErrorLogged) {
        Serial.println(F("✅ Firebase reconnected. Resuming data operations."));
        firebaseErrorLogged = false;
    }
    
    // Process pending status update first (higher priority)
    if (pendingStatusUpdate) {
        // Double-check Firebase is ready right before the operation
        if (isFirebaseReady()) {
            bool updateResult = updateDeviceStatus(pendingStatusValues[0], pendingStatusValues[1], pendingStatusValues[2]);
            
            if (updateResult) {
                pendingStatusUpdate = false;
                lastFirebaseOpTime = currentTime;
            } else {
                // Only report status update failures periodically to avoid flooding serial output
                if (currentTime - lastReportTime >= REPORT_INTERVAL) {
                    Serial.println(F("⚠️ Status updates pending"));
                    lastReportTime = currentTime;
                }
                
                lastFirebaseOpTime = currentTime;
            }
        } else {
            // Firebase became disconnected between checks
            if (currentTime - lastReportTime >= REPORT_INTERVAL) {
                Serial.println(F("⚠️ Firebase disconnected - status update deferred"));
                lastReportTime = currentTime;
            }
        }
        return; // Process one operation per call to avoid blocking
    }
    
    // Process log queue if not empty and no status update is pending
    if (!isLogQueueEmpty()) {
        // Double-check Firebase is ready right before operation
        if (isFirebaseReady()) {
            LogEntry &entry = logQueue[logQueueHead];
            
            if (entry.isValid) {
                // Construct Firebase path - use existing logs node
                char logsPath[64];
                snprintf(logsPath, sizeof(logsPath), "%s%s/logs", DEVICE_PATH, deviceId);
                Serial.println(logsPath);
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
                    //Serial.println(F("✅ Log entry added to Firebase"));
                    
                    // Mark as processed and move head
                    entry.isValid = false;
                    logQueueHead = (logQueueHead + 1) % MAX_LOG_QUEUE;
                    lastFirebaseOpTime = currentTime;
                } else {
                    // Don't print detailed error messages to avoid blocking
                    // Just report queue size periodically to indicate backlog
                    if (currentTime - lastReportTime >= REPORT_INTERVAL) {
                        uint8_t queueSize = (logQueueTail >= logQueueHead) ? 
                            (logQueueTail - logQueueHead) : 
                            (MAX_LOG_QUEUE - logQueueHead + logQueueTail);
                        
                        Serial.print(F("⚠️ Log entries queued: "));
                        Serial.println(queueSize);
                        lastReportTime = currentTime;
                    }
                    
                    // Will retry on next call
                    lastFirebaseOpTime = currentTime;
                }
            }
        } else {
            // Firebase became disconnected between checks
            if (currentTime - lastReportTime >= REPORT_INTERVAL) {
                Serial.println(F("⚠️ Firebase disconnected - log entry deferred"));
                lastReportTime = currentTime;
            }
        }
    }
}

void logStateChange(bool isClosed, bool isSecure) {
    // Log both state types
    queueLogEvent(isClosed ? EVENT_LOCKED : EVENT_UNLOCKED);
    queueLogEvent(isSecure ? EVENT_SECURED : EVENT_COMPROMISED);
}

void sendCommandToNano(const char* command) {
    NanoSerial.print(command);
    NanoSerial.write('\n');
    NanoSerial.flush();  // Ensure complete transmission
    
    Serial.print(F("[ESP32→Nano] Command sent: '"));
    Serial.print(command);
    Serial.println(F("'"));
    // Small delay to give Nano time to process
    delay(50);
}