#include "Accelerometer.h"

const int accelIntPin = 13;
volatile bool motionDetected = false;
Adafruit_MPU6050 mpu;

void motionInterrupt() {
    motionDetected = true;
}

void initializeAccelerometer() {
    Wire.begin();
    if (!mpu.begin()) {
        Serial.println("MPU6050 not found!");
        while (1);
    }
    
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_1000_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_260_HZ);

    pinMode(accelIntPin, INPUT);
    attachInterrupt(digitalPinToInterrupt(accelIntPin), motionInterrupt, RISING);
}
