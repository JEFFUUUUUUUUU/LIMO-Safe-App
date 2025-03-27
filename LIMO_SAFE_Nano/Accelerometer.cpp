#include "Accelerometer.h"
#include <Wire.h>
#include <Adafruit_MPU6050.h>

const int accelIntPin = 13;
volatile bool motionDetected = false;
Adafruit_MPU6050 mpu;
bool accelInitialized = false;

void motionInterrupt() {
    motionDetected = true;
}

bool initializeAccelerometer() {
    // Initialize I2C communication
    Wire.begin();
    
    // Attempt to initialize the MPU6050
    if (!mpu.begin()) {
        Serial.println("MPU6050 not found!");
        accelInitialized = false;
        return false;
    }
   
    // Configure sensor settings
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_1000_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_260_HZ);
    
    // Set up interrupt pin
    pinMode(accelIntPin, INPUT);
    attachInterrupt(digitalPinToInterrupt(accelIntPin), motionInterrupt, RISING);
   
    accelInitialized = true;
    return true;
}

// You might want to add a function to check motion detection
bool isMotionDetected() {
    return motionDetected;
}

// Function to reset motion detection flag
void resetMotionDetection() {
    motionDetected = false;
}