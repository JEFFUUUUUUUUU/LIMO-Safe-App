// Core Arduino includes for basic functionality
#include <Arduino.h>
#include <WiFi.h>  // WiFi connectivity support
#include <WiFiClientSecure.h> // Secure WiFi client for encrypted communication
#include <Firebase_ESP_Client.h> // Firebase integration for cloud storage/authentication
#include <Preferences.h> // Non-volatile storage for persistent data

// ESP32-specific headers for low-level WiFi control
#include <esp_wifi.h>

// Project headers for modular functionality
#include "WiFiSetup.h" // WiFi configuration and connection management
#include "FirebaseHandler.h" // Firebase database operations
#include "NanoCommunicator.h" // Communication with Arduino Nano
#include "LightSensor.h" // Light sensor for Morse code input
#include "OTPVerifier.h" // One-time password verification
#include "UserManager.h" // User authentication and management
#include "RGBLed.h" // RGB LED status indicator
#include "FingerprintSensor.h" // Fingerprint reader for biometric authentication
#include "secrets.h" // Confidential credentials and API keys

void setup() {
    setLEDStatus(STATUS_OFFLINE); // Initialize LED to offline state
    Serial.begin(115200); // Start serial communication at 115200 baud
    Serial.println("\n🚀 Starting LIMO SAFE Morse System..."); // Print startup message
    
    setupNanoCommunication(); // Initialize communication with Arduino Nano
    initRGB(); // Initialize RGB LED
    setupLightSensor(); // Configure light sensor for Morse code input
    initializeFingerprint(); // Initialize fingerprint sensor
    //deleteAllFingerprints(); // Commented functionality to wipe fingerprint database

    // Try to connect to WiFi with multiple attempts
    int wifiAttempts = 5;
    while (!setupWiFi() && wifiAttempts > 0) {
        Serial.println("⚠️ Retrying WiFi setup...");
        wifiAttempts--;
        delay(5000); // Wait 5 seconds between attempts
    }

    if (wifiAttempts == 0) {
        Serial.println("❌ WiFi setup failed! Restarting...");
        delay(3000);
        //ESP.restart(); // Commented automatic restart after WiFi failure
    }

    WiFi.setSleep(false); // Disable WiFi sleep mode for better responsiveness
    performTimeSync(); // Synchronize device time with NTP server

    // Try to connect to Firebase with multiple attempts
    int firebaseAttempts = 5;
    while (!setupFirebase() && firebaseAttempts > 0) {
        Serial.println("⚠️ Retrying Firebase setup...");
        firebaseAttempts--;
        delay(5000); // Wait 5 seconds between attempts
    }

    if (firebaseAttempts == 0) {
        Serial.println("❌ Firebase setup failed! Restarting...");
        delay(3000);
        //ESP.restart(); // Commented automatic restart after Firebase failure
    }

    // Log successful WiFi connection to Firebase
    if (Firebase.ready()) {
        FirebaseJson logEntry; // Create JSON object for log entry
        logEntry.set("timestamp", isTimeSynchronized()); // Add timestamp to log
        logEntry.set("event", "wifi_connected"); // Log event type
        logEntry.set("ssid", WiFi.SSID()); // Include connected WiFi network name

        String logPath = String("devices/") + deviceId + "/logs"; // Create path to device logs
        if (Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry)) {
            Serial.println("✅ WiFi connection logged.");
        } else {
            Serial.println("❌ Failed to log WiFi connection.");
            Serial.println(fbdo.errorReason().c_str()); // Print error reason
        }
    }
    updateDeviceStatus(true, false, false); // Update device status in Firebase

    Serial.println("✅ System initialization complete!");
}

unsigned long lastFirebaseCheck = 0; // Timestamp of last Firebase check
const unsigned long FIREBASE_CHECK_INTERVAL = 2000; // Check Firebase every 2 seconds

void loop() {
    // Process light sensor input (decode Morse code)
    processLightInput();
    
    // Handle fingerprint sensor (high-current operation)
    handleFingerprint();
    
    // Always process Nano data to prevent buffer overflow
    handleNanoData();
    
    // Monitor WiFi connection status
    if (!checkWiFiConnection()) {
        Serial.println("⚠️ WiFi disconnected, continuing with local operations");
        // Skip Firebase operations while WiFi is down
    } else {
        // Only check Firebase periodically to reduce overhead
        if (millis() - lastFirebaseCheck >= FIREBASE_CHECK_INTERVAL) {
            checkFirebaseConnection(); // Verify Firebase connectivity
            lastFirebaseCheck = millis(); // Update timestamp
            
            // Continue even if Firebase isn't ready
            if (!Firebase.ready()) {
                Serial.println("⚠️ Firebase unavailable, continuing with local operations");
            } else {
                // Firebase-dependent operations 
                    checkPeriodicWiFiCredentials(); // Check for WiFi credential updates
                    processFirebaseQueue(); // Process pending Firebase operations
                }
            }
        }
    }