#include "WiFiSetup.h"
#include "secrets.h"
#include "FirebaseHandler.h"
#include <WiFi.h>
#include <Preferences.h>

// External declarations for Firebase variables
extern FirebaseData fbdo;
extern const char* const DEVICE_PATH;
extern const char* const WIFI_NODE;
extern String deviceId;

// Constants for configuration
#define WIFI_CONNECT_TIMEOUT_MS 10000    // 10 seconds timeout for connection
#define WIFI_MAX_FAILED_ATTEMPTS 3       // Max failed attempts before resetting
#define WIFI_CHECK_INTERVAL_MS 30000     // Check connection every 30 seconds
#define WIFI_CREDENTIALS_NAMESPACE "wifi"
#define WIFI_PREF_SSID "ssid"
#define WIFI_PREF_PASS "pass"
#define WIFI_PREF_FAILED "failedAttempts"
#define FLASH_WRITE_DELAY_MS 100         // Delay after preferences operations

// Define the global wifiPrefs instance
Preferences wifiPrefs;

// Forward declarations for helper functions
bool updateFirebaseWiFiStatus(bool connected);
void loadWiFiCredentials(String &ssid, String &password, int &failedAttempts);
void saveWiFiCredentials(const String &ssid, const String &password);
void saveFailedAttempts(int failedAttempts);

bool setupWiFi() {
    Serial.println("📡 Setting up WiFi...");
    WiFi.mode(WIFI_STA);
    
    // Load credentials
    String savedSSID, savedPass;
    int failedAttempts = 0;
    loadWiFiCredentials(savedSSID, savedPass, failedAttempts);
    
    // Use default credentials if none are stored
    if (savedSSID.length() == 0 || savedPass.length() == 0) {
        Serial.println("⚠️ No WiFi credentials found in flash. Using default credentials...");
        savedSSID = WIFI_SSID;
        savedPass = WIFI_PASSWORD;
    }

    Serial.print("📡 Attempting to connect to: ");
    Serial.println(savedSSID);
    
    // Disconnect from old network and begin connection with loaded credentials
    WiFi.disconnect(true);
    delay(500);
    WiFi.begin(savedSSID.c_str(), savedPass.c_str());
    
    // Connection attempt with timeout
    unsigned long startAttemptTime = millis();
    while (WiFi.status() != WL_CONNECTED && 
           millis() - startAttemptTime < WIFI_CONNECT_TIMEOUT_MS) {
        delay(500);
        Serial.print(".");
    }
    Serial.println();
    
    // Handle successful connection
    if (WiFi.status() == WL_CONNECTED) {
        Serial.print("✅ Connected to WiFi. IP: ");
        Serial.println(WiFi.localIP());
        
        // Reset failure count
        saveFailedAttempts(0);
        
        // Update Firebase status if possible (non-blocking)
        updateFirebaseWiFiStatus(true);
        return true;
    } 
    // Handle connection failure
    else {
        Serial.println("❌ Failed to connect to WiFi");
        
        // Increment and check failed attempts
        failedAttempts++;
        saveFailedAttempts(failedAttempts);
        
        Serial.print("⚠️ Failed Attempts Count: ");
        Serial.println(failedAttempts);
        
        // Reset credentials after too many failures
        if (failedAttempts >= WIFI_MAX_FAILED_ATTEMPTS) {
            Serial.println("⚠️ Too many failures. Resetting WiFi settings...");
            clearFlashStorage();
            // clearFlashStorage will restart the device
        }
        
        return false;
    }
}

bool checkWiFiConnection() {
    static unsigned long lastCheck = 0;
    
    // Check periodically based on defined interval
    if (millis() - lastCheck >= WIFI_CHECK_INTERVAL_MS) {
        lastCheck = millis();
        
        if (WiFi.status() != WL_CONNECTED) {
            Serial.println("⚠️ WiFi connection lost. Attempting to reconnect...");
            
            // Update Firebase status if possible (non-blocking)
            updateFirebaseWiFiStatus(false);
            
            // Attempt reconnection
            WiFi.disconnect();
            delay(500);
            return setupWiFi();
        }
    }
    
    return WiFi.status() == WL_CONNECTED;
}

bool updateWiFiCredentials(const char* ssid, const char* password) {
    Serial.println("📡 Updating WiFi credentials in Flash...");
    
    if (ssid == nullptr || password == nullptr || strlen(ssid) == 0) {
        Serial.println("❌ Invalid WiFi credentials provided");
        return false;
    }
    
    // Store new credentials
    saveWiFiCredentials(String(ssid), String(password));
    
    // Verify storage
    String checkSSID, checkPass;
    int dummy = 0;
    loadWiFiCredentials(checkSSID, checkPass, dummy);
    
    // Ensure the stored values match what we tried to save
    bool credentialsMatch = (String(ssid) == checkSSID && String(password) == checkPass);
    
    if (!credentialsMatch) {
        Serial.println("❌ ERROR: Failed to save WiFi credentials correctly!");
        return false;
    }
    
    Serial.println("✅ WiFi credentials updated in flash");
    
    // Reconnect with new credentials
    Serial.println("📡 Reconnecting with new credentials...");
    WiFi.disconnect(true);
    delay(500);
    return setupWiFi();
}

void clearFlashStorage() {
    Serial.println("🧹 Clearing WiFi flash storage...");
    
    // Safely open, clear, and close preferences
    if (wifiPrefs.begin(WIFI_CREDENTIALS_NAMESPACE, false)) {
        wifiPrefs.clear();
        wifiPrefs.putInt(WIFI_PREF_FAILED, 0);
        wifiPrefs.end();
        
        // Give time for flash write to complete
        delay(FLASH_WRITE_DELAY_MS);
        Serial.println("🧹 Flash storage cleared!");
        
        // Delay before restart to ensure flash writes complete
        delay(500);
        ESP.restart();
    } else {
        Serial.println("❌ Failed to access preferences for clearing");
    }
}

// Helper Functions

void loadWiFiCredentials(String &ssid, String &password, int &failedAttempts) {
    // Safely open preferences
    if (wifiPrefs.begin(WIFI_CREDENTIALS_NAMESPACE, false)) {
        // Read values
        failedAttempts = wifiPrefs.getInt(WIFI_PREF_FAILED, 0);
        ssid = wifiPrefs.getString(WIFI_PREF_SSID, "");
        password = wifiPrefs.getString(WIFI_PREF_PASS, "");
        wifiPrefs.end();
        
        // Log credentials (for development only - remove in production)
        Serial.print("📡 Loaded SSID from flash: ");
        Serial.println(ssid);
        Serial.print("🔑 Loaded Password from flash: ");
        Serial.println("********"); // Don't print actual password
        Serial.print("🔄 Previous failed WiFi attempts: ");
        Serial.println(failedAttempts);
    } else {
        Serial.println("❌ Failed to access preferences for reading");
    }
}

void saveWiFiCredentials(const String &ssid, const String &password) {
    // Safely handle preferences
    if (wifiPrefs.begin(WIFI_CREDENTIALS_NAMESPACE, false)) {
        // Store SSID and password
        wifiPrefs.putString(WIFI_PREF_SSID, ssid);
        wifiPrefs.putString(WIFI_PREF_PASS, password);
        wifiPrefs.end();
        
        // Small delay to ensure flash write completes
        delay(FLASH_WRITE_DELAY_MS);
        
        Serial.println("📡 Saved WiFi credentials to flash");
    } else {
        Serial.println("❌ Failed to access preferences for saving credentials");
    }
}

void saveFailedAttempts(int failedAttempts) {
    if (wifiPrefs.begin(WIFI_CREDENTIALS_NAMESPACE, false)) {
        wifiPrefs.putInt(WIFI_PREF_FAILED, failedAttempts);
        wifiPrefs.end();
        delay(FLASH_WRITE_DELAY_MS);
    } else {
        Serial.println("❌ Failed to access preferences for saving attempt count");
    }
}

bool updateFirebaseWiFiStatus(bool connected) {
    // Don't attempt Firebase operations if not ready
    if (!Firebase.ready()) {
        Serial.println("⚠️ Firebase not ready, WiFi status update skipped");
        return false;
    }
    
    // Construct the path and attempt to update
    String path = String(DEVICE_PATH) + deviceId + WIFI_NODE + "/connected";
    bool success = Firebase.RTDB.setBool(&fbdo, path.c_str(), connected);
    
    if (success) {
        Serial.print("✅ Updated WiFi connection status to '");
        Serial.print(connected ? "connected" : "disconnected");
        Serial.println("' in Firebase");
    } else {
        Serial.print("❌ Failed to update Firebase: ");
        Serial.println(fbdo.errorReason().c_str());
    }
    
    return success;
}