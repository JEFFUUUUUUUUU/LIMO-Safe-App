#ifndef REED_SENSOR_H
#define REED_SENSOR_H

#include <Arduino.h>

extern const int reedSwitchPin;  // Reed switch pin
void initializeReedSensor();
bool isSafeClosed(); // Returns true if the safe is closed, false otherwise.
bool getDebugReedSensorState();

#endif // REED_SENSOR_H
