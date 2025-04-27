// LightSensor.h

#ifndef LIGHT_SENSOR_H
#define LIGHT_SENSOR_H

#include <Arduino.h>

// Light sensor pin on ESP32
#define LIGHT_SENSOR_PIN 34

// Timing and threshold constants for Morse code detection (milliseconds)
#define UNIT_TIME 70UL                 // Base unit time for Morse timing
#define THRESHOLD 500                  // Threshold of analog reading for LIGHT ON/OFF
#define MESSAGE_TIMEOUT 1000UL         // Timeout after last Morse signal to process message
#define DEBOUNCE_TIME 50UL             // Sensor debounce time to stabilize input

// Morse code timing definitions based on UNIT_TIME multiples
#define LETTER_GAP_DURATION (UNIT_TIME * 3UL)  // Letter gap duration (210 ms)
#define WORD_GAP_DURATION   (UNIT_TIME * 7UL)  // Word gap duration (490 ms)

// Buffer size for light reading smoothing
#define LIGHT_BUFFER_SIZE 10            // Number of readings to average

// Sampling interval for light sensor (milliseconds)
#define LIGHT_SAMPLING_INTERVAL 10      // Read sensor every 10ms for best performance

// Globals for Morse input and state tracking
extern String receivedMorse;
extern String receivedOTP;
extern unsigned long lastChangeTime;
extern bool lastState;

// Function prototypes
void setupLightSensor();                    // Initialize light sensor pin
void processLightInput();                   // Process light sensor readings
int readLightSensor();                      // Read and filter light sensor value
void processMorseSequence(const String& morseSequence); // Process a complete Morse sequence

// Optional: For advanced implementation
// void setupLightInterrupt();              // Set up timer interrupt for precise light sampling
// void IRAM_ATTR onLightSampleTimer();     // Timer interrupt handler for light sampling

#endif