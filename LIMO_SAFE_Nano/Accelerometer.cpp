#include "Accelerometer.h"
#include <Wire.h>
#include <Adafruit_MPU6050.h>

Adafruit_MPU6050 mpu;
bool accelInitialized = false;

// Add variables to track acceleration history
float prevAccelMagnitude = 0;
const float MOTION_THRESHOLD = 0.5; // Adjustable threshold in m/s²

// Variables for detecting significant motion
float accelHistory[3] = {0, 0, 0}; // Store recent acceleration samples
int historyIndex = 0;
const int HISTORY_SIZE = 3;
const float SUSTAINED_MOTION_THRESHOLD = 0.7; // Higher threshold for sustained motion

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
    
    // Configure motion detection in the MPU6050 (still useful even without interrupt)
    Wire.beginTransmission(0x68); // MPU6050 address
    
    // Configure motion threshold - 1 = 4mg, so 20 = 80mg
    Wire.write(0x1F); // Write to MOT_THR register
    Wire.write(20);   // Set motion threshold
    
    // Configure motion duration - 1 = 1ms, so 40 = 40ms
    Wire.write(0x20); // Write to MOT_DUR register
    Wire.write(40);   // Set motion duration
    
    Wire.endTransmission(true);
    
    // Initialize acceleration values
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    prevAccelMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    
    for (int i = 0; i < HISTORY_SIZE; i++) {
        accelHistory[i] = prevAccelMagnitude;
    }
    
    accelInitialized = true;
    return true;
}

// Calculate vector magnitude
float calculateMagnitude(float x, float y, float z) {
    return sqrt(x*x + y*y + z*z);
}

// Detect motion by polling the MPU6050 status register
bool isMotionDetected() {
    if (!accelInitialized) {
        return false;
    }
    
    uint8_t intStatus;
    
    // Read the INT_STATUS register (0x3A)
    Wire.beginTransmission(0x68);
    Wire.write(0x3A);
    Wire.endTransmission(false);
    
    Wire.requestFrom(0x68, 1, true);
    if (Wire.available()) {
        intStatus = Wire.read();
    }
    
    // Check bit 6 (0x40) which is the motion detection interrupt flag
    return (intStatus & 0x40) > 0;
}

// Detect motion by analyzing acceleration changes
bool detectMotionByReading() {
    if (!accelInitialized) {
        return false;
    }
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    // Calculate current acceleration magnitude
    float currentAccelMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    
    // Calculate change in acceleration
    float accelDelta = abs(currentAccelMagnitude - prevAccelMagnitude);
    
    // Update previous reading
    prevAccelMagnitude = currentAccelMagnitude;
    
    // Return true if acceleration change exceeds threshold
    return accelDelta > MOTION_THRESHOLD;
}

// Detect sustained motion by analyzing several consecutive readings
bool detectSustainedMotion() {
    if (!accelInitialized) {
        return false;
    }
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    // Calculate current acceleration magnitude
    float currentAccelMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    
    // Calculate change from gravity
    float gravityMagnitude = 9.8; // Approximate magnitude of gravity in m/s²
    float netAcceleration = abs(currentAccelMagnitude - gravityMagnitude);
    
    // Store in history array
    accelHistory[historyIndex] = netAcceleration;
    historyIndex = (historyIndex + 1) % HISTORY_SIZE;
    
    // Check if most of the recent readings exceed the threshold
    int aboveThresholdCount = 0;
    for (int i = 0; i < HISTORY_SIZE; i++) {
        if (accelHistory[i] > SUSTAINED_MOTION_THRESHOLD) {
            aboveThresholdCount++;
        }
    }
    
    // Return true if majority of readings show motion
    return aboveThresholdCount >= (HISTORY_SIZE / 2 + 1);
}

// Reset motion detection - in polling mode, this just clears the flag
void resetMotionDetection() {
    // Read the INT_STATUS register to clear the motion flag
    uint8_t intStatus;
    Wire.beginTransmission(0x68);
    Wire.write(0x3A); // INT_STATUS register
    Wire.endTransmission(false);
    
    Wire.requestFrom(0x68, 1, true);
    if (Wire.available()) {
        intStatus = Wire.read();
    }
    
    // Also reset the history to current values
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    float currentMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    
    for (int i = 0; i < HISTORY_SIZE; i++) {
        accelHistory[i] = currentMagnitude - 9.8; // Reset to current net acceleration
    }
}

// Get the current acceleration values
void getAccelerationData(float* x, float* y, float* z) {
    if (!accelInitialized) {
        *x = 0;
        *y = 0;
        *z = 0;
        return;
    }
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    *x = a.acceleration.x;
    *y = a.acceleration.y;
    *z = a.acceleration.z;
}

// Get the current gyroscope values
void getGyroscopeData(float* x, float* y, float* z) {
    if (!accelInitialized) {
        *x = 0;
        *y = 0;
        *z = 0;
        return;
    }
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    *x = g.gyro.x;
    *y = g.gyro.y;
    *z = g.gyro.z;
}

// Calculate the orientation (approximate)
void getOrientation(float* pitch, float* roll) {
    if (!accelInitialized) {
        *pitch = 0;
        *roll = 0;
        return;
    }
    
    float x, y, z;
    getAccelerationData(&x, &y, &z);
    
    // Calculate pitch and roll (simplified - assumes no extreme motion)
    *pitch = atan2(y, sqrt(x * x + z * z)) * 180.0 / PI;
    *roll = atan2(-x, z) * 180.0 / PI;
}