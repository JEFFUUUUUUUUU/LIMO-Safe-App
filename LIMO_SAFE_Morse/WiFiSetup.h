#ifndef WiFiSetup_h
#define WiFiSetup_h

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>

extern Preferences wifiPrefs;

bool setupWiFi();
bool checkWiFiConnection();
bool updateWiFiCredentials(const char* ssid, const char* password);
void clearFlashStorage();

#endif
