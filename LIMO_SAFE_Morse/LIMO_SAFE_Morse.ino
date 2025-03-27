// Core Arduino includes
#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>

// Project headers
#include "WiFiSetup.h"
#include "FirebaseHandler.h"
#include "NanoCommunicator.h"
#include "LightSensor.h"
#include "OTPVerifier.h"
#include "UserManager.h"
#include "RGBLed.h"
#include "secrets.h"

void setup() {
    Serial.begin(115200);
    Serial.println("\n🚀 Starting LIMO SAFE Morse System...");

    initRGB();
    
    // Initialize light sensor
    setupLightSensor();

    // Setup WiFi first
    if (!setupWiFi()) {
        Serial.println("❌ WiFi setup failed! Restarting...");
        delay(3000);
        ESP.restart();
    }

    // Capture WiFi details immediately after successful connection
    String connectedSSID = WiFi.SSID();
    IPAddress connectedIP = WiFi.localIP();
    
    // Setup Firebase after WiFi is connected
    if (!setupFirebase()) {
        Serial.println("❌ Firebase setup failed! Restarting...");
        delay(3000);
        ESP.restart();
    }

    // Log WiFi connection to Firebase
    if (Firebase.ready()) {
        time_t now = time(nullptr);
        FirebaseJson logEntry;
        logEntry.set("timestamp", (double) now);
        logEntry.set("event", "wifi_connected");
        logEntry.set("ssid", connectedSSID);
        logEntry.set("ip_address", connectedIP.toString());
        
        String logPath = String("devices/") + deviceId + "/logs";
        
        if (Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry)) {
            Serial.println("✅ WiFi connection logged during system startup");
        } else {
            Serial.println("❌ Failed to log WiFi connection during system startup");
            Serial.println(fbdo.errorReason().c_str());
        }
    }

    // Initialize Nano communication
    setupNanoCommunication();
    updateDeviceStatus(false, false, false, false, false);

    Serial.println("✅ System initialization complete!");
}

void loop() {
    // Ensure WiFi is connected
    if (!checkWiFiConnection()) {
        return; // Skip loop if WiFi is not connected
    }

    // Check for new WiFi credentials
    static unsigned long lastWiFiCheck = 0;
    if (millis() - lastWiFiCheck >= 30000) { // Every 30 seconds
        lastWiFiCheck = millis();
        String newSSID, newPassword;
        if (checkForNewWiFiCredentials(newSSID, newPassword)) {
            // Get current credentials from flash
            Preferences wifiPrefs;
            wifiPrefs.begin("wifi", false);
            String currentSSID = wifiPrefs.getString("ssid", "");
            String currentPass = wifiPrefs.getString("pass", "");
            wifiPrefs.end();

            // Only update if credentials are different
            if (currentSSID != newSSID || currentPass != newPassword) {
                Serial.println("📡 New WiFi credentials received from Firebase");
                Serial.print("📡 New SSID: ");
                Serial.println(newSSID);
                if (updateWiFiCredentials(newSSID.c_str(), newPassword.c_str())) {
                    Serial.println("📡 Reconnecting with new credentials...");
                    WiFi.disconnect();
                    delay(1000);
                    setupWiFi();
                }
            }
        }
    }

    // Handle Nano communication (process safe status)
    handleNanoData();
    // Process light sensor input (Morse code)
    processLightInput();
}