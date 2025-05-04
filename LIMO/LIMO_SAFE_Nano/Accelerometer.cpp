#include "Accelerometer.h"

// Single static instance
static Adafruit_MPU6050 mpu;
static bool initialized = false;

// Constants
#define MOTION_THRESHOLD 0.5f
#define SUSTAINED_THRESHOLD 0.7f
#define HISTORY_SIZE 3
#define MPU_ADDR 0x68
#define GRAVITY 9.8f

// Motion detection variables
static float prevMagnitude = 0;
static float history[HISTORY_SIZE];
static uint8_t historyIdx = 0;

bool initializeAccelerometer() {
    Wire.begin();
    
    if (!mpu.begin()) {
        return false;
    }
   
    // Basic configuration
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_1000_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_260_HZ);
    
    // Motion detection setup
    Wire.beginTransmission(MPU_ADDR);
    Wire.write(0x1F);  // Motion threshold register
    Wire.write(20);    // 80mg
    Wire.write(0x20);  // Motion duration register
    Wire.write(40);    // 40ms
    Wire.endTransmission(true);
    
    // Initialize values
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    prevMagnitude = sqrt(a.acceleration.x*a.acceleration.x + 
                         a.acceleration.y*a.acceleration.y + 
                         a.acceleration.z*a.acceleration.z);
    
    // Fill history
    for (uint8_t i = 0; i < HISTORY_SIZE; i++) {
        history[i] = prevMagnitude;
    }
    
    initialized = true;
    return true;
}

float calculateMagnitude(float x, float y, float z) {
    return sqrt(x*x + y*y + z*z);
}

bool isMotionDetected() {
    if (!initialized) return false;
    
    Wire.beginTransmission(MPU_ADDR);
    Wire.write(0x3A);  // INT_STATUS register
    Wire.endTransmission(false);
    
    Wire.requestFrom(MPU_ADDR, 1, true);
    return (Wire.available() && (Wire.read() & 0x40));  // Check bit 6
}

bool detectMotionByReading() {
    if (!initialized) return false;
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    float current = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    float delta = abs(current - prevMagnitude);
    prevMagnitude = current;
    
    return delta > MOTION_THRESHOLD;
}

bool detectSustainedMotion() {
    if (!initialized) return false;
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    // Net acceleration relative to gravity
    float current = calculateMagnitude(a.acceleration.x, a.acceleration.y, a.acceleration.z);
    float netAccel = abs(current - GRAVITY);
    
    // Update history
    history[historyIdx] = netAccel;
    historyIdx = (historyIdx + 1) % HISTORY_SIZE;
    
    // Weighted count of readings above threshold
    uint8_t count = 0;
    float decay = 1.0;
    uint8_t idx = historyIdx;
    
    for (uint8_t i = 0; i < HISTORY_SIZE; i++) {
        // Get previous index
        idx = (idx == 0) ? HISTORY_SIZE - 1 : idx - 1;
        
        if (history[idx] > SUSTAINED_THRESHOLD) {
            count += decay;
        }
        
        decay *= 0.8;  // 20% reduction for older readings
    }
    
    return count >= (HISTORY_SIZE / 2);
}

void resetMotionDetection() {
    if (!initialized) return;
    
    // Clear interrupt status
    Wire.beginTransmission(MPU_ADDR);
    Wire.write(0x3A);
    Wire.endTransmission(false);
    Wire.requestFrom(MPU_ADDR, 1, true);
    if (Wire.available()) Wire.read();
    
    historyIdx = 0;
}

bool checkForTamper() {
    static bool tamperState = false;
    static unsigned long tamperClearTime = 0;
    
    // Check for motion
    bool motion = detectSustainedMotion();
    
    // Update tamper state
    if (motion) {
        tamperState = true;
        tamperClearTime = 0;
        resetMotionDetection();
    } else if (tamperState && tamperClearTime == 0) {
        // Start countdown to clear tamper state
        tamperClearTime = millis();
    } else if (tamperState && millis() - tamperClearTime > 1000) {
        // No motion for 1 second, clear tamper
        tamperState = false;
        tamperClearTime = 0;
        resetMotionDetection();
    }
    
    return tamperState;
}

void getSensorData(float* ax, float* ay, float* az, float* gx, float* gy, float* gz) {
    if (!initialized) {
        *ax = *ay = *az = *gx = *gy = *gz = 0;
        return;
    }
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    
    *ax = a.acceleration.x;
    *ay = a.acceleration.y;
    *az = a.acceleration.z;
    *gx = g.gyro.x;
    *gy = g.gyro.y;
    *gz = g.gyro.z;
}

void getAccelerationData(float* x, float* y, float* z) {
    float gx, gy, gz;  // Unused but needed
    getSensorData(x, y, z, &gx, &gy, &gz);
}

void getGyroscopeData(float* x, float* y, float* z) {
    float ax, ay, az;  // Unused but needed
    getSensorData(&ax, &ay, &az, x, y, z);
}

void getOrientation(float* pitch, float* roll) {
    float x, y, z;
    getAccelerationData(&x, &y, &z);
    
    *pitch = atan2(y, sqrt(x*x + z*z)) * 180.0 / PI;
    *roll = atan2(-x, z) * 180.0 / PI;
}