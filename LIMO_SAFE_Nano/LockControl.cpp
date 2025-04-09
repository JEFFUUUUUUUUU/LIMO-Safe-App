#include "LockControl.h"

const int relayPin = 11;  // Relay connected to digital pin 11

void initializeLock() {
    pinMode(relayPin, OUTPUT);
    digitalWrite(relayPin, HIGH); 
    //lockSafe();  // Ensure safe is locked by default
}

void lockSafe() {
    digitalWrite(relayPin, HIGH);  // Activate relay to lock safe
    Serial.println("Safe is LOCKED.");
}

void unlockSafe() {
    digitalWrite(relayPin, LOW);  // Deactivate relay to unlock safe
    Serial.println("Safe is UNLOCKED.");
}