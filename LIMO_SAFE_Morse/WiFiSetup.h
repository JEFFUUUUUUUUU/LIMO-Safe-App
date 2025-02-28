#ifndef WiFiSetup_h
#define WiFiSetup_h

#include <Arduino.h>
#include <Preferences.h>

extern Preferences wifiPrefs;

bool setupWiFi();
bool checkWiFiConnection();
bool updateWiFiCredentials(const char* ssid, const char* password);

#endif
