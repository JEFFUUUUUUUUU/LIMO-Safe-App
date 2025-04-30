#ifndef NANO_COMMUNICATOR_H
#define NANO_COMMUNICATOR_H

#include <Arduino.h>
#include <HardwareSerial.h>
#include "FirebaseHandler.h"

// **Ensure these values are defined**
#define SERIAL2_RX 16    // ESP32's RX pin connected to Nano's TX
#define SERIAL2_TX 17    // ESP32's TX pin connected to Nano's RX
#define SERIAL2_BAUD 9600

// Function declarations
void setupNanoCommunication();
void handleNanoData();
void sendCommandToNano(String command);
void updateDeviceStatusFromNano(bool isLocked, bool isSecure);
void handleDeviceStatusChange(bool isLocked, bool isSecure);
void logStatusChange(const char* event, bool isLocked, bool isSecure);

// External declarations
extern bool unlockLedActive;
extern unsigned long unlockLedTimer;

// Queue functions are in FirebaseHandler.h
// (removed processFirebaseQueue declaration to avoid conflict)

#endif
