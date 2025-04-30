#include "NetworkManager.h"
#include "WiFiSetup.h"
#include "FirebaseHandler.h"

// Initialize network check timers
void setupNetworkChecks() {
    lastFirebaseCheck = millis();
}

// Handles network-related tasks in the main loop
void handleNetworkTasks() {
    // Check WiFi connection
    if (!checkWiFiConnection()) {
        Serial.println("⚠️ WiFi disconnected, continuing with local operations");
        return; // Skip Firebase operations when WiFi is down
    }
    
    // Periodically check Firebase connection
    if (millis() - lastFirebaseCheck >= FIREBASE_CHECK_INTERVAL) {
        checkFirebaseConnection();
        lastFirebaseCheck = millis();
        
        if (!Firebase.ready()) {
            Serial.println("⚠️ Firebase unavailable, continuing with local operations");
            return;
        }
        
        // Perform Firebase operations
        checkPeriodicWiFiCredentials();
        processFirebaseQueue();
    }
} 