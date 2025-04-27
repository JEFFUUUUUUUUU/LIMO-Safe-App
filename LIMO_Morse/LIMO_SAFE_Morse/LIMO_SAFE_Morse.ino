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

// Timing variables for loop optimization
// lastFingerprintCheck and FINGERPRINT_CHECK_INTERVAL are defined in FingerprintSensor.cpp
unsigned long lastNanoCheck = 0;
const unsigned long NANO_CHECK_INTERVAL = 20; // 20ms for nano communication
unsigned long lastFirebaseCheck = 0;
const unsigned long FIREBASE_CHECK_INTERVAL = 2000; // Every 2 seconds

// ✅ Watchdog timer to restart ESP if WiFi or Firebase fail
unsigned long lastSuccess = millis();
const unsigned long WATCHDOG_TIMEOUT = 60000; // 1 minute

bool unlockSent = false;

void loop() {
    unsigned long currentTime = millis();
    
    // ✅ Process light sensor input (Morse code) - HIGHEST PRIORITY
    // Always process light input first to ensure timing accuracy
    processLightInput();
    
    // ✅ Handle fingerprint sensor with controlled timing
    if (currentTime - lastFingerprintCheck >= FINGERPRINT_CHECK_INTERVAL) {
        handleFingerprint();
        lastFingerprintCheck = currentTime;
    }
    
    // ✅ Handle Nano communication (process safe status) with controlled timing
    if (currentTime - lastNanoCheck >= NANO_CHECK_INTERVAL) {
        handleNanoData();
        lastNanoCheck = currentTime;
    }

    // ✅ Ensure WiFi is connected - LOWEST PRIORITY (least frequent)
    if (currentTime - lastFirebaseCheck >= FIREBASE_CHECK_INTERVAL) {
        if (!checkWiFiConnection()) {
            Serial.println("⚠️ WiFi disconnected, continuing with local operations");
            // Skip Firebase operations while WiFi is down
        } else {
            // ✅ Only check Firebase if WiFi is connected
            checkFirebaseConnection();
            
            // Continue even if Firebase isn't ready
            if (!Firebase.ready()) {
                Serial.println("⚠️ Firebase unavailable, continuing with local operations");
            } else {
                // Only do these operations if Firebase is available
                checkPeriodicWiFiCredentials();
                processFirebaseQueue();
            }
        }
        lastFirebaseCheck = currentTime;
    }
    
    // Optional: Very small delay to prevent CPU hogging
    // This actually helps improve overall responsiveness
    delay(1);
}