#ifndef REED_SENSOR_H
#define REED_SENSOR_H

#include <Arduino.h>

// Debounce configuration
#define REED_DEBOUNCE_DELAY 50  // Debounce time in milliseconds

extern const int reedSwitchPin;  // Reed switch pin
void initializeReedSensor();
bool isSafeClosed(); // Returns true if the safe is closed, false otherwise.
bool getDebugReedSensorState();
void resetReedSensorState(); // Reset the debounce state if needed

#endif // REED_SENSOR_H
