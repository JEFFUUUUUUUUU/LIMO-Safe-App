#include "FingerprintSensor.h"
#include "NanoCommunicator.h"

// Define pins for fingerprint sensor (adjust if necessary)

HardwareSerial FingerSerial(1);  // Use UART1
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&FingerSerial);

// State machine for fingerprint authentication
enum FingerprintState {
    FP_IDLE,
    FP_GET_IMAGE,
    FP_CONVERT_IMAGE,
    FP_SEARCH
};

FingerprintState fingerprintState = FP_IDLE;
unsigned long lastFingerprintCheck = 0;
const unsigned long FINGERPRINT_CHECK_INTERVAL = 10000; // 1 second between checks

void initializeFingerprint() {
    FingerSerial.begin(57600, SERIAL_8N1, 32, 33);
    
    if (finger.verifyPassword()) {
        Serial.println(F("✅ Fingerprint sensor initialized"));
    } else {
        Serial.println(F("❌ Fingerprint sensor not found!"));
    }
    
    // Set security level (1-5)
    finger.setSecurityLevel(1);
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

bool enrollFingerprint(int id) {
    Serial.println(F("Ready to enroll a fingerprint!"));
    Serial.print(F("Please place finger on sensor for ID #")); 
    Serial.println(id);
    
    while (finger.getImage() != FINGERPRINT_OK) {
        Serial.println(F("Place finger on sensor..."));
        delay(1000);
    }
    
    // First image conversion
    uint8_t p = finger.image2Tz(1);
    if (p != FINGERPRINT_OK) {
        Serial.println(F("Image conversion failed"));
        return false;
    }
    
    Serial.println(F("Remove finger"));
    waitForFingerRemoval();
    
    Serial.println(F("Place same finger again"));
    while (finger.getImage() != FINGERPRINT_OK) {
        delay(50);
    }
    
    // Second image conversion
    p = finger.image2Tz(2);
    if (p != FINGERPRINT_OK) {
        Serial.println(F("Image conversion failed"));
        return false;
    }
    
    // Create model
    p = finger.createModel();
    if (p != FINGERPRINT_OK) {
        Serial.println(F("Failed to create model"));
        return false;
    }
    
    // Store model
    p = finger.storeModel(id);
    if (p == FINGERPRINT_OK) {
        Serial.println(F("✅ Fingerprint enrolled successfully!"));
        return true;
    } else {
        Serial.println(F("❌ Error storing fingerprint"));
        return false;
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