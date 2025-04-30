#ifndef WIFI_SETUP_H
#define WIFI_SETUP_H

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>
#include <WiFi.h>
#include <time.h>

extern Preferences wifiPrefs;

// Constants for WiFi setup
#define WIFI_CONNECT_TIMEOUT_MS 10000  // 10 seconds timeout for connection

// Return codes for non-blocking WiFi setup
#define WIFI_SETUP_IN_PROGRESS  0
#define WIFI_SETUP_COMPLETE     1
#define WIFI_SETUP_FAILED      -1

// Global variables used across files
extern bool flashWriteInProgress;

// WiFi Functions
void WiFiEventHandler(WiFiEvent_t event);
bool setupWiFi(bool fromCredentialUpdate = false);
bool checkWiFiConnection();
bool updateWiFiCredentials(const char* ssid, const char* password);
void clearFlashStorage(bool skipRestart = false);
void setLEDStatus(int status); // Refer to RGBLed.h for status codes

// Non-blocking flash operations
bool checkFlashClearStatus();
bool isFlashWriteComplete();

// NTP Time Sync Functions
void performTimeSync();
unsigned long long isTimeSynchronized();

// Non-blocking WiFi setup
int nonBlockingWiFiSetup();
void resetNonBlockingWiFiSetup();

#endif
