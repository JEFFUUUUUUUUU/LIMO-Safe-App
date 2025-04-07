#ifndef FINGERPRINT_SENSOR_H
#define FINGERPRINT_SENSOR_H

#include <Arduino.h>
#include <Adafruit_Fingerprint.h>

void initializeFingerprint();
bool authenticateUser();
bool enrollFingerprint(int id);
void deleteAllFingerprints();
void waitForFingerRemoval(unsigned long timeoutMillis = 3000);

extern unsigned long lastFingerprintCheck;
extern const unsigned long FINGERPRINT_CHECK_INTERVAL;

#endif