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
        setLEDStatus(STATUS_SCANNING);
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
