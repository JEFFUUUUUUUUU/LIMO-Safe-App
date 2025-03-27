#ifndef WiFiSetup_h
#define WiFiSetup_h

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>
#include <WiFi.h>
#include <time.h>

extern Preferences wifiPrefs;

// WiFi Functions
bool setupWiFi();
bool checkWiFiConnection();
bool updateWiFiCredentials(const char* ssid, const char* password);
void clearFlashStorage();

// NTP Time Sync Functions
void performTimeSync();
bool isTimeSynchronized();
void printCurrentTime();
void printTimeErrorDiagnostics();

#endif
