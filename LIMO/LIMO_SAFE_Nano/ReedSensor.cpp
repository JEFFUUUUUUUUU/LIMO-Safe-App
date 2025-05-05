#include "ReedSensor.h"

const int reedSwitchPin = 10;

// Debouncing variables
static bool lastReading = false;
static bool stableState = false;
static unsigned long lastDebounceTime = 0;

void initializeReedSensor() {
  // Enable internal pull-up resistor on the reed switch pin
  pinMode(reedSwitchPin, INPUT_PULLUP);

  // Initialize the stable state
  // Now HIGH means no magnet (pull-up), LOW means magnet (switch closed to ground)
  lastReading = digitalRead(reedSwitchPin) == LOW;
  stableState = lastReading;
}

bool isSafeClosed() {
    // Get current raw reading (LOW means magnet present/safe closed)
    bool currentReading = digitalRead(reedSwitchPin) == LOW;
    
    // If the reading changed from last time
    if (currentReading != lastReading) {
        // Reset the debounce timer
        lastDebounceTime = millis();
    }
    
    // Update state only if debounce period has passed
    if ((millis() - lastDebounceTime) > REED_DEBOUNCE_DELAY) {
        // Only update if reading is stable
        if (currentReading != stableState) {
            stableState = currentReading;
        }
    }
    
    // Save the current reading for next time
    lastReading = currentReading;
    
    // Return the debounced state
    return stableState;
}

bool getDebugReedSensorState() {
    return digitalRead(reedSwitchPin);
}

void resetReedSensorState() {
    // Force a reset of the debounce state (useful after physical changes)
    bool currentReading = digitalRead(reedSwitchPin) == LOW;
    lastReading = currentReading;
    stableState = currentReading;
    lastDebounceTime = 0;
}