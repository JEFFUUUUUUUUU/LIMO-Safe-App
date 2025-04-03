#ifndef ACCELEROMETER_H
#define ACCELEROMETER_H

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

extern bool accelInitialized;

// Core functions
bool initializeAccelerometer();
bool isMotionDetected();
bool detectMotionByReading();
bool detectSustainedMotion();
void resetMotionDetection();

// Helper functions
float calculateMagnitude(float x, float y, float z);
void getAccelerationData(float* x, float* y, float* z);
void getGyroscopeData(float* x, float* y, float* z);
void getOrientation(float* pitch, float* roll);

#endif // ACCELEROMETER_H