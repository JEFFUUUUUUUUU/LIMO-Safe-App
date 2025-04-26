// LightSensor.h
#ifndef LIGHT_SENSOR_H
#define LIGHT_SENSOR_H

#include <Arduino.h>

// Define constants for light sensor processing
#define LIGHT_SENSOR_PIN 34        // ESP32 GPIO for light sensor
#define UNIT_TIME 70UL             // Base unit time in ms
#define MESSAGE_TIMEOUT 1000UL     // Timeout to detect message completion
#define DEBOUNCE_TIME 50UL         // Debounce time for signal stability

// Morse code timing definitions
#define LETTER_GAP_DURATION (UNIT_TIME * 3UL)  // 210 ms
#define WORD_GAP_DURATION   (UNIT_TIME * 7UL)  // 490 ms

// Additional timing and filtering constants
#define CALIBRATION_INTERVAL 5000UL   // Time between auto-calibrations (5 seconds)
#define INACTIVE_TIMEOUT 2000UL       // Time to wait after last activity before recalibration
#define SIGNAL_HISTORY_SIZE 10        // Number of signal transitions to track
#define SMOOTHING_ALPHA 0.2           // Exponential smoothing factor (0-1)
#define MIN_CONTRAST_RATIO 0.2        // Minimum ratio between high and low signals
#define TRANSITION_COUNT_THRESHOLD 3  // Number of transitions to detect active transmission
#define TRANSITION_TIME_WINDOW 1000UL // Window to count transitions for activity detection
#define MAX_MORSE_PERIOD 500UL        // Maximum expected period between Morse transitions

// Signal state thresholds with hysteresis
#define THRESHOLD_MARGIN 50           // Margin for hysteresis

// Confidence scoring thresholds
#define MIN_CONFIDENCE_THRESHOLD 0.7  // Minimum confidence to process a signal

// Function prototypes
void setupLightSensor();
void processLightInput();
void calibrateSensor();
bool verifyOTP(String otp);
String decodeMorse(String morse);

#endif