#include "FingerprintSensor.h"
#include "NanoCommunicator.h"
#include "WiFiSetup.h"
#include "RGBLed.h"

// Define pins for fingerprint sensor (adjust if necessary)

HardwareSerial FingerSerial(2);  // Use UART1
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&FingerSerial);

FingerprintState fingerprintState = FP_IDLE;

EnrollmentState enrollmentState = ENROLL_IDLE;
unsigned long enrollmentStateStartTime = 0;
int currentEnrollmentId = -1;
String currentEnrollmentUserId = "";
const unsigned long ENROLLMENT_STATE_TIMEOUT = 20000;

bool deleteFingerPrintCommandPending = false;
int fingerprintToDelete = -1;  // -1 means delete all, positive number is specific ID
std::vector<int> fingerprints_to_delete;

unsigned long lastCommandCheck = 0;
const unsigned long COMMAND_CHECK_INTERVAL = 10000; 

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

// Modified to focus only on sending the command when needed
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

// Replace the current enrollFingerprint with this state machine version
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

// Unified command checking and processing function
void manageFingerprintCommands() {
    // Process any ongoing enrollment first
    if (enrollmentState != ENROLL_IDLE) {
        processOngoingEnrollment();
        return; // Don't check for new commands until enrollment is complete
    }
    
    // Process any pending delete commands
    if (deleteFingerPrintCommandPending) {
        processDeleteCommands();
        return; // Don't check for new commands until deletion is complete
    }
    
    // Only check for new commands periodically
    unsigned long currentMillis = millis();
    if (currentMillis - lastCommandCheck < COMMAND_CHECK_INTERVAL) {
        return;
    }
    
    lastCommandCheck = currentMillis;
    
    // Make sure Firebase is ready
    if (!isFirebaseReady()) {
        return;
    }
    
    // Path to check for fingerprint mappings
    String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
    
    if (Firebase.RTDB.getJSON(&fbdo, mappingsPath.c_str())) {
        FirebaseJson* json = fbdo.jsonObjectPtr();
        if (json != nullptr) {
            // Iterate through all users in the mappings
            size_t iterCount = json->iteratorBegin();
            FirebaseJson::IteratorValue value;
            bool commandFound = false;
            
            for (size_t i = 0; i < iterCount && !commandFound; i++) {
                value = json->valueAt(i);
                String userId = value.key;
                String status = value.value;
                status.replace("\"", ""); // Remove quotes
                
                // Priority 1: Check for delete commands first
                if (status == "delete_all" || status.startsWith("delete_")) {
                    commandFound = true;
                    setupDeleteCommand(userId, status);
                    
                    // Clear the command after reading
                    String userPath = mappingsPath + "/" + userId;
                    Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
                }
                // Priority 2: Check for enrollment requests
                else if (status == "enroll") {
                    commandFound = true;
                    setupEnrollment(userId);
                }
            }
            
            json->iteratorEnd();
        }
    }
}

// Setup enrollment process (doesn't actually process it yet)
void setupEnrollment(String userId) {
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

        // Clear the command
        String userPath = String(DEVICE_PATH) + deviceId + "/fingerprint/" + userId;
        Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
        return;
    }
    
    // Start enrollment process
    currentEnrollmentId = availableId;
    currentEnrollmentUserId = userId;
    enrollmentState = ENROLL_WAITING_FIRST;
    enrollmentStateStartTime = millis();
    fingerprintEnrollmentInProgress = true;
    setLEDStatus(STATUS_SCANNING);
    
    Serial.println(F("Ready to enroll a fingerprint!"));
    Serial.print(F("Please place finger on sensor for ID #")); 
    Serial.println(currentEnrollmentId);
}

// Process ongoing enrollment
void processOngoingEnrollment() {
    // State machine implementation of the enrollment process
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
            break;
            
        // [All other enrollment states from your existing code]
        // ... (state machine continues with all the enrollment states)
            
        case ENROLL_COMPLETE:
            // Enrollment successful, complete the process
            finalizeEnrollment(true);
            break;
            
        case ENROLL_FAILED:
            // Enrollment failed, clean up
            finalizeEnrollment(false);
            break;
            
        default:
            // Reset to idle if we're in an unknown state
            enrollmentState = ENROLL_IDLE;
            fingerprintEnrollmentInProgress = false;
            setLEDStatus(STATUS_ONLINE);
            break;
    }
    
    // Check for timeout in any active state
    if (enrollmentState != ENROLL_IDLE && 
        enrollmentState != ENROLL_COMPLETE &&
        enrollmentState != ENROLL_FAILED) {
        
        if (millis() - enrollmentStateStartTime > ENROLLMENT_STATE_TIMEOUT) {
            Serial.println(F("❌ Enrollment timeout"));
            enrollmentState = ENROLL_FAILED;
            setLEDStatus(STATUS_ERROR);
        }
    }
}

// Finalize the enrollment process (success or failure)
void finalizeEnrollment(bool success) {
    // Path to check for fingerprint mappings
    fingerprintEnrollmentInProgress = false;
    setLEDStatus(success ? STATUS_REGISTERED : STATUS_ERROR);
    
    if (!currentEnrollmentUserId.isEmpty()) {
        String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
        
        if (success) {
            // Update the user's status to "registered"
            String userPath = mappingsPath + "/" + currentEnrollmentUserId;
            Firebase.RTDB.setString(&fbdo, userPath.c_str(), "registered");
            
            // Store the fingerprint ID to user mapping for later reference
            String idMappingPath = mappingsPath + "/ids/" + String(currentEnrollmentId);
            Firebase.RTDB.setString(&fbdo, idMappingPath.c_str(), currentEnrollmentUserId);
            
            // Add fingerprint ID to user's registeredDevices structure
            String userDevicesPath = String(USERS_PATH) + currentEnrollmentUserId + "/registeredDevices/" + deviceId + "/fingerprint";
            
            // First, check if the user already has fingerprints registered for this device
            FirebaseJsonArray fingerprintArray;
            
            if (Firebase.RTDB.getArray(&fbdo, userDevicesPath.c_str())) {
                // User already has fingerprint array for this device
                fingerprintArray = fbdo.jsonArray();
            }
            
            // Add the new fingerprint ID to the array
            fingerprintArray.add(currentEnrollmentId);
            
            // Save the updated array
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
            
            String logPath = String("devices/") + deviceId + "/logs";
            Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            
            delay(2000); // Brief pause to show success LED
        } else {
            // Log failure
            FirebaseJson logEntry;
            logEntry.set("timestamp", isTimeSynchronized());
            logEntry.set("event", "fingerprint_enrollment_failed");
            logEntry.set("userId", currentEnrollmentUserId);
            logEntry.set("reason", "enrollment_error");
            
            String logPath = String("devices/") + deviceId + "/logs";
            Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            
            String userPath = mappingsPath + "/" + currentEnrollmentUserId;
            Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
            
            delay(2000); // Brief pause to show error LED
        }
    }
    
    // Reset enrollment state variables
    currentEnrollmentUserId = "";
    currentEnrollmentId = -1;
    enrollmentState = ENROLL_IDLE;
    setLEDStatus(STATUS_ONLINE);
}

// Setup delete command parameters
void setupDeleteCommand(String userId, String status) {
    Serial.print("📱 Delete command received for user: ");
    Serial.println(userId);
    
    deleteFingerPrintCommandPending = true;
    currentEnrollmentUserId = userId;
    
    if (status == "delete_all") {
        Serial.println("Command: Delete all fingerprints");
        fingerprintToDelete = -1;
        fingerprints_to_delete.clear(); // Clear any previously stored ID array
    }
    else if (status.startsWith("delete_")) {
        // Extract IDs from the command (format: "delete_1" or "delete_1,2,3")
        String idsToDelete = status.substring(7); 
        
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
        
        // For backward compatibility with original code
        if (fingerprints_to_delete.size() == 1) {
            fingerprintToDelete = fingerprints_to_delete[0];
        } else if (fingerprints_to_delete.size() > 1) {
            fingerprintToDelete = -2; // Indicates multiple IDs
        } else {
            // No valid IDs found
            Serial.println("❌ No valid fingerprint IDs found in delete command");
            deleteFingerPrintCommandPending = false;
        }
    }
}

// Function to execute pending delete commands
void processDeleteCommands() {
    if (!deleteFingerPrintCommandPending) {
        return;
    }
    
    // Do not process delete commands if enrollment is in progress
    if (enrollmentState != ENROLL_IDLE) {
        return;
    }
    
    if (fingerprintToDelete == -1) {
        // Delete all fingerprints for a specific user
        String userId = currentEnrollmentUserId;
        
        if (userId.isEmpty()) {
            Serial.println(F("❌ No user ID specified for fingerprint deletion"));
            deleteFingerPrintCommandPending = false;
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
                if (p != FINGERPRINT_OK) {
                    Serial.print(F("❌ Failed to delete fingerprint ID #"));
                    Serial.println(fpId);
                    allSuccess = false;
                } else {
                    Serial.print(F("✅ Deleted fingerprint ID #"));
                    Serial.println(fpId);
                }
            }
            
            // If all deletions were successful or we want to clear regardless
            // Remove the fingerprint array from the user's registered devices
            Firebase.RTDB.deleteNode(&fbdo, userFingerprintPath.c_str());
            
            // Log the event
            if (isFirebaseReady()) {
                FirebaseJson logEntry;
                logEntry.set("timestamp", isTimeSynchronized());
                logEntry.set("event", allSuccess ? "user_fingerprints_deleted" : "user_fingerprints_delete_partial");
                logEntry.set("userId", userId);
                logEntry.set("count", arraySize);
                
                String logPath = String("devices/") + deviceId + "/logs";
                Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            }
            
            setLEDStatus(allSuccess ? STATUS_ONLINE : STATUS_ERROR);
        } else {
            Serial.println(F("⚠️ No fingerprints found for this user"));
            
            // Log that no fingerprints were found
            if (isFirebaseReady()) {
                FirebaseJson logEntry;
                logEntry.set("timestamp", isTimeSynchronized());
                logEntry.set("event", "fingerprint_delete_no_fingerprints");
                logEntry.set("userId", userId);
                
                String logPath = String("devices/") + deviceId + "/logs";
                Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            }
        }
    } 
    else if (fingerprintToDelete == -2 || !fingerprints_to_delete.empty()) {
        // Delete specific fingerprint IDs (multiple)
        String userId = currentEnrollmentUserId;
        int deleteCount = fingerprints_to_delete.size();
        int successCount = 0;
        
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
            } else {
                Serial.print(F("❌ Failed to delete fingerprint ID #"));
                Serial.println(id);
            }
        }
        
        // Update the user's fingerprint array if we have a userId
        if (!userId.isEmpty()) {
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
                    for (int deleteId : fingerprints_to_delete) {
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
                
                Serial.println(F("✅ Updated user's fingerprint array"));
            }
        }
        
        // Log the event
        if (isFirebaseReady()) {
            FirebaseJson logEntry;
            logEntry.set("timestamp", isTimeSynchronized());
            logEntry.set("event", "multiple_fingerprints_deleted");
            logEntry.set("total", deleteCount);
            logEntry.set("success", successCount);
            
            if (!userId.isEmpty()) {
                logEntry.set("userId", userId);
            }
            
            String logPath = String("devices/") + deviceId + "/logs";
            Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
        }
        
        setLEDStatus(successCount == deleteCount ? STATUS_ONLINE : STATUS_ERROR);
    } 
    else {
        // Delete specific fingerprint ID (legacy single ID method)
        int specificId = fingerprintToDelete;
        String userId = currentEnrollmentUserId;
        
        Serial.print(F("⚠️ Executing command: Delete fingerprint ID #"));
        Serial.println(specificId);
        
        uint8_t p = finger.deleteModel(specificId);
        if (p == FINGERPRINT_OK) {
            Serial.print(F("✅ Deleted fingerprint ID #"));
            Serial.println(specificId);
            
            // Update the user's fingerprint array
            if (!userId.isEmpty()) {
                String userFingerprintPath = String(USERS_PATH) + userId + "/registeredDevices/" + deviceId + "/fingerprint";
                
                if (Firebase.RTDB.getArray(&fbdo, userFingerprintPath.c_str())) {
                    FirebaseJsonArray fingerprintArray = fbdo.jsonArray();
                    FirebaseJsonArray newArray;
                    size_t arraySize = fingerprintArray.size();
                    
                    // Create a new array excluding the deleted fingerprint ID
                    for (size_t i = 0; i < arraySize; i++) {
                        FirebaseJsonData result;
                        fingerprintArray.get(result, i);
                        int currentId = result.intValue;
                        
                        if (currentId != specificId) {
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
                    
                    Serial.println(F("✅ Updated user's fingerprint array"));
                }
            }
            
            // Log the event
            if (isFirebaseReady()) {
                FirebaseJson logEntry;
                logEntry.set("timestamp", isTimeSynchronized());
                logEntry.set("event", "fingerprint_deleted");
                logEntry.set("fingerprintId", specificId);
                
                if (!userId.isEmpty()) {
                    logEntry.set("userId", userId);
                }
                
                String logPath = String("devices/") + deviceId + "/logs";
                Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            }
            
            setLEDStatus(STATUS_ONLINE);
        } else {
            Serial.print(F("❌ Failed to delete fingerprint ID #"));
            Serial.println(specificId);
            setLEDStatus(STATUS_ERROR);
            
            // Log the failure
            if (isFirebaseReady()) {
                FirebaseJson logEntry;
                logEntry.set("timestamp", isTimeSynchronized());
                logEntry.set("event", "fingerprint_delete_failed");
                logEntry.set("fingerprintId", specificId);
                
                if (!userId.isEmpty()) {
                    logEntry.set("userId", userId);
                }
                
                String logPath = String("devices/") + deviceId + "/logs";
                Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            }
        }
    }
    
    // Reset command flags
    deleteFingerPrintCommandPending = false;
    fingerprintToDelete = -1;
    fingerprints_to_delete.clear();
    currentEnrollmentUserId = "";
}

void deleteAllFingerprints() {
    Serial.println(F("⚠️ Deleting all fingerprints..."));
    
    if (finger.emptyDatabase() == FINGERPRINT_OK) {
        Serial.println(F("✅ All fingerprints deleted!"));
    } else {
        Serial.println(F("❌ Failed to delete fingerprints"));
    }
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

// Add this function to FingerprintSensor.cpp
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