#include "WiFiSetup.h"
#include "secrets.h"
#include <WiFi.h>
#include <Preferences.h>

// Define the global wifiPrefs instance
Preferences wifiPrefs;

bool setupWiFi() {
    Serial.println("📡 Setting up WiFi...");
    WiFi.mode(WIFI_STA);
    
    // Try to get credentials from flash first
    wifiPrefs.begin("wifi", false); // false = read-only mode
    String savedSSID = wifiPrefs.getString("ssid", "");
    String savedPass = wifiPrefs.getString("pass", "");
    wifiPrefs.end();

    const char* ssid;
    const char* password;

    // Use saved credentials if they exist, otherwise use defaults
    if (savedSSID.length() > 0 && savedPass.length() > 0) {
        Serial.println("📡 Using saved WiFi credentials");
        ssid = savedSSID.c_str();
        password = savedPass.c_str();
    } else {
        Serial.println("📡 Using default WiFi credentials");
        ssid = WIFI_SSID;
        password = WIFI_PASSWORD;
    }

    WiFi.begin(ssid, password);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 20) {
        delay(500);
        Serial.print(".");
        attempts++;
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        Serial.print("✅ Connected to WiFi. IP: ");
        Serial.println(WiFi.localIP());
        return true;
    } else {
        Serial.println("❌ Failed to connect to WiFi");
        // If we failed with saved credentials, try default ones
        if (savedSSID.length() > 0) {
            Serial.println("📡 Trying default credentials...");
            WiFi.disconnect();
            delay(1000);
            WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
            attempts = 0;
            while (WiFi.status() != WL_CONNECTED && attempts < 20) {
                delay(500);
                Serial.print(".");
                attempts++;
            }
            Serial.println();
            if (WiFi.status() == WL_CONNECTED) {
                Serial.print("✅ Connected to WiFi using defaults. IP: ");
                Serial.println(WiFi.localIP());
                return true;
            }
        }
        return false;
    }
}

bool checkWiFiConnection() {
    static unsigned long lastCheck = 0;
    const unsigned long checkInterval = 30000; // Check every 30 seconds

    if (millis() - lastCheck >= checkInterval) {
        lastCheck = millis();
        
        if (WiFi.status() != WL_CONNECTED) {
            Serial.println("⚠️ WiFi connection lost. Attempting to reconnect...");
            WiFi.disconnect();
            delay(1000);
            return setupWiFi();
        }
    }
    return WiFi.status() == WL_CONNECTED;
}

bool updateWiFiCredentials(const char* ssid, const char* password) {
    wifiPrefs.begin("wifi", true); // true = read-write mode
    wifiPrefs.putString("ssid", ssid);
    wifiPrefs.putString("pass", password);
    wifiPrefs.end();
    
    Serial.println("✅ WiFi credentials updated in flash");
    return true;
}
