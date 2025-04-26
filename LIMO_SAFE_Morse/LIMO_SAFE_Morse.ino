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
    
    setupNanoCommunication();
    initRGB();
    setupLightSensor();
    initializeFingerprint();
    //deleteAllFingerprints();

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
    updateDeviceStatus(true, false, false);

    Serial.println("✅ System initialization complete!");
}

unsigned long lastFirebaseCheck = 0;
const unsigned long FIREBASE_CHECK_INTERVAL = 2000; // Every 2 seconds

// ✅ Watchdog timer to restart ESP if WiFi or Firebase fail
unsigned long lastSuccess = millis();
const unsigned long WATCHDOG_TIMEOUT = 60000; // 1 minute

bool unlockSent = false;

void loop() {
    if (!unlockSent && authenticateUser()) {
        Serial.println(F("🔓 Auth success! Unlocking..."));
        sendCommandToNano("UNLOCK");
        unlockSent = true;
        setLEDStatus(STATUS_UNLOCKED);
        // Add a small delay to prevent immediate recheck
        delay(2000); // This brief delay is acceptable since the event is rare
    }
    
    // Reset the flag periodically to allow new unlock attempts
    static unsigned long lastResetTime = 0;
    if (unlockSent && millis() - lastResetTime >= 5000) {
        unlockSent = false;
        lastResetTime = millis();
    }

    // ✅ Handle Nano communication (process safe status)
    handleNanoData();

    // ✅ Ensure WiFi is connected
    if (!checkWiFiConnection()) {
        Serial.println("⚠️ WiFi disconnected, continuing with local operations");
        // Skip Firebase operations while WiFi is down
    } else {
        // ✅ Only check Firebase if WiFi is connected
        if (millis() - lastFirebaseCheck >= FIREBASE_CHECK_INTERVAL) {
            checkFirebaseConnection();
            lastFirebaseCheck = millis();
            
            // Continue even if Firebase isn't ready
            if (!Firebase.ready()) {
                Serial.println("⚠️ Firebase unavailable, continuing with local operations");
            } else {
                // Only do these operations if Firebase is available
                // ✅ Process light sensor input (Morse code)
                processLightInput();
                checkPeriodicWiFiCredentials();
                processFirebaseQueue();
                manageFingerprintCommands();
            }
        }
    }
}
