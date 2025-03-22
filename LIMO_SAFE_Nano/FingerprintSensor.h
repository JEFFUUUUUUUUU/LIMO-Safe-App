#ifndef FINGERPRINT_SENSOR_H
#define FINGERPRINT_SENSOR_H

#include <Arduino.h>
#include <Adafruit_Fingerprint.h>

extern Adafruit_Fingerprint finger;

// Define the maximum number of fingerprints allowed
#define MAX_FINGERPRINTS 3  // Change this if needed

extern const int allowedFingerprints[MAX_FINGERPRINTS]; // Allowed fingerprint IDs

void initializeFingerprint();
bool authenticateUser();
bool enrollFingerprint(int id);
void deleteAllFingerprints();

#endif // FINGERPRINT_SENSOR_H
