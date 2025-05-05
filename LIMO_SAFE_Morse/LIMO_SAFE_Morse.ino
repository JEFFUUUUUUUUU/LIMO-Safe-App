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
            //Serial.println(fbdo.errorReason().c_str()); // Print error reason
        }
    }
    updateDeviceStatus(true, false, false); // Update device status in Firebase

    Serial.println("✅ System initialization complete!");
}

unsigned long lastFirebaseCheck = 0; // Timestamp of last Firebase check
const unsigned long FIREBASE_CHECK_INTERVAL = 5000; // Check Firebase every 5 seconds (increased from 2s)
bool lastKnownFirebaseStatus = false; // Track last Firebase status

void loop() {
    // Always process inputs and core functionality regardless of connectivity
    // These operations should never be blocked by connectivity issues
    handleFingerprint();
    handleNanoData();
    
    // Non-blocking WiFi status check
    bool wifiConnected = checkWiFiConnection();
    
    // Only periodically check Firebase connectivity to reduce overhead
    unsigned long currentMillis = millis();
    if (currentMillis - lastFirebaseCheck >= FIREBASE_CHECK_INTERVAL) {
        lastFirebaseCheck = currentMillis;
        
        // Only check Firebase if WiFi is connected
        if (wifiConnected) {
            // Use non-blocking Firebase check
            lastKnownFirebaseStatus = checkFirebaseConnection();
            
            // Update LED status based on connectivity
            if (wifiConnected && lastKnownFirebaseStatus) {
                setLEDStatus(STATUS_ONLINE);
            } else if (wifiConnected && !lastKnownFirebaseStatus) {
                setLEDStatus(STATUS_OFFLINE);
            } else {
                setLEDStatus(STATUS_OFFLINE);
            }
            
            // Only try Firebase operations if we believe we're connected
            // These should be fast timeouts to prevent blocking
            if (lastKnownFirebaseStatus) {
                checkPeriodicWiFiCredentials();
                processFirebaseQueue();
            }
        } else {
            // No WiFi connection
            setLEDStatus(STATUS_OFFLINE);
            lastKnownFirebaseStatus = false;
        }
    }
    
    // Every 10 loops, allow a very small delay to prevent watchdog issues
    static int loopCounter = 0;
    if (++loopCounter >= 10) {
        loopCounter = 0;
        delay(1); // Minimal delay to allow background system tasks
    }
}