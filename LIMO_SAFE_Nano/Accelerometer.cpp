#include "Accelerometer.h"

// Single static instance for the hardware
static Adafruit_MPU6050 mpu;
static bool accelInitialized = false;

// Constants in PROGMEM or as #define to save RAM
#define MOTION_THRESHOLD 0.5f
#define SUSTAINED_MOTION_THRESHOLD 0.7f
#define HISTORY_SIZE 3
#define MPU_ADDRESS 0x68
#define GRAVITY_MAGNITUDE 9.8f

// Variables for motion detection
static float prevAccelMagnitude = 0;
static float accelHistory[HISTORY_SIZE];
static uint8_t historyIndex = 0;

bool initializeAccelerometer() {
    Wire.begin();
    
    if (!mpu.begin()) {
        return false;
    }
   
    // Configure with minimal settings
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_1000_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_260_HZ);
    
    // Configure motion detection
    Wire.beginTransmission(MPU_ADDRESS);
    Wire.write(0x1F);  // MOT_THR register
    Wire.write(20);    // Threshold (80mg)
    Wire.write(0x20);  // MOT_DUR register
    Wire.write(40);    // Duration (40ms)
    Wire.endTransmission(true);
    
    // Initialize values
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    prevAccelMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    
    // Initialize history with current values
    for (uint8_t i = 0; i < HISTORY_SIZE; i++) {
        accelHistory[i] = prevAccelMagnitude;
    }
    
    accelInitialized = true;
    return true;
}

// Optimized magnitude calculation
float calculateMagnitude(float x, float y, float z) {
    return sqrt(x*x + y*y + z*z);
}

// Check MPU6050 status register for motion detection
bool isMotionDetected() {
    if (!accelInitialized) return false;
    
    // Read INT_STATUS register
    Wire.beginTransmission(MPU_ADDRESS);
    Wire.write(0x3A);
    Wire.endTransmission(false);
    
    Wire.requestFrom(MPU_ADDRESS, 1, true);
    if (Wire.available()) {
        return (Wire.read() & 0x40) > 0;  // Check bit 6
    }
    return false;
}

// Detect motion by analyzing acceleration
bool detectMotionByReading() {
    if (!accelInitialized) return false;
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    float currentMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    float delta = abs(currentMagnitude - prevAccelMagnitude);
    prevAccelMagnitude = currentMagnitude;
    
    return delta > MOTION_THRESHOLD;
}

// Detect sustained motion
bool detectSustainedMotion() {
    if (!accelInitialized) return false;
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    // Calculate net acceleration (relative to gravity)
    float currentMagnitude = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    float netAccel = abs(currentMagnitude - GRAVITY_MAGNITUDE);
    
    // Update history
    accelHistory[historyIndex] = netAccel;
    historyIndex = (historyIndex + 1) % HISTORY_SIZE;
    
    // Count readings above threshold
    uint8_t count = 0;
    for (uint8_t i = 0; i < HISTORY_SIZE; i++) {
        if (accelHistory[i] > SUSTAINED_MOTION_THRESHOLD) {
            count++;
        }
    }
    
    return count >= ((HISTORY_SIZE / 2) + 1);
}

// Reset motion detection
void resetMotionDetection() {
    if (!accelInitialized) return;
    
    // Read INT_STATUS to clear flags
    Wire.beginTransmission(MPU_ADDRESS);
    Wire.write(0x3A);
    Wire.endTransmission(false);
    Wire.requestFrom(MPU_ADDRESS, 1, true);
    if (Wire.available()) {
        Wire.read();  // Just read to clear
    }
    
    // Reset history
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    float currMag = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    
    for (uint8_t i = 0; i < HISTORY_SIZE; i++) {
        accelHistory[i] = abs(currMag - GRAVITY_MAGNITUDE);
    }
}

// Combined sensor reading to reduce duplicate calls
void getSensorData(float* ax, float* ay, float* az, float* gx, float* gy, float* gz) {
    sensors_event_t a, g, temp;
    
    if (!accelInitialized) {
        *ax = *ay = *az = *gx = *gy = *gz = 0;
        return;
    }
    
    mpu.getEvent(&a, &g, &temp);
    
    *ax = a.acceleration.x;
    *ay = a.acceleration.y;
    *az = a.acceleration.z;
    *gx = g.gyro.x;
    *gy = g.gyro.y;
    *gz = g.gyro.z;
}

// Get acceleration data
void getAccelerationData(float* x, float* y, float* z) {
    float gx, gy, gz;  // Unused but needed for the call
    getSensorData(x, y, z, &gx, &gy, &gz);
}

// Get gyroscope data
void getGyroscopeData(float* x, float* y, float* z) {
    float ax, ay, az;  // Unused but needed for the call
    getSensorData(&ax, &ay, &az, x, y, z);
}

// Calculate orientation
void getOrientation(float* pitch, float* roll) {
    float x, y, z;
    getAccelerationData(&x, &y, &z);
    
    *pitch = atan2(y, sqrt(x * x + z * z)) * 180.0 / PI;
    *roll = atan2(-x, z) * 180.0 / PI;
}