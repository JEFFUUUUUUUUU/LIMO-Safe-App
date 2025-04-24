#include "FingerprintSensor.h"
#include "NanoCommunicator.h"
#include "WiFiSetup.h"
#include "RGBLed.h"

// Define pins for fingerprint sensor (adjust if necessary)

HardwareSerial FingerSerial(2);  // Use UART1
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&FingerSerial);

FingerprintState fingerprintState = FP_IDLE;
unsigned long lastFingerprintCheck = 0;
const unsigned long FINGERPRINT_CHECK_INTERVAL = 10000; // 1 second between checks
unsigned long lastFingerprintEnrollCheck = 0;
const unsigned long FINGERPRINT_ENROLL_CHECK_INTERVAL = 30000;

EnrollmentState enrollmentState = ENROLL_IDLE;
unsigned long enrollmentStateStartTime = 0;
int currentEnrollmentId = -1;
String currentEnrollmentUserId = "";
const unsigned long ENROLLMENT_STATE_TIMEOUT = 20000;

bool deleteFingerPrintCommandPending = false;
int fingerprintToDelete = -1;  // -1 means delete all, positive number is specific ID
unsigned long lastDeleteCommandCheck = 0;
const unsigned long DELETE_COMMAND_CHECK_INTERVAL = 10000;  // Check every 10 seconds

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

// Now modify the checkForEnrollmentRequests function to work with the state machine
void checkForEnrollmentRequests() {
    // If already in enrollment process, don't check for new requests
    if (enrollmentState != ENROLL_IDLE) {
        return;
    }
    
    // Only check periodically to avoid excessive Firebase queries
    if (millis() - lastFingerprintEnrollCheck < FINGERPRINT_ENROLL_CHECK_INTERVAL) {
        return;
    }
    
    lastFingerprintEnrollCheck = millis();
    
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
            
            for (size_t i = 0; i < iterCount; i++) {
                value = json->valueAt(i);
                String userId = value.key;
                String status = value.value;
                
                // Check if this user has a pending enrollment
                if (status == "\"enroll\"") {
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

                        String userPath = mappingsPath + "/" + userId;
                        Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
                        continue;
                    }
                    
                    // Start enrollment process by setting the enrollment ID and user ID
                    currentEnrollmentId = availableId;
                    currentEnrollmentUserId = userId;
                    enrollFingerprint(currentEnrollmentId);
                    
                    // Only process one enrollment at a time
                    break;
                }
            }
            
            json->iteratorEnd();
        }
    }
}

// Add this function to be called from the main loop
void processEnrollment() {
    // If enrollment is in progress
    if (enrollmentState != ENROLL_IDLE) {
        // Process the enrollment state machine
        bool enrollmentResult = enrollFingerprint(currentEnrollmentId);
        fingerprintEnrollmentInProgress = true;
        setLEDStatus(STATUS_SCANNING);
        
        // If enrollment completed (success or failure)
        if (enrollmentState == ENROLL_IDLE && !currentEnrollmentUserId.isEmpty()) {
            // Path to check for fingerprint mappings
            fingerprintEnrollmentInProgress = false;
            setLEDStatus(STATUS_ONLINE);
            String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
            
            if (enrollmentResult) {
                // Update the user's status to "registered"
                String userPath = mappingsPath + "/" + currentEnrollmentUserId;
                Firebase.RTDB.setString(&fbdo, userPath.c_str(), "registered");
                
                // Store the fingerprint ID to user mapping for later reference
                String idMappingPath = String(DEVICE_PATH) + deviceId + "/fingerprint/ids/" + String(currentEnrollmentId);
                Firebase.RTDB.setString(&fbdo, idMappingPath.c_str(), currentEnrollmentUserId);
                
                // Add fingerprint ID to user's registeredDevices structure
                String userDevicesPath = String(USERS_PATH) + currentEnrollmentUserId + "/registeredDevices/" + deviceId + "/fingerprint";
                
                // First, check if the user already has fingerprints registered for this device
                FirebaseJsonArray fingerprintArray;
                bool hasExistingFingerprints = false;
                
                if (Firebase.RTDB.getArray(&fbdo, userDevicesPath.c_str())) {
                    // User already has fingerprint array for this device
                    fingerprintArray = fbdo.jsonArray();
                    hasExistingFingerprints = true;
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
            }
            
            // Reset enrollment state variables
            currentEnrollmentUserId = "";
            currentEnrollmentId = -1;
            setLEDStatus(STATUS_ONLINE);
        }
    }
}

void deleteAllFingerprints() {
    Serial.println(F("⚠️ Deleting all fingerprints..."));
    
    if (finger.emptyDatabase() == FINGERPRINT_OK) {
        Serial.println(F("✅ All fingerprints deleted!"));
    } else {
        Serial.println(F("❌ Failed to delete fingerprints"));
    }
}

// Function to check for delete commands from Firebase
void checkForDeleteCommands() {
    // Only check periodically to avoid excessive Firebase queries
    if (millis() - lastDeleteCommandCheck < DELETE_COMMAND_CHECK_INTERVAL) {
        return;
    }
    
    lastDeleteCommandCheck = millis();
    
    // Make sure Firebase is ready
    if (!isFirebaseReady()) {
        return;
    }
    
    // Path to check for fingerprint mappings (commands are here)
    String mappingsPath = String(DEVICE_PATH) + deviceId + "/fingerprint";
    
    if (Firebase.RTDB.getJSON(&fbdo, mappingsPath.c_str())) {
        FirebaseJson* json = fbdo.jsonObjectPtr();
        if (json != nullptr) {
            // Iterate through all users in the mappings
            size_t iterCount = json->iteratorBegin();
            FirebaseJson::IteratorValue value;
            
            for (size_t i = 0; i < iterCount; i++) {
                value = json->valueAt(i);
                String userId = value.key;
                String status = value.value;
                
                // Check if this user has a delete command
                if (status == "\"delete_all\"") {
                    Serial.print("📱 Command received: Delete all fingerprints for user: ");
                    Serial.println(userId);
                    deleteFingerPrintCommandPending = true;
                    fingerprintToDelete = -1;
                    currentEnrollmentUserId = userId; // Store the userId for processing
                    
                    // Clear the command after reading
                    String userPath = mappingsPath + "/" + userId;
                    Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
                    break; // Process one command at a time
                }
                else if (status == "\"delete_id\"") {
                    Serial.print("📱 Command received: Delete fingerprint ID for user: ");
                    Serial.println(userId);
                    
                    // We need to find the fingerprint ID for this user
                    String userFingerprintPath = String(USERS_PATH) + userId + "/registeredDevices/" + deviceId + "/fingerprint";
                    
                    if (Firebase.RTDB.getArray(&fbdo, userFingerprintPath.c_str())) {
                        FirebaseJsonArray fingerprintArray = fbdo.jsonArray();
                        
                        // For simplicity, let's delete the first fingerprint ID found
                        // A more sophisticated approach could specify which ID to delete
                        if (fingerprintArray.size() > 0) {
                            FirebaseJsonData result;
                            fingerprintArray.get(result, 0);
                            int idToDelete = result.intValue;
                            
                            deleteFingerPrintCommandPending = true;
                            fingerprintToDelete = idToDelete;
                            currentEnrollmentUserId = userId;
                            
                            Serial.print("Found fingerprint ID to delete: ");
                            Serial.println(idToDelete);
                        } else {
                            Serial.println("❌ No fingerprint IDs found for this user");
                        }
                    } else {
                        Serial.println("❌ Could not access user's fingerprint array");
                    }
                    
                    // Clear the command after reading
                    String userPath = mappingsPath + "/" + userId;
                    Firebase.RTDB.deleteNode(&fbdo, userPath.c_str());
                    break; // Process one command at a time
                }
            }
            
            json->iteratorEnd();
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
    } else {
        // Delete specific fingerprint ID
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
    currentEnrollmentUserId = "";
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
