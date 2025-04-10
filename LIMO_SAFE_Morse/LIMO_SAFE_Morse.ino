// Core Arduino includes
#include <Arduino.h>
#include <WiFi.h>  
#include <WiFiClientSecure.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>

// ESP32-specific headers
#include <esp_wifi.h>

// Project headers
#include "WiFiSetup.h"
#include "FirebaseHandler.h"
#include "NanoCommunicator.h"
#include "LightSensor.h"
#include "OTPVerifier.h"
#include "UserManager.h"
#include "RGBLed.h"
#include "FingerprintSensor.h"
#include "secrets.h"

void setup() {
    setLEDStatus(STATUS_OFFLINE);
    Serial.begin(115200);
    Serial.println("\n🚀 Starting LIMO SAFE Morse System...");

    initRGB();
    setupLightSensor();
    initializeFingerprint();
    //deleteAllFingerprint();

    // ✅ Ensure WiFi is available
    int wifiAttempts = 5;
    while (!setupWiFi() && wifiAttempts > 0) {
        Serial.println("⚠️ Retrying WiFi setup...");
        wifiAttempts--;
        delay(5000);
    }

    if (wifiAttempts == 0) {
        Serial.println("❌ WiFi setup failed! Restarting...");
        delay(3000);
        //ESP.restart();
    }

    WiFi.setSleep(false);
    performTimeSync();

    // ✅ Ensure Firebase is available
    int firebaseAttempts = 5;
    while (!setupFirebase() && firebaseAttempts > 0) {
        Serial.println("⚠️ Retrying Firebase setup...");
        firebaseAttempts--;
        delay(5000);
    }

    if (firebaseAttempts == 0) {
        Serial.println("❌ Firebase setup failed! Restarting...");
        delay(3000);
        //ESP.restart();
    }

    // ✅ Log WiFi connection
    if (Firebase.ready()) {
        FirebaseJson logEntry;
        logEntry.set("timestamp", isTimeSynchronized());
        logEntry.set("event", "wifi_connected");
        logEntry.set("ssid", WiFi.SSID());

        String logPath = String("devices/") + deviceId + "/logs";
        if (Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry)) {
            Serial.println("✅ WiFi connection logged.");
        } else {
            Serial.println("❌ Failed to log WiFi connection.");
            Serial.println(fbdo.errorReason().c_str());
        }
    }

    // Initialize Nano communication
    setupNanoCommunication();
    updateDeviceStatus(true, false, false);

    Serial.println("✅ System initialization complete!");
}

unsigned long lastFirebaseCheck = 0;
const unsigned long FIREBASE_CHECK_INTERVAL = 30000; // Every 30 seconds

// ✅ Watchdog timer to restart ESP if WiFi or Firebase fail
unsigned long lastSuccess = millis();
const unsigned long WATCHDOG_TIMEOUT = 60000; // 1 minute

bool unlockSent = false;

void loop() {
    if (!unlockSent && authenticateUser()) {
        Serial.println(F("🔓 Auth success! Unlocking..."));
        sendCommandToNano("UNLOCK");
        unlockSent = true;
        setLEDStatus(STATUS_FINGERPRINT_OK);
        // Add a small delay to prevent immediate recheck
        delay(1000); // This brief delay is acceptable since the event is rare
    }
    
    // Reset the flag periodically to allow new unlock attempts
    static unsigned long lastResetTime = 0;
    if (unlockSent && millis() - lastResetTime >= 5000) {
        unlockSent = false;
        lastResetTime = millis();
    }

    // ✅ Ensure WiFi is connected
    if (!checkWiFiConnection()) {
        Serial.println("❌ WiFi lost! Restarting...");
        delay(3000);
        //ESP.restart();
    }

    // ✅ Ensure Firebase is connected
    if (millis() - lastFirebaseCheck >= FIREBASE_CHECK_INTERVAL) {
        checkFirebaseConnection();
        lastFirebaseCheck = millis();

        if (!Firebase.ready()) {
            Serial.println("❌ Firebase lost! Restarting...");
            delay(3000);
            //ESP.restart();
        }
    }
    
    // ✅ Check for new WiFi credentials
    static unsigned long lastWiFiCheck = 0;
    if (millis() - lastWiFiCheck >= 30000) { // Every 30 seconds
        lastWiFiCheck = millis();
        String newSSID, newPassword;
        if (checkForNewWiFiCredentials(newSSID, newPassword)) {
            Preferences wifiPrefs;
            wifiPrefs.begin("wifi", false);
            String currentSSID = wifiPrefs.getString("ssid", "");
            String currentPass = wifiPrefs.getString("pass", "");
            wifiPrefs.end();

            if (currentSSID != newSSID || currentPass != newPassword) {
                Serial.println("📡 New WiFi credentials received!");
                if (updateWiFiCredentials(newSSID.c_str(), newPassword.c_str())) {
                    Serial.println("📡 Reconnecting with new credentials...");
                    WiFi.disconnect();
                    delay(1000);
                    WiFi.reconnect();
                }
            }
        }
    }
    
    // ✅ Handle Nano communication (process safe status)
    handleNanoData();
    processFirebaseQueue(); 
    // ✅ Process light sensor input (Morse code)
    processLightInput();
}
