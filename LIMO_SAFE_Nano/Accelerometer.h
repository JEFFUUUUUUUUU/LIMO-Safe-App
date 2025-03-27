#ifndef ACCELEROMETER_H
#define ACCELEROMETER_H

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

extern const int accelIntPin;
extern volatile bool motionDetected;
extern bool accelInitialized;

// Change the return type to bool to match the implementation
bool initializeAccelerometer();
void motionInterrupt(); // Interrupt handler

// Additional utility functions
bool isMotionDetected();
void resetMotionDetection();

#endif // ACCELEROMETER_H