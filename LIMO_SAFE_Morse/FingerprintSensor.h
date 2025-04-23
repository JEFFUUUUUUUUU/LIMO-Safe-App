#ifndef FINGERPRINT_SENSOR_H
#define FINGERPRINT_SENSOR_H

#include <Arduino.h>
#include <Adafruit_Fingerprint.h>

enum FingerprintState {
    FP_IDLE,          // Waiting for finger
    FP_CONVERT_IMAGE, // Converting finger image
    FP_SEARCH,        // Searching for matching fingerprint
    FP_SUCCESS_DELAY  // Waiting after successful match
};

enum EnrollmentState {
    ENROLL_IDLE,
    ENROLL_WAITING_FIRST,
    ENROLL_CAPTURING_FIRST,
    ENROLL_WAITING_REMOVE,
    ENROLL_WAITING_SECOND,
    ENROLL_CAPTURING_SECOND,
    ENROLL_CREATE_MODEL,
    ENROLL_STORE_MODEL,
    ENROLL_COMPLETE,
    ENROLL_FAILED
};

void initializeFingerprint();
bool checkAndProcessFingerprint();
bool authenticateUser();
bool enrollFingerprint(int id);
void checkForEnrollmentRequests();
void processEnrollment();
void deleteAllFingerprints();
void checkForDeleteCommands();
void processDeleteCommands();
void waitForFingerRemoval(unsigned long timeoutMillis = 3000);
int findNextAvailableId();


extern unsigned long lastFingerprintCheck;
extern const unsigned long FINGERPRINT_CHECK_INTERVAL;
extern unsigned long lastFingerprintEnrollCheck;
extern const unsigned long FINGERPRINT_ENROLL_CHECK_INTERVAL;
extern unsigned long fpCommandSentTime;
extern unsigned long fpStateTimeout;

#endif