#include "SystemInit.h"
#include "WiFiSetup.h"
#include "FirebaseHandler.h"
#include "RGBLed.h"
#include "NanoCommunicator.h"
#include "LightSensor.h"
#include "FingerprintSensor.h"
#include <WiFi.h>

// Initialize hardware components
void initializeSystem() {
    Serial.println("\n🚀 Starting LIMO SAFE Morse System...");
    
    setLEDStatus(STATUS_OFFLINE);
    initRGB();
    setupNanoCommunication();
    setupLightSensor();
    initializeFingerprint();
    
    // Start WiFi setup process (non-blocking)
    wifiSetupInProgress = true;
    wifiSetupAttempts = 5;
    wifiSetupTimer = millis();
    resetNonBlockingWiFiSetup();
}

// Handles the system initialization process
// Returns true if initialization is complete
bool handleSystemInitialization() {
    // Already initialized
    if (systemInitialized) {
        return true;
    }
    
    // Step 1: WiFi Setup
    if (wifiSetupInProgress) {
        if (millis() - wifiSetupTimer >= 100) {
            wifiSetupTimer = millis();
            
            int setupResult = nonBlockingWiFiSetup();
            if (setupResult == WIFI_SETUP_COMPLETE) {
                // WiFi setup successful
                Serial.println("✅ WiFi connected successfully");
                wifiSetupInProgress = false;
                WiFi.setSleep(false); // Better responsiveness
                performTimeSync();
                
                // Start Firebase setup
                firebaseSetupInProgress = true;
                firebaseSetupAttempts = 5;
                firebaseSetupTimer = millis();
                resetFirebaseSetup();
            } 
            else if (setupResult == WIFI_SETUP_FAILED) {
                // Handle failed WiFi setup
                wifiSetupAttempts--;
                if (wifiSetupAttempts <= 0) {
                    Serial.println("❌ WiFi setup failed after multiple attempts!");
                    Serial.println("Continuing with local operations only");
                    wifiSetupInProgress = false;
                    
                    // Try Firebase anyway
                    firebaseSetupInProgress = true;
                    firebaseSetupAttempts = 5;
                    firebaseSetupTimer = millis();
                    resetFirebaseSetup();
                } else {
                    resetNonBlockingWiFiSetup();
                }
            }
        }
    }
    // Step 2: Firebase Setup
    else if (firebaseSetupInProgress) {
        if (millis() - firebaseSetupTimer >= 100) {
            firebaseSetupTimer = millis();
            
            if (nonBlockingFirebaseSetup()) {
                // Firebase setup successful
                completeSystemInitialization(true);
            } else {
                // Handle failed Firebase setup
                firebaseSetupAttempts--;
                if (firebaseSetupAttempts <= 0) {
                    Serial.println("❌ Firebase setup failed after multiple attempts!");
                    Serial.println("Continuing with local operations only");
                    firebaseSetupInProgress = false;
                    completeSystemInitialization(false);
                }
            }
        }
    }
    
    // Return whether initialization is complete
    return systemInitialized;
}

// Completes system initialization and sets the system state
void completeSystemInitialization(bool withFirebase) {
    systemInitialized = true;
    if (withFirebase) {
        Serial.println("✅ Firebase connected successfully");
        Serial.println("✅ System initialization complete!");
    } else {
        Serial.println("✅ System initialization complete (offline mode)!");
    }
}

// Returns whether the system is initialized
bool isSystemInitialized() {
    return systemInitialized;
} 