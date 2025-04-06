#include "FingerprintSensor.h"
#include <SoftwareSerial.h>

// Define pins for fingerprint sensor (adjust if necessary)
#define FINGERPRINT_RX 8
#define FINGERPRINT_TX 9

SoftwareSerial fingerprintSerial(FINGERPRINT_RX, FINGERPRINT_TX);
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&fingerprintSerial);

unsigned long lastFingerprintCheck = 0;
const unsigned long FINGERPRINT_CHECK_INTERVAL = 1000; // 5 seconds between checks

void initializeFingerprint() {
    fingerprintSerial.begin(57600);
    
    if (finger.verifyPassword()) {
        Serial.println(F("✅ Fingerprint sensor initialized"));
    } else {
        Serial.println(F("❌ Fingerprint sensor not found!"));
    }
    
    // Set security level (1-5)
    finger.setSecurityLevel(3);
}

bool authenticateUser() {
    // Only check fingerprint every 5 seconds
    unsigned long currentTime = millis();
    if (currentTime - lastFingerprintCheck < FINGERPRINT_CHECK_INTERVAL) {
        return false; // Skip fingerprint check if not enough time has passed
    }
    
    // Time to check for fingerprint
    lastFingerprintCheck = currentTime;
    Serial.println(F("Waiting for fingerprint..."));
    
    // Get fingerprint image
    uint8_t p = finger.getImage();
    
    // No finger detected
    if (p == FINGERPRINT_NOFINGER) {
        return false;
    }
    
    // If we got an image but there was an error
    if (p != FINGERPRINT_OK) {
        Serial.print(F("Fingerprint error: "));
        Serial.println(p);
        return false;
    }
    
    // Convert image to features
    p = finger.image2Tz();
    if (p != FINGERPRINT_OK) {
        Serial.println(F("Image conversion error"));
        waitForFingerRemoval();
        return false;
    }
    
    // Search for a match
    p = finger.fingerSearch();
    if (p == FINGERPRINT_OK) {
        Serial.print(F("✅ Authorized fingerprint ID #"));
        Serial.println(finger.fingerID);
        waitForFingerRemoval();
        return true;
    } 
    
    Serial.println(F("❌ Fingerprint not recognized"));
    waitForFingerRemoval();
    return false;
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

void waitForFingerRemoval(unsigned long timeoutMillis = 5000) {
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