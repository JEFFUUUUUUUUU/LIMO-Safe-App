#include "FingerprintSensor.h"
#include <SoftwareSerial.h>

SoftwareSerial fingerSerial(6, 7); // RX, TX for fingerprint module
Adafruit_Fingerprint finger = Adafruit_Fingerprint(&fingerSerial);

// Define specific allowed fingerprint IDs
const int allowedFingerprints[MAX_FINGERPRINTS] = {1, 2, 3}; // Allow IDs 1, 2, and 3

void initializeFingerprint() {
    finger.begin(57600);
    if (finger.verifyPassword()) {
        Serial.println("Fingerprint sensor found.");
    } else {
        Serial.println("Fingerprint sensor NOT detected!");
    }
}

// Authenticate only if the fingerprint ID is in the allowed list
bool authenticateUser() {
    Serial.println("Waiting for fingerprint...");

    if (finger.getImage() != FINGERPRINT_OK) return false;
    if (finger.image2Tz() != FINGERPRINT_OK) return false;
    if (finger.fingerFastSearch() != FINGERPRINT_OK) return false;

    int userID = finger.fingerID;

    // Check if the fingerprint ID is allowed
    for (int i = 0; i < MAX_FINGERPRINTS; i++) {
        if (userID == allowedFingerprints[i]) {
            Serial.print("Authenticated! User ID: ");
            Serial.println(userID);
            return true;
        }
    }

    Serial.println("Unauthorized fingerprint detected!");
    return false;
}

// Enroll a fingerprint if under the limit
bool enrollFingerprint(int id) {
    if (id < 1 || id > MAX_FINGERPRINTS) {
        Serial.println("Invalid fingerprint ID! Must be between 1 and " + String(MAX_FINGERPRINTS));
        return false;
    }

    Serial.print("Enrolling fingerprint ID ");
    Serial.println(id);

    // Wait for a valid fingerprint
    Serial.println("Place finger on sensor...");
    while (finger.getImage() != FINGERPRINT_OK);

    if (finger.image2Tz() != FINGERPRINT_OK) return false;
    if (finger.createModel() != FINGERPRINT_OK) return false;

    if (finger.storeModel(id) == FINGERPRINT_OK) {
        Serial.println("Fingerprint stored successfully!");
        return true;
    } else {
        Serial.println("Error storing fingerprint.");
        return false;
    }
}

// Delete all fingerprints
void deleteAllFingerprints() {
    Serial.println("Deleting all fingerprints...");
    for (int i = 1; i <= MAX_FINGERPRINTS; i++) {
        finger.deleteModel(i);
    }
    Serial.println("All fingerprints deleted.");
}
