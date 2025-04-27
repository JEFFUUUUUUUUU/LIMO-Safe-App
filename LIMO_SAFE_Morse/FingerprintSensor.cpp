#include "FingerprintSensor.h"
#include "NanoCommunicator.h"
#include "WiFiSetup.h"
#include "RGBLed.h"

// Define pins for fingerprint sensor (adjust if necessary)

HardwareSerial FingerSerial(2);  // Use UART2
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&FingerSerial);

// Firebase data cache to reduce redundant queries
struct {
    unsigned long lastRefreshTime = 0;
    const unsigned long CACHE_TIMEOUT = 5000; // 5 seconds cache validity
    FirebaseJson fingerprintMappings;
    bool isValid = false;
    
    bool needsRefresh() {
        return !isValid || (millis() - lastRefreshTime > CACHE_TIMEOUT);
    }
    
    void refresh() {
        // Only refresh if Firebase is ready
        if (!isFirebaseReady()) {
            return;
        }
        
        String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
        
        if (Firebase.RTDB.getJSON(&fbdo, mappingsPath.c_str())) {
            FirebaseJson* json = fbdo.jsonObjectPtr();
            if (json != nullptr) {
                fingerprintMappings = *json;
                isValid = true;
                lastRefreshTime = millis();
            }
        }
    }
    
    FirebaseJson* getData() {
        if (needsRefresh()) {
            refresh();
        }
        return isValid ? &fingerprintMappings : nullptr;
    }
} firebaseCache;

// State variables
bool fingerprintEnrollmentInProgress = false;
FingerprintState fingerprintState = FP_IDLE;
EnrollmentState enrollmentState = ENROLL_IDLE;

// Timer variables
unsigned long lastFingerprintCheck = 0;
unsigned long enrollmentStateStartTime = 0;
unsigned long lastCommandCheck = 0;
const unsigned long FINGERPRINT_CHECK_INTERVAL = 10000; // 10 seconds between checks
const unsigned long ENROLLMENT_STATE_TIMEOUT = 30000;   // 30 seconds timeout
const unsigned long COMMAND_CHECK_INTERVAL = 10000;     // 10 seconds between command checks

// Enrollment variables
int currentEnrollmentId = -1;
String currentEnrollmentUserId = "";

// Delete command variables
bool deleteCommandPending = false;
int fingerprintToDelete = -1;  // -1 means delete all, -2 means multiple, positive number is specific ID
std::vector<int> fingerprints_to_delete;

void initializeFingerprint() {
    FingerSerial.begin(57600, SERIAL_8N1, 32, 33);
    
    if (finger.verifyPassword()) {
        Serial.println(F("✅ Fingerprint sensor initialized"));
    } else {
        Serial.println(F("❌ Fingerprint sensor not found!"));
    }
    
    // Set security level (1-5)
    finger.setSecurityLevel(3);
}

bool authenticateUser() {
    static unsigned long stateStartTime = 0;
    const unsigned long STATE_TIMEOUT = 500; // 500ms timeout for each state
    
    // Check if there's a finger on the sensor (only once per main loop)
    if (fingerprintState == FP_IDLE) {
        uint8_t p = finger.getImage();
        if (p == FINGERPRINT_OK) {
            // Finger detected! Start the authentication process
            fingerprintState = FP_CONVERT_IMAGE;
            stateStartTime = millis();
        } else {
            // No finger or error, remain idle
            return false;
        }
    }
    
    // State timeout check
    if (millis() - stateStartTime > STATE_TIMEOUT) {
        // Reset to idle if any state takes too long
        fingerprintState = FP_IDLE;
        return false;
    }
    
    // State machine for fingerprint processing
    switch (fingerprintState) {
        case FP_CONVERT_IMAGE:
            {
                uint8_t p = finger.image2Tz();
                if (p == FINGERPRINT_OK) {
                    fingerprintState = FP_SEARCH;
                    stateStartTime = millis();
                } else {
                    // Error in conversion, return to idle
                    fingerprintState = FP_IDLE;
                }
            }
            return false;
            
        case FP_SEARCH:
            {
                uint8_t p = finger.fingerSearch();
                if (p == FINGERPRINT_OK) {
                    Serial.print(F("✅ Recognized ID #")); 
                    Serial.println(finger.fingerID);
                    fingerprintState = FP_IDLE;
                    return true; // Authentication successful
                } else {
                    fingerprintState = FP_IDLE;
                    if (!fingerprintEnrollmentInProgress) {
                        setLEDStatus(STATUS_ERROR);
                    }
                }
            }
            return false;
            
        default:
            fingerprintState = FP_IDLE;
            return false;
    }
}

bool enrollFingerprint(int id) {
    // Only start enrollment if we're in IDLE state
    if (enrollmentState == ENROLL_IDLE) {
        fingerprintEnrollmentInProgress = true;
        setLEDStatus(STATUS_SCANNING);
        Serial.println(F("Ready to enroll a fingerprint!"));
        Serial.print(F("Please place finger on sensor for ID #")); 
        Serial.println(id);
        
        // Initialize enrollment process
        currentEnrollmentId = id;
        enrollmentState = ENROLL_WAITING_FIRST;
        enrollmentStateStartTime = millis();
        return false; // Not complete yet
    }
    
    // Check for timeout in any state
    if (millis() - enrollmentStateStartTime > ENROLLMENT_STATE_TIMEOUT && 
        enrollmentState != ENROLL_IDLE && 
        enrollmentState != ENROLL_COMPLETE &&
        enrollmentState != ENROLL_FAILED) {
        Serial.println(F("❌ Enrollment timeout"));
        enrollmentState = ENROLL_FAILED;
        setLEDStatus(STATUS_ERROR);
        return false;
    }
    
    // State machine for enrollment
    switch (enrollmentState) {
        case ENROLL_WAITING_FIRST:
            // Waiting for finger placement
            {
                uint8_t p = finger.getImage();
                if (p == FINGERPRINT_OK) {
                    Serial.println(F("First image captured"));
                    enrollmentState = ENROLL_CAPTURING_FIRST;
                    enrollmentStateStartTime = millis();
                } else if (p == FINGERPRINT_NOFINGER) {
                    // Still waiting, blink LED to indicate
                    if ((millis() / 500) % 2 == 0) {
                        setLEDStatus(STATUS_SCANNING);
                    } else {
                        setLEDStatus(STATUS_ONLINE);
                    }
                }
            }
            return false;
            
        case ENROLL_CAPTURING_FIRST:
            // Convert first image to template
            {
                uint8_t p = finger.image2Tz(1);
                if (p == FINGERPRINT_OK) {
                    Serial.println(F("Remove finger"));
                    enrollmentState = ENROLL_WAITING_REMOVE;
                    enrollmentStateStartTime = millis();
                } else {
                    Serial.println(F("❌ Image conversion failed"));
                    enrollmentState = ENROLL_FAILED;
                    setLEDStatus(STATUS_ERROR);
                }
            }
            return false;
            
        case ENROLL_WAITING_REMOVE:
            // Waiting for finger removal
            {
                uint8_t p = finger.getImage();
                if (p == FINGERPRINT_NOFINGER) {
                    Serial.println(F("Place same finger again"));
                    enrollmentState = ENROLL_WAITING_SECOND;
                    enrollmentStateStartTime = millis();
                }
            }
            return false;
            
        case ENROLL_WAITING_SECOND:
            // Waiting for second finger placement
            {
                uint8_t p = finger.getImage();
                if (p == FINGERPRINT_OK) {
                    Serial.println(F("Second image captured"));
                    enrollmentState = ENROLL_CAPTURING_SECOND;
                    enrollmentStateStartTime = millis();
                } else if (p == FINGERPRINT_NOFINGER) {
                    // Still waiting, blink LED to indicate
                    if ((millis() / 500) % 2 == 0) {
                        setLEDStatus(STATUS_SCANNING);
                    } else {
                        setLEDStatus(STATUS_ONLINE);
                    }
                }
            }
            return false;
            
        case ENROLL_CAPTURING_SECOND:
            // Convert second image to template
            {
                uint8_t p = finger.image2Tz(2);
                if (p == FINGERPRINT_OK) {
                    Serial.println(F("Second image converted"));
                    enrollmentState = ENROLL_CREATE_MODEL;
                    enrollmentStateStartTime = millis();
                } else {
                    Serial.println(F("❌ Image conversion failed"));
                    enrollmentState = ENROLL_FAILED;
                    setLEDStatus(STATUS_ERROR);
                }
            }
            return false;
            
        case ENROLL_CREATE_MODEL:
            // Create model from the two templates
            {
                uint8_t p = finger.createModel();
                if (p == FINGERPRINT_OK) {
                    Serial.println(F("Model created"));
                    enrollmentState = ENROLL_STORE_MODEL;
                    enrollmentStateStartTime = millis();
                } else {
                    Serial.println(F("❌ Failed to create model"));
                    enrollmentState = ENROLL_FAILED;
                    setLEDStatus(STATUS_ERROR);
                }
            }
            return false;
            
        case ENROLL_STORE_MODEL:
            // Store the model in the database
            {
                uint8_t p = finger.storeModel(currentEnrollmentId);
                if (p == FINGERPRINT_OK) {
                    Serial.println(F("✅ Fingerprint enrolled successfully!"));
                    enrollmentState = ENROLL_COMPLETE;
                    setLEDStatus(STATUS_REGISTERED);
                    delay(3000);
                } else {
                    Serial.println(F("❌ Error storing fingerprint"));
                    enrollmentState = ENROLL_FAILED;
                    setLEDStatus(STATUS_ERROR);
                }
            }
            return false;
            
        case ENROLL_COMPLETE:
            // Enrollment successful, reset state
            enrollmentState = ENROLL_IDLE;
            return true;
            
        case ENROLL_FAILED:
            // Enrollment failed, reset state
            enrollmentState = ENROLL_IDLE;
            return false;
            
        default:
            enrollmentState = ENROLL_IDLE;
            return false;
    }
}

// Unified function to check and process Firebase commands
void checkForCommands() {
    // Don't check if enrollment is in progress or if it's too soon
    if (enrollmentState != ENROLL_IDLE || millis() - lastCommandCheck < COMMAND_CHECK_INTERVAL) {
        return;
    }
    
    lastCommandCheck = millis();
    
    // Make sure Firebase is ready
    if (!isFirebaseReady()) {
        return;
    }

    // Get the fingerprint mappings data (using cache if available)
    FirebaseJson* json = firebaseCache.getData();
    if (json == nullptr) {
        return;
    }

    // Iterate through all users in the mappings
    size_t iterCount = json->iteratorBegin();
    FirebaseJson::IteratorValue value;
    bool commandFound = false;
    
    for (size_t i = 0; i < iterCount && !commandFound; i++) {
        value = json->valueAt(i);
        String userId = value.key;
        String status = value.value;
        status.replace("\"", ""); // Remove quotes
        
        String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
        String userPath = mappingsPath + "/" + userId;
        
        // Process enrollment requests
        if (status == "enroll") {
            Serial.print("📱 Fingerprint enrollment requested for user: ");
            Serial.println(userId);
            
            // Find the next available fingerprint ID
            int availableId = findNextAvailableId();
            
            if (availableId <= 0) {
                Serial.println("❌ No available fingerprint slots!");
                
                // Log failure
                FirebaseJson logEntry;
                logEntry.set("timestamp", isTimeSynchronized());
                logEntry.set("event", "fingerprint_enrollment_failed");
                logEntry.set("userId", userId);
                logEntry.set("reason", "no_available_slots");

                String logPath = String("devices/") + deviceId + "/logs";
                Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);

                Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
                continue;
            }
            
            // Start enrollment process
            currentEnrollmentId = availableId;
            currentEnrollmentUserId = userId;
            enrollFingerprint(currentEnrollmentId);
            commandFound = true;
        }
        // Process delete_all command
        else if (status == "delete_all") {
            Serial.print("📱 Command received: Delete all fingerprints for user: ");
            Serial.println(userId);
            deleteCommandPending = true;
            fingerprintToDelete = -1;
            currentEnrollmentUserId = userId;
            fingerprints_to_delete.clear();
            
            // Clear the command after reading
            Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
            commandFound = true;
        }
        // Process delete_ID or delete_ID1,ID2,ID3 format
        else if (status.startsWith("delete_")) {
            Serial.print("📱 Command received for user: ");
            Serial.print(userId);
            Serial.print(" - ");
            Serial.println(status);
            
            // Extract IDs from the command
            String idsToDelete = status.substring(7); // Remove "delete_" prefix
            
            // Parse comma-separated IDs
            fingerprints_to_delete.clear();
            int commaIndex = -1;
            int startPos = 0;
            
            do {
                commaIndex = idsToDelete.indexOf(',', startPos);
                String idStr;
                
                if (commaIndex != -1) {
                    idStr = idsToDelete.substring(startPos, commaIndex);
                    startPos = commaIndex + 1;
                } else {
                    idStr = idsToDelete.substring(startPos);
                }
                
                // Convert to integer and add to our vector
                if (idStr.length() > 0) {
                    int id = idStr.toInt();
                    fingerprints_to_delete.push_back(id);
                    Serial.print("Added ID to delete list: ");
                    Serial.println(id);
                }
            } while (commaIndex != -1);
            
            if (fingerprints_to_delete.size() > 0) {
                deleteCommandPending = true;
                currentEnrollmentUserId = userId;
                
                // For backward compatibility with original code
                fingerprintToDelete = (fingerprints_to_delete.size() == 1) ? 
                                      fingerprints_to_delete[0] : -2; // -2 indicates multiple IDs
                
                Serial.print("Found ");
                Serial.print(fingerprints_to_delete.size());
                Serial.println(" fingerprint ID(s) to delete");
            } else {
                Serial.println("❌ No valid fingerprint IDs found in delete command");
            }
            
            // Clear the command after reading
            Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
            commandFound = true;
        }
    }
    
    json->iteratorEnd();
    
    // If a command was found and processed, invalidate the cache
    if (commandFound) {
        firebaseCache.isValid = false;
    }
}

// Function to update Firebase after enrollment completes
void updateFingerprintStatus(bool success) {
    if (currentEnrollmentUserId.isEmpty()) {
        return;
    }

    // Paths for Firebase updates
    String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
    String userPath = mappingsPath + "/" + currentEnrollmentUserId;
    String logPath = String("devices/") + deviceId + "/logs";
    
    if (success) {
        // Update the user's status to "registered"
        Firebase.RTDB.setString(&fbdo, userPath.c_str(), "registered");
        
        // Store the fingerprint ID to user mapping for later reference
        String idMappingPath = mappingsPath + "/ids/" + String(currentEnrollmentId);
        Firebase.RTDB.setString(&fbdo, idMappingPath.c_str(), currentEnrollmentUserId);
        
        // Add fingerprint ID to user's registeredDevices structure
        String userDevicesPath = String(USERS_PATH) + currentEnrollmentUserId + "/registeredDevices/" + deviceId + "/fingerprint";
        
        // Check if the user already has fingerprints registered and update
        FirebaseJsonArray fingerprintArray;
        if (Firebase.RTDB.getArray(&fbdo, userDevicesPath.c_str())) {
            fingerprintArray = fbdo.jsonArray();
        }
        
        // Add the new fingerprint ID to the array
        fingerprintArray.add(currentEnrollmentId);
        Firebase.RTDB.setArray(&fbdo, userDevicesPath.c_str(), &fingerprintArray);
        
        Serial.print("✅ Added fingerprint ID ");
        Serial.print(currentEnrollmentId);
        Serial.print(" to user's registered device ");
        Serial.println(deviceId);
        
        // Log successful enrollment
        FirebaseJson logEntry;
        logEntry.set("timestamp", isTimeSynchronized());
        logEntry.set("event", "fingerprint_enrolled");
        logEntry.set("userId", currentEnrollmentUserId);
        logEntry.set("fingerprintId", currentEnrollmentId);
        Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
    } else {
        // Log failure
        FirebaseJson logEntry;
        logEntry.set("timestamp", isTimeSynchronized());
        logEntry.set("event", "fingerprint_enrollment_failed");
        logEntry.set("userId", currentEnrollmentUserId);
        logEntry.set("reason", "enrollment_error");
        Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
        
        // Remove the pending enrollment request
        Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
    }
    
    // Invalidate the cache since we made changes
    firebaseCache.isValid = false;
}

// Process the enrollment state machine
void processEnrollment() {
    // If enrollment is in progress
    if (enrollmentState != ENROLL_IDLE) {
        // Process the enrollment state machine
        bool enrollmentResult = enrollFingerprint(currentEnrollmentId);
        fingerprintEnrollmentInProgress = (enrollmentState != ENROLL_IDLE);
        
        // If enrollment completed (success or failure)
        if (enrollmentState == ENROLL_IDLE && !currentEnrollmentUserId.isEmpty()) {
            fingerprintEnrollmentInProgress = false;
            
            // Update Firebase with the enrollment result
            updateFingerprintStatus(enrollmentResult);
            
            // Reset enrollment state variables
            currentEnrollmentUserId = "";
            currentEnrollmentId = -1;
            setLEDStatus(STATUS_ONLINE);
        }
    }
}

// Helper function to update user's fingerprint array in Firebase
void updateUserFingerprintArray(const String& userId, const std::vector<int>& deletedIds) {
    if (userId.isEmpty()) {
        return;
    }
    
    String userFingerprintPath = String(USERS_PATH) + userId + "/registeredDevices/" + deviceId + "/fingerprint";
    
    if (Firebase.RTDB.getArray(&fbdo, userFingerprintPath.c_str())) {
        FirebaseJsonArray fingerprintArray = fbdo.jsonArray();
        FirebaseJsonArray newArray;
        size_t arraySize = fingerprintArray.size();
        
        // Create a new array excluding all deleted fingerprint IDs
        for (size_t i = 0; i < arraySize; i++) {
            FirebaseJsonData result;
            fingerprintArray.get(result, i);
            int currentId = result.intValue;
            
            // Check if this ID was in our deletion list
            bool shouldKeep = true;
            for (int deleteId : deletedIds) {
                if (currentId == deleteId) {
                    shouldKeep = false;
                    break;
                }
            }
            
            if (shouldKeep) {
                newArray.add(currentId);
            }
        }
        
        // Update the array in Firebase
        if (newArray.size() > 0) {
            Firebase.RTDB.setArray(&fbdo, userFingerprintPath.c_str(), &newArray);
        } else {
            // If array is empty, remove it completely
            Firebase.RTDB.deleteNode(&fbdo, userFingerprintPath.c_str());
        }
    }
}

// Log fingerprint deletion events to Firebase
void logDeletionEvent(const String& eventType, const String& userId, int count, int successCount, int specificId){
    if (!isFirebaseReady()) {
        return;
    }
    
    FirebaseJson logEntry;
    logEntry.set("timestamp", isTimeSynchronized());
    logEntry.set("event", eventType);
    
    if (!userId.isEmpty()) {
        logEntry.set("userId", userId);
    }
    
    if (count > 0) {
        logEntry.set("total", count);
    }
    
    if (successCount > 0) {
        logEntry.set("success", successCount);
    }
    
    if (specificId >= 0) {
        logEntry.set("fingerprintId", specificId);
    }
    
    String logPath = String("devices/") + deviceId + "/logs";
    Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
}

// Process pending delete commands
void processDeleteCommands() {
    if (!deleteCommandPending || enrollmentState != ENROLL_IDLE) {
        return;
    }
    
    String userId = currentEnrollmentUserId;
    
    // Delete all fingerprints for a specific user
    if (fingerprintToDelete == -1) {
        if (userId.isEmpty()) {
            Serial.println(F("❌ No user ID specified for fingerprint deletion"));
            deleteCommandPending = false;
            return;
        }
        
        Serial.print(F("⚠️ Executing command: Delete all fingerprints for user "));
        Serial.println(userId);
        
        // Get the user's fingerprint array
        String userFingerprintPath = String(USERS_PATH) + userId + "/registeredDevices/" + deviceId + "/fingerprint";
        
        if (Firebase.RTDB.getArray(&fbdo, userFingerprintPath.c_str())) {
            FirebaseJsonArray fingerprintArray = fbdo.jsonArray();
            size_t arraySize = fingerprintArray.size();
            bool allSuccess = true;
            int successCount = 0;
            std::vector<int> deletedIds;
            
            Serial.print(F("Found "));
            Serial.print(arraySize);
            Serial.println(F(" fingerprints to delete"));
            
            // Delete each fingerprint in the array
            for (size_t i = 0; i < arraySize; i++) {
                FirebaseJsonData result;
                fingerprintArray.get(result, i);
                int fpId = result.intValue;
                
                Serial.print(F("Deleting fingerprint ID #"));
                Serial.println(fpId);
                
                uint8_t p = finger.deleteModel(fpId);
                if (p == FINGERPRINT_OK) {
                    Serial.print(F("✅ Deleted fingerprint ID #"));
                    Serial.println(fpId);
                    successCount++;
                    deletedIds.push_back(fpId);
                } else {
                    Serial.print(F("❌ Failed to delete fingerprint ID #"));
                    Serial.println(fpId);
                    allSuccess = false;
                }
            }
            
            // Remove the fingerprint array from the user's registered devices
            Firebase.RTDB.deleteNode(&fbdo, userFingerprintPath.c_str());
            
            // Log the event
            logDeletionEvent(
                allSuccess ? "user_fingerprints_deleted" : "user_fingerprints_delete_partial", 
                userId, 
                arraySize, 
                successCount
            );
            
            setLEDStatus(allSuccess ? STATUS_ONLINE : STATUS_ERROR);
        } else {
            Serial.println(F("⚠️ No fingerprints found for this user"));
            logDeletionEvent("fingerprint_delete_no_fingerprints", userId);
        }
    } 
    // Delete specific fingerprint IDs (multiple)
    else if (fingerprintToDelete == -2 || !fingerprints_to_delete.empty()) {
        int deleteCount = fingerprints_to_delete.size();
        int successCount = 0;
        std::vector<int> deletedIds;
        
        Serial.print(F("⚠️ Executing command: Delete multiple fingerprint IDs ("));
        Serial.print(deleteCount);
        Serial.println(F(" IDs)"));
        
        // Process each ID in the vector
        for (int id : fingerprints_to_delete) {
            Serial.print(F("Attempting to delete fingerprint ID #"));
            Serial.println(id);
            
            uint8_t p = finger.deleteModel(id);
            if (p == FINGERPRINT_OK) {
                Serial.print(F("✅ Deleted fingerprint ID #"));
                Serial.println(id);
                successCount++;
                deletedIds.push_back(id);
            } else {
                Serial.print(F("❌ Failed to delete fingerprint ID #"));
                Serial.println(id);
            }
        }
        
        // Update the user's fingerprint array if we have a userId
        if (!userId.isEmpty()) {
            updateUserFingerprintArray(userId, deletedIds);
            Serial.println(F("✅ Updated user's fingerprint array"));
        }
        
        // Log the event
        logDeletionEvent("multiple_fingerprints_deleted", userId, deleteCount, successCount);
        
        setLEDStatus(successCount == deleteCount ? STATUS_ONLINE : STATUS_ERROR);
    } 
    // Delete specific fingerprint ID (legacy single ID method)
    else {
        int specificId = fingerprintToDelete;
        
        Serial.print(F("⚠️ Executing command: Delete fingerprint ID #"));
        Serial.println(specificId);
        
        uint8_t p = finger.deleteModel(specificId);
        if (p == FINGERPRINT_OK) {
            Serial.print(F("✅ Deleted fingerprint ID #"));
            Serial.println(specificId);
            
            // Update the user's fingerprint array
            if (!userId.isEmpty()) {
                std::vector<int> deletedIds = {specificId};
                updateUserFingerprintArray(userId, deletedIds);
                Serial.println(F("✅ Updated user's fingerprint array"));
            }
            
            // Log the event
            logDeletionEvent("fingerprint_deleted", userId, 0, 0, specificId);
            
            setLEDStatus(STATUS_ONLINE);
        } else {
            Serial.print(F("❌ Failed to delete fingerprint ID #"));
            Serial.println(specificId);
            setLEDStatus(STATUS_ERROR);
            
            // Log the failure
            logDeletionEvent("fingerprint_delete_failed", userId, 0, 0, specificId);
        }
    }
    
    // Reset command flags
    deleteCommandPending = false;
    fingerprintToDelete = -1;
    fingerprints_to_delete.clear();
    currentEnrollmentUserId = "";
    
    // Invalidate the cache since we made changes
    firebaseCache.isValid = false;
}

void waitForFingerRemoval(unsigned long timeoutMillis) {
    Serial.println(F("Remove finger..."));
    unsigned long startTime = millis();

    while (finger.getImage() != FINGERPRINT_NOFINGER) {
        delay(50);

        // Timeout check
        if (millis() - startTime > timeoutMillis) {
            Serial.println(F("⚠️ Timeout: Finger removal not detected"));
            break;
        }
    }
}

int findNextAvailableId() {
    int availableId = -1;
    const int MAX_FINGERPRINT_COUNT = 127;  // adjust per your sensor

    for (int id = 1; id <= MAX_FINGERPRINT_COUNT; id++) {
        uint8_t p = finger.loadModel(id);

        if (p != FINGERPRINT_OK) {
            Serial.print("✅ Found available ID: ");
            Serial.println(id);
            return id;
        }
    }

    Serial.println("❌ No available fingerprint IDs found!");
    return -1;
}

// Main function to handle all fingerprint operations - call this from the main loop
void handleFingerprint() {
    // Check for pending commands in Firebase
    checkForCommands();
    
    // Process enrollment if in progress
    processEnrollment();
    
    // Process delete commands if pending
    processDeleteCommands();
    
    // Check for fingerprint authentication if not doing anything else
    if (!fingerprintEnrollmentInProgress && enrollmentState == ENROLL_IDLE && !deleteCommandPending) {
        if (authenticateUser()) {
            // Authentication successful, handle the unlock process
            // This could be a call to another function like handleSuccessfulAuth()
            sendCommandToNano("UNLOCK");
            // For now, just set the LED to success
            setLEDStatus(STATUS_UNLOCKED);
            delay(3000);
            setLEDStatus(STATUS_ONLINE);
        }
    }
}