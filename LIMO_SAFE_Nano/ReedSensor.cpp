#include "ReedSensor.h"

const int reedSwitchPin = 12;

void initializeReedSensor() {
    // Change to INPUT mode since KY-021 has its own pull-up resistor
    pinMode(reedSwitchPin, INPUT);
}

bool isSafeClosed() {
    // For KY-021, LOW means magnet is present (switch is closed)
    // HIGH means magnet is absent (switch is open)
    return digitalRead(reedSwitchPin) == LOW;
}

bool getDebugReedSensorState() {
    return digitalRead(reedSwitchPin);
}