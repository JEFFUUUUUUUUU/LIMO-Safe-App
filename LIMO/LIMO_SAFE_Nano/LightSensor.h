#ifndef LIGHT_SENSOR_H
#define LIGHT_SENSOR_H

#include <Arduino.h>

// Define constants for light sensor processing
#define LIGHT_SENSOR_PIN A7   // Arduino Nano analog pin for light sensor
#define UNIT_TIME 45UL        // Base unit time in ms
#define THRESHOLD_BASE 100    // Fixed threshold value for light detection
#define MESSAGE_TIMEOUT 1000UL // Timeout to detect message completion
#define DEBOUNCE_TIME 20UL    // Debounce time for signal stability

// Morse code timing definitions
#define LETTER_GAP_DURATION (UNIT_TIME * 3UL)  // 210 ms

// Global variables
extern String receivedMorse;
extern String receivedOTP;
extern unsigned long lastChangeTime;
extern bool lastState;
extern int currentThreshold;  // Fixed threshold value
extern unsigned long lastAdaptiveUpdate; // Unused, kept for compatibility

// Function prototypes
void setupLightSensor();
int getSmoothReading();
void updateAdaptiveThreshold(); // Now uses fixed threshold (no calibration)
void processLightInput();
void calibrateSensor();

#endif 