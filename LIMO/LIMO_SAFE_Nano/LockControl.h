#ifndef LOCK_CONTROL_H
#define LOCK_CONTROL_H

#include <Arduino.h>

extern const int relayPin;  // Relay control pin
void initializeLock();
void lockSafe();
void unlockSafe();

#endif // LOCK_CONTROL_H
