// Core Arduino includes for basic functionality
#include <Arduino.h>
#include <WiFi.h>  // WiFi connectivity support
#include <WiFiClientSecure.h> // Secure WiFi client for encrypted communication
#include <Firebase_ESP_Client.h> // Firebase integration for cloud storage/authentication
#include <Preferences.h> // Non-volatile storage for persistent data
#include <esp_wifi.h> // ESP32-specific headers for low-level WiFi control

// Project headers for modular functionality
#include "WiFiSetup.h" // WiFi configuration and connection management
#include "FirebaseHandler.h" // Firebase database operations
#include "NanoCommunicator.h" // Communication with Arduino Nano
#include "LightSensor.h" // Light sensor for Morse code input
#include "OTPVerifier.h" // One-time password verification
#include "UserManager.h" // User authentication and management
#include "RGBLed.h" // RGB LED status indicator
#include "FingerprintSensor.h" // Fingerprint reader for biometric authentication
#include "SystemInit.h" // System initialization
#include "NetworkManager.h" // Network management
#include "InputManager.h" // Input manager for handling multiple input sources
#include "secrets.h" // Confidential credentials and API keys

// State variables
unsigned long unlockLedTimer = 0;
bool unlockLedActive = false;
unsigned long wifiSetupTimer = 0;
bool wifiSetupInProgress = false;
int wifiSetupAttempts = 0;
unsigned long firebaseSetupTimer = 0;
bool firebaseSetupInProgress = false;
int firebaseSetupAttempts = 0;
bool systemInitialized = false;
unsigned long lastFirebaseCheck = 0; 
const unsigned long FIREBASE_CHECK_INTERVAL = 2000;

void setup() {
    Serial.begin(115200);
    initializeSystem();
    setupNetworkChecks();
}

void loop() {
    // System Initialization Phase
    if (!handleSystemInitialization()) {
        handleNanoData(); // Critical during initialization
        yield();
        return;
    }
    
    // Main System Loop
    updateLEDEffects();    // Update LED status effects
    processAllInputs();    // Process all input sources
    handleNetworkTasks();  // Handle WiFi and Firebase
    
    // Handle flash memory operations
    checkFlashClearStatus();
    if (flashWriteInProgress) {
        isFlashWriteComplete();
    }
}