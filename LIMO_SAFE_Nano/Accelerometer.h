#ifndef ACCELEROMETER_H
#define ACCELEROMETER_H

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

extern const int accelIntPin;
extern volatile bool motionDetected;

void initializeAccelerometer();
void motionInterrupt(); // Interrupt handler

#endif // ACCELEROMETER_H
