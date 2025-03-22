#include "LockControl.h"

const int relayPin = 5;  // Relay connected to digital pin 5

void initializeLock() {
    pinMode(relayPin, OUTPUT);
    lockSafe();  // Ensure safe is locked by default
}

void lockSafe() {
    digitalWrite(relayPin, HIGH);  // Activate relay to lock safe
    Serial.println("Safe is LOCKED.");
}

void unlockSafe() {
    digitalWrite(relayPin, LOW);  // Deactivate relay to unlock safe
    Serial.println("Safe is UNLOCKED.");
}
