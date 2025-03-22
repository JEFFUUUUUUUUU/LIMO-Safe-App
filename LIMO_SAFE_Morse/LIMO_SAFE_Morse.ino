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
#include "secrets.h"

void setup() {
    Serial.begin(115200);
    Serial.println("\n🚀 Starting LIMO SAFE Morse System...");
    
    // Initialize light sensor
    setupLightSensor();

    // Setup WiFi first
    if (!setupWiFi()) {
        Serial.println("❌ WiFi setup failed! Restarting...");
        delay(3000);
        ESP.restart();
    }

    // Setup Firebase after WiFi is connected
    if (!setupFirebase()) {
        Serial.println("❌ Firebase setup failed! Restarting...");
        delay(3000);
        ESP.restart();
    }

    // Initialize Nano communication
    setupNanoCommunication();

    Serial.println("✅ System initialization complete!");
}

void loop() {
    // Ensure WiFi is connected
    if (!checkWiFiConnection()) {
        return; // Skip loop if WiFi is not connected
    }

    // Update device status in Firebase periodically
    static unsigned long lastStatusUpdate = 0;
    if (millis() - lastStatusUpdate >= 60000) { // Every minute
        updateDeviceStatus(true, false, true);
        lastStatusUpdate = millis();
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
