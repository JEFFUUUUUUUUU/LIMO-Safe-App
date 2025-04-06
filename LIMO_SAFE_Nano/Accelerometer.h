#ifndef ACCELEROMETER_H
#define ACCELEROMETER_H

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

// Core functions
bool initializeAccelerometer();
bool isMotionDetected();
bool detectMotionByReading();
bool detectSustainedMotion();
void resetMotionDetection();

// Helper functions
float calculateMagnitude(float x, float y, float z);
bool checkForTamper();
void getSensorData(float* ax, float* ay, float* az, float* gx, float* gy, float* gz);
void getAccelerationData(float* x, float* y, float* z);
void getGyroscopeData(float* x, float* y, float* z);
void getOrientation(float* pitch, float* roll);

#endif