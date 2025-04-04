#include "FingerprintSensor.h"
#include <SoftwareSerial.h>

// Single static instance for the hardware
static SoftwareSerial fingerSerial(8, 9); // RX, TX
static Adafruit_Fingerprint finger(&fingerSerial);

// Constants
#define ALLOWED_FINGERPRINT_ID 1
#define MAX_REMOVAL_ATTEMPTS 12
#define LED_DISABLE_ATTEMPTS 3
#define NEXT_SCAN_DELAY 5000  // 5 seconds delay between scans

void initializeFingerprint() {
    finger.begin(57600);
    if (finger.verifyPassword()) {
        Serial.println(F("Fingerprint sensor found."));
        
        // Disable LED with fewer attempts
        for (uint8_t i = 0; i < LED_DISABLE_ATTEMPTS; i++) {
            finger.LEDcontrol(false);
            delay(20); // Reduced delay
        }
    } else {
        Serial.println(F("Fingerprint sensor NOT detected!"));
    }
}

// Authenticate only if fingerprint ID is allowed
bool authenticateUser() {
    Serial.println(F("Waiting for fingerprint..."));

    uint8_t p = finger.getImage();
    if (p != FINGERPRINT_OK) return false;
    
    p = finger.image2Tz();
    if (p != FINGERPRINT_OK) return false;
    
    p = finger.fingerFastSearch();
    if (p != FINGERPRINT_OK) {
        waitForFingerRemoval();
        delay(NEXT_SCAN_DELAY);  // 5 second delay before next scan
        return false;
    }

    // Check if the fingerprint is authorized
    if (finger.fingerID == ALLOWED_FINGERPRINT_ID) {
        // Disable LED and wait for finger removal
        finger.LEDcontrol(false);
        waitForFingerRemoval();
        delay(NEXT_SCAN_DELAY);  // 5 second delay before next scan
        return true;
    }
    delay(5000);
    return false;
}

// Simplified fingerprint enrollment
bool enrollFingerprint(int id) {
    if (id != ALLOWED_FINGERPRINT_ID) {
        Serial.println(F("Only ID 1 allowed!"));
        return false;
    }
    
    finger.LEDcontrol(false);
    Serial.println(F("Place finger on sensor..."));
    
    uint8_t p = 0;
    while (p != FINGERPRINT_OK) {
        p = finger.getImage();
        // Only process meaningful states
        if (p == FINGERPRINT_OK) {
            Serial.println(F("Image taken"));
        } else if (p != FINGERPRINT_NOFINGER) {
            Serial.println(F("Error"));
        }
    }

    // Process fingerprint
    if (finger.image2Tz(1) != FINGERPRINT_OK) {
        return false;
    }
    
    finger.image2Tz(2);  // Create template in slot 2 (duplicating first one)
    
    if (finger.createModel() != FINGERPRINT_OK) {
        return false;
    }

    if (finger.storeModel(id) == FINGERPRINT_OK) {
        Serial.println(F("Fingerprint stored!"));
        finger.LEDcontrol(false);
        waitForFingerRemoval();
        delay(NEXT_SCAN_DELAY);  // 5 second delay before next scan
        return true;
    }
    
    return false;
}

// Delete fingerprint
void deleteAllFingerprints() {
    finger.deleteModel(ALLOWED_FINGERPRINT_ID);
}

// Wait for finger removal with timeout
void waitForFingerRemoval() {
    Serial.println(F("Remove finger..."));
    
    // Short delay for stability
    delay(300);
    
    for (uint8_t attempts = 0; attempts < MAX_REMOVAL_ATTEMPTS; attempts++) {
        if (finger.getImage() == FINGERPRINT_NOFINGER) {
            Serial.println(F("Finger removed"));
            delay(500);  // Small delay after finger removed
            return;
        }
        delay(200);
    }
    
    // Timeout occurred, disable LED before continuing
    Serial.println(F("Timeout - please remove finger"));
    finger.LEDcontrol(false);
    delay(NEXT_SCAN_DELAY);  // 5 second delay after timeout
}