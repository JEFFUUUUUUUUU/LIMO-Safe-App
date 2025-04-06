#ifndef FINGERPRINT_SENSOR_H
#define FINGERPRINT_SENSOR_H

#include <Arduino.h>
#include <Adafruit_Fingerprint.h>

void initializeFingerprint();
bool authenticateUser();
bool enrollFingerprint(int id);
void deleteAllFingerprints();
void waitForFingerRemoval(unsigned long timeoutMillis = 5000);

#endif