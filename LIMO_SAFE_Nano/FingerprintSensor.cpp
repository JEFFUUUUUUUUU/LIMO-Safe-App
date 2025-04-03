#include "FingerprintSensor.h"
#include <SoftwareSerial.h>

SoftwareSerial fingerSerial(8, 9); // RX, TX for fingerprint module
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&fingerSerial);

// Define specific allowed fingerprint IDs
const int allowedFingerprints[MAX_FINGERPRINTS] = {1};

void initializeFingerprint() {
    finger.begin(57600);
    if (finger.verifyPassword()) {
        Serial.println("Fingerprint sensor found.");
        
        // Try multiple times to disable the built-in LED
        for (int i = 0; i < 5; i++) {
            finger.LEDcontrol(false);
            delay(50);
        }
        Serial.println("Sensor LED disabled (attempted multiple times)");
        
    } else {
        Serial.println("Fingerprint sensor NOT detected!");
    }
    
    finger.getParameters();
    Serial.print("Capacity: "); Serial.print(finger.capacity); Serial.println(" fingerprints");
}

// Disable sensor's built-in LED with multiple attempts
void disableSensorLED() {
    // Call LEDcontrol multiple times to ensure it takes effect
    for (int i = 0; i < 3; i++) {
        finger.LEDcontrol(false);
        delay(20);
    }
}

// Authenticate only if the fingerprint ID is in the allowed list
bool authenticateUser() {
    Serial.println("Waiting for fingerprint...");

    uint8_t p = finger.getImage();
    if (p != FINGERPRINT_OK) return false;
    
    p = finger.image2Tz();
    if (p != FINGERPRINT_OK) return false;
    
    p = finger.fingerFastSearch();
    if (p != FINGERPRINT_OK) {
        if (p == FINGERPRINT_NOTFOUND) {
            Serial.println("Did not find a match");
        }
        waitForFingerRemoval();
        return false;
    }

    int userID = finger.fingerID;

    // Check if the fingerprint ID is allowed
    for (int i = 0; i < MAX_FINGERPRINTS; i++) {
        if (userID == allowedFingerprints[i]) {
            Serial.print("Authenticated! User ID: ");
            Serial.println(userID);
            
            // Disable sensor LED again
            disableSensorLED();
            
            // Wait for finger removal
            waitForFingerRemoval();
            
            return true;
        }
    }

    Serial.println("Unauthorized fingerprint detected!");
    return false;
}

// Enroll a fingerprint if under the limit
bool enrollFingerprint(int id) {
    if (id != 1) {  // Only allow fingerprint ID 1
        Serial.println("❌ Only fingerprint ID 1 is allowed!");
        return false;
    }
    Serial.print("Enrolling fingerprint ID ");
    Serial.println(id);

    // Make sure LED is off before starting enrollment
    disableSensorLED();

    // Wait for a valid finger
    Serial.println("Place finger on sensor...");
    uint8_t p = -1;
    while (p != FINGERPRINT_OK) {
        p = finger.getImage();
        switch (p) {
            case FINGERPRINT_OK:
                Serial.println("Image taken");
                break;
            case FINGERPRINT_NOFINGER:
                // Silent when no finger detected to avoid spamming
                break;
            case FINGERPRINT_PACKETRECIEVEERR:
                Serial.println("Communication error");
                break;
            case FINGERPRINT_IMAGEFAIL:
                Serial.println("Imaging error");
                break;
            default:
                Serial.println("Unknown error");
                break;
        }
    }

    // Convert image to template
    p = finger.image2Tz(1);
    if (p != FINGERPRINT_OK) {
        Serial.println("❌ Error processing fingerprint.");
        return false;
    }
    
    // Since we're only taking one scan, we'll duplicate the template
    Serial.println("Creating fingerprint model...");
    
    // We're skipping the second scan and using the first template for both slots
    finger.image2Tz(2);  // Create template in slot 2 (duplicating the first one)
    
    p = finger.createModel();
    if (p != FINGERPRINT_OK) {
        Serial.println("❌ Error creating fingerprint model.");
        return false;
    }

    // Store the model
    if (finger.storeModel(id) == FINGERPRINT_OK) {
        Serial.println("✅ Fingerprint stored successfully!");
        
        // Make sure LED is disabled after enrollment
        disableSensorLED();
        
        waitForFingerRemoval();
        return true;
    } else {
        Serial.println("❌ Error storing fingerprint.");
        return false;
    }
}

// Delete all fingerprints
void deleteAllFingerprints() {
    Serial.println("Deleting fingerprint ID 1...");
    finger.deleteModel(1);
    Serial.println("Fingerprint ID 1 deleted.");
}

// Wait for finger to be removed from the sensor
void waitForFingerRemoval() {
    Serial.println("Waiting for finger to be removed...");
    
    // First, give a short delay for stability
    delay(500);
    
    // Set a maximum number of attempts to prevent infinite loop
    int maxAttempts = 15;
    int attempts = 0;
    
    while (attempts < maxAttempts) {
        uint8_t p = finger.getImage();
        
        // If no finger detected, we're done
        if (p == FINGERPRINT_NOFINGER) {
            Serial.println("Finger removed, ready for next scan");
            delay(500); // Short delay before next scan
            return;
        }
        
        // Small delay between checks
        delay(300);
        attempts++;
        
        // If taking too long, force exit
        if (attempts >= maxAttempts - 3) {
            Serial.println("Timeout waiting for finger removal");
            Serial.println("Forcing continuation...");
            delay(1000);
            return;
        }
    }
    
    // Disable sensor LED again after finger removal
    disableSensorLED();
}