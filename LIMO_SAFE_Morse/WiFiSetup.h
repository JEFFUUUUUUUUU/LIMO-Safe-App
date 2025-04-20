#ifndef WiFiSetup_h
#define WiFiSetup_h

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>
#include <WiFi.h>
#include <time.h>

extern Preferences wifiPrefs;

// WiFi Functions
void WiFiEventHandler(WiFiEvent_t event);
bool setupWiFi(bool fromCredentialUpdate = false);
bool checkWiFiConnection();
bool updateWiFiCredentials(const char* ssid, const char* password);
void clearFlashStorage(bool skipRestart = false);

// NTP Time Sync Functions
void performTimeSync();
bool isTimeSynchronized(unsigned long long &epochMilliseconds);
unsigned long long isTimeSynchronized();
void printCurrentTime();
void printTimeErrorDiagnostics();

#endif
