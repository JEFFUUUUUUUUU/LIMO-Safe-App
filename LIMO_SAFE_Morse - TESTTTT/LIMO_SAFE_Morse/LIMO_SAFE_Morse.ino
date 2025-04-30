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

// Non-blocking operation flags and timers
unsigned long unlockLedTimer = 0;
bool unlockLedActive = false;
unsigned long wifiSetupTimer = 0;
bool wifiSetupInProgress = false;
int wifiSetupAttempts = 0;
unsigned long firebaseSetupTimer = 0;
bool firebaseSetupInProgress = false;
int firebaseSetupAttempts = 0;
bool systemInitialized = false;

void setup() {
    setLEDStatus(STATUS_OFFLINE); // Initialize LED to offline state
    Serial.begin(115200); // Start serial communication at 115200 baud
    Serial.println("\n🚀 Starting LIMO SAFE Morse System..."); // Print startup message
    
    setupNanoCommunication(); // Initialize communication with Arduino Nano
    initRGB(); // Initialize RGB LED
    setupLightSensor(); // Configure light sensor for Morse code input
    initializeFingerprint(); // Initialize fingerprint sensor
    //deleteAllFingerprints(); // Commented functionality to wipe fingerprint database

    // Set up WiFi connection attempts - non-blocking
    wifiSetupInProgress = true;
    wifiSetupAttempts = 5;
    wifiSetupTimer = millis(); // Start WiFi setup process
    
    // System will continue setup in the main loop using non-blocking operations
}

unsigned long lastFirebaseCheck = 0; // Timestamp of last Firebase check
const unsigned long FIREBASE_CHECK_INTERVAL = 2000; // Check Firebase every 2 seconds

void loop() {
    // Continue system initialization if not yet complete
    if (!systemInitialized) {
        if (wifiSetupInProgress) {
            // Handle WiFi setup with timeouts
            if (millis() - wifiSetupTimer >= 500) { // Check every 500ms
                wifiSetupTimer = millis();
                
                if (setupWiFi()) {
                    Serial.println("✅ WiFi connected successfully");
                    wifiSetupInProgress = false;
                    WiFi.setSleep(false); // Disable WiFi sleep mode for better responsiveness
                    performTimeSync(); // Synchronize device time with NTP server
                    
                    // Start Firebase setup
                    firebaseSetupInProgress = true;
                    firebaseSetupAttempts = 5;
                    firebaseSetupTimer = millis();
                } else {
                    wifiSetupAttempts--;
                    if (wifiSetupAttempts <= 0) {
                        Serial.println("❌ WiFi setup failed after multiple attempts!");
                        Serial.println("Continuing with local operations only");
                        wifiSetupInProgress = false;
                        
                        // Continue with Firebase setup anyway, it will handle WiFi issues
                        firebaseSetupInProgress = true;
                        firebaseSetupAttempts = 5;
                        firebaseSetupTimer = millis();
                    }
                }
            }
        } 
        else if (firebaseSetupInProgress) {
            // Handle Firebase setup with timeouts
            if (millis() - firebaseSetupTimer >= 500) { // Check every 500ms
                firebaseSetupTimer = millis();
                
                if (setupFirebase()) {
                    Serial.println("✅ Firebase connected successfully");
                    firebaseSetupInProgress = false;
                    
                    // Log successful WiFi connection to Firebase
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
                    
                    // Complete system initialization
                    systemInitialized = true;
                    Serial.println("✅ System initialization complete!");
                } else {
                    firebaseSetupAttempts--;
                    if (firebaseSetupAttempts <= 0) {
                        Serial.println("❌ Firebase setup failed after multiple attempts!");
                        Serial.println("Continuing with local operations only");
                        firebaseSetupInProgress = false;
                        systemInitialized = true; // Continue without Firebase
                        Serial.println("✅ System initialization complete (offline mode)!");
                    }
                }
            }
        }
        
        // Process critical functions even during initialization
        handleNanoData();
        yield();
        return; // Skip the rest of the loop until initialization is complete
    }
    
    // Handle non-blocking LED unlocked indicator
    if (unlockLedActive && millis() - unlockLedTimer >= 3000) {
        unlockLedActive = false;
        setColorRGB(COLOR_OFF);
    }
    
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