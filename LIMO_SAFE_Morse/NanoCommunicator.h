#ifndef NanoCommunicator_h
#define NanoCommunicator_h

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include "FirebaseHandler.h"

// **Ensure these values are defined**
#define SERIAL2_RX 16    // ESP32's RX pin connected to Nano's TX
#define SERIAL2_TX 17    // ESP32's TX pin connected to Nano's RX
#define SERIAL2_BAUD 9600

// Function declarations
void setupNanoCommunication();
void handleNanoData();
void sendCommandToNano(const String& command);

// Sensor data upload functions
void uploadSensorData(const String& sensorType, float value);
void uploadTemperature(float temperature);
void uploadLight(float light);
void uploadHumidity(float humidity);

#endif
