#include "WiFiSetup.h"
#include "secrets.h"
#include "FirebaseHandler.h"
#include "RGBLed.h"
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

const char* NTP_SERVERS[] = {
    "time.google.com",
    "pool.ntp.org",
    "time.cloudflare.com"
};
const int NTP_SERVER_COUNT = sizeof(NTP_SERVERS) / sizeof(NTP_SERVERS[0]);
const long GMT_OFFSET_SEC = 0;
const int DAYLIGHT_OFFSET_SEC = 0;

// Global state variables to track flash write operations
unsigned long flashWriteStartTime = 0;
bool flashWriteInProgress = false;
bool flashOperationComplete = false;

// Forward declarations for helper functions
bool updateFirebaseWiFiStatus(bool connected);
void loadWiFiCredentials(String &ssid, String &password, int &failedAttempts);
void saveWiFiCredentials(const String &ssid, const String &password);
void saveFailedAttempts(int failedAttempts);

void WiFiEventHandler(WiFiEvent_t event) {
    static int attemptCount = 0;
    static unsigned long lastReconnectAttempt = 0;
    const unsigned long delayIntervals[] = {5000, 10000, 30000, 60000};  // 5s, 10s, 30s, 60s
    unsigned long now = millis();
    switch (event) {
        case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
            Serial.println("WiFi lost connection");
            setLEDStatus(STATUS_OFFLINE);          
            if (now - lastReconnectAttempt >= delayIntervals[min(attemptCount, 3)]) {
                Serial.println("🔄 Attempting WiFi reconnection...");
                WiFi.reconnect();
                lastReconnectAttempt = now;
                attemptCount++;
            }
            break;

        case ARDUINO_EVENT_WIFI_STA_GOT_IP:
            Serial.println("✅ WiFi connected!");
            Serial.print("IP address: ");
            Serial.println(WiFi.localIP());
            setLEDStatus(STATUS_ONLINE);
            updateFirebaseWiFiStatus(true);
            attemptCount = 0;  // Reset attempt counter
            break;
    }
}

// This is the original blocking function, kept for compatibility but
// redirected to use the non-blocking version instead
bool setupWiFi(bool fromCredentialUpdate) {
    // Instead of implementing the blocking setup,
    // we'll reset the non-blocking state machine and notify the caller
    // that they should use the non-blocking version
    
    Serial.println("📡 Redirecting to non-blocking WiFi setup...");
    resetNonBlockingWiFiSetup();
    
    // Return success if already connected
    return WiFi.status() == WL_CONNECTED;
}

unsigned long lastReconnectAttempt = 0;
int reconnectInterval = 1000; // Start with 1 second
const int maxReconnectInterval = 60000; // Max 1 minute between attempts

// State variables for reconnection
enum WiFiReconnectState {
    RECONNECT_IDLE,
    RECONNECT_DISCONNECT,
    RECONNECT_WAITING,
    RECONNECT_CONNECT
};
WiFiReconnectState reconnectState = RECONNECT_IDLE;
unsigned long reconnectStateTime = 0;

// Update WiFi check function to be truly non-blocking with reconnects
bool checkWiFiConnection() {
    static unsigned long currentMillis = 0;
    
    if (WiFi.status() == WL_CONNECTED) {
        reconnectInterval = 1000; // Reset interval
        reconnectState = RECONNECT_IDLE; // Reset state
        return true;
    }
    
    // WiFi not connected, handle reconnection state machine
    currentMillis = millis();
    
    switch (reconnectState) {
        case RECONNECT_IDLE:
            // Check if it's time to attempt reconnection
            if (currentMillis - lastReconnectAttempt > reconnectInterval) {
                Serial.print("Attempting reconnection (");
                Serial.print(reconnectInterval / 1000);
                Serial.println("s interval)");
                
                lastReconnectAttempt = currentMillis;
                reconnectState = RECONNECT_DISCONNECT;
                reconnectStateTime = currentMillis;
                
                // Increase the interval for next attempt
                reconnectInterval = min(reconnectInterval * 2, maxReconnectInterval);
                setLEDStatus(STATUS_OFFLINE);
            }
            break;
            
        case RECONNECT_DISCONNECT:
            // Disconnect before reconnecting
            if (reconnectInterval >= maxReconnectInterval) {
                Serial.println("🚨 Too many failures, attempting reconnection...");
                WiFi.disconnect(false); // Don't erase settings
            } else {
                WiFi.disconnect(false);
            }
            reconnectState = RECONNECT_WAITING;
            reconnectStateTime = currentMillis;
            break;
            
        case RECONNECT_WAITING:
            // Wait a bit before reconnecting (non-blocking)
            if (currentMillis - reconnectStateTime >= 500) {
                reconnectState = RECONNECT_CONNECT;
                WiFi.reconnect();
                reconnectStateTime = currentMillis;
            }
            break;
            
        case RECONNECT_CONNECT:
            // Check if we've connected yet or wait for timeout
            if (WiFi.status() == WL_CONNECTED) {
                Serial.println("✅ WiFi reconnected successfully");
                reconnectState = RECONNECT_IDLE;
                return true;
            } else if (currentMillis - reconnectStateTime >= 5000) {
                // Timeout after 5 seconds of trying to reconnect
                Serial.println("⚠️ Reconnection attempt timed out");
                reconnectState = RECONNECT_IDLE;
            }
            break;
    }
    
    // If we're still disconnected, return false
    return false;
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
    
    // Use non-blocking WiFi reconnection instead of blocking approach
    Serial.println("📡 Starting WiFi reconnection with new credentials...");
    WiFi.disconnect(true);
    // Initialize non-blocking WiFi setup with new credentials
    resetNonBlockingWiFiSetup();
    return true; // Return success - actual connection will happen in non-blocking loop
}

// State enumeration for clearFlashStorage
enum FlashClearState {
    CLEAR_INIT,
    CLEAR_STARTED,
    CLEAR_WAITING,
    CLEAR_COMPLETE,
    CLEAR_RESTART_WAITING,
    CLEAR_RESTART
};

static FlashClearState flashClearState = CLEAR_INIT;
static unsigned long flashClearStateTime = 0;
static bool skipFlashRestart = false;

void clearFlashStorage(bool skipRestart) {
    // Initialize state machine if not started
    if (flashClearState == CLEAR_INIT) {
        Serial.println("🧹 Starting flash clear operation...");
        flashClearState = CLEAR_STARTED;
        flashClearStateTime = millis();
        skipFlashRestart = skipRestart;
    }
    
    // The rest of the process will be handled by checkFlashClearStatus()
}

// This should be called from the main loop to advance the flash clear process
bool checkFlashClearStatus() {
    unsigned long currentTime = millis();
    
    switch (flashClearState) {
        case CLEAR_INIT:
            // Nothing to do until clearFlashStorage is called
            return false;
            
        case CLEAR_STARTED:
            // Step 1: Clear the preferences
            Serial.println("🧹 Clearing WiFi credentials from flash...");
            wifiPrefs.begin(WIFI_CREDENTIALS_NAMESPACE, false);
            wifiPrefs.clear();
            wifiPrefs.putInt(WIFI_PREF_FAILED, 0);
            wifiPrefs.end();
            
            // Move to waiting state
            flashClearState = CLEAR_WAITING;
            flashClearStateTime = currentTime;
            return false;
            
        case CLEAR_WAITING:
            // Step 2: Wait for flash write to complete
            if (currentTime - flashClearStateTime >= FLASH_WRITE_DELAY_MS) {
                Serial.println("✅ Flash storage cleared!");
                flashClearState = CLEAR_COMPLETE;
                flashClearStateTime = currentTime;
            }
            return false;
            
        case CLEAR_COMPLETE:
            // Step 3: Decide if we need to restart
            if (skipFlashRestart) {
                // No restart needed, complete the process
                flashClearState = CLEAR_INIT;
                return true;
            } else {
                // Prepare for restart
                Serial.println("🔄 Restarting device in 3 seconds...");
                setLEDStatus(STATUS_ERROR);  // Indicate that a reset is happening
                flashClearState = CLEAR_RESTART_WAITING;
                flashClearStateTime = currentTime;
            }
            return false;
            
        case CLEAR_RESTART_WAITING:
            // Step 4: Wait before restarting
            if (currentTime - flashClearStateTime >= 3000) {
                flashClearState = CLEAR_RESTART;
                Serial.println("🔁 Restarting now...");
                return true;
            }
            return false;
            
        case CLEAR_RESTART:
            // Step 5: Perform the restart
            ESP.restart();
            return true; // Never reached
    }
    
    return false;
}

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
        
        // Mark the beginning of flash write operation
        flashWriteStartTime = millis();
        flashWriteInProgress = true;
        flashOperationComplete = false;
        
        Serial.println("📡 Saving WiFi credentials to flash");
    } else {
        Serial.println("❌ Failed to access preferences for saving credentials");
    }
}

// Check if flash operation is complete (non-blocking)
bool isFlashWriteComplete() {
    if (!flashWriteInProgress) {
        return true; // No write in progress
    }
    
    if (millis() - flashWriteStartTime >= FLASH_WRITE_DELAY_MS) {
        // Flash write should be complete
        flashWriteInProgress = false;
        flashOperationComplete = true;
        return true;
    }
    
    return false; // Still waiting for flash write to complete
}

void saveFailedAttempts(int failedAttempts) {
    if (wifiPrefs.begin(WIFI_CREDENTIALS_NAMESPACE, false)) {
        wifiPrefs.putInt(WIFI_PREF_FAILED, failedAttempts);
        wifiPrefs.end();
        
        // Mark the beginning of flash write operation
        flashWriteStartTime = millis();
        flashWriteInProgress = true;
        flashOperationComplete = false;
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
    if (deviceId.isEmpty()) {
        Serial.println("❌ ERROR: Device ID is missing, cannot update Firebase!");
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

void performTimeSync() {
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("❌ Cannot sync time: WiFi not connected");
        return;
    }

    Serial.println("\n🕒 Attempting NTP Time Synchronization...");

    bool syncSuccessful = false;
    static int serverIndex = 0;
    static unsigned long startAttemptTime = 0;
    static bool attemptInProgress = false;
    
    if (!attemptInProgress) {
        Serial.print("- Trying NTP Server: ");
        Serial.println(NTP_SERVERS[serverIndex]);
        configTime(GMT_OFFSET_SEC, DAYLIGHT_OFFSET_SEC, NTP_SERVERS[serverIndex]);
        startAttemptTime = millis();
        attemptInProgress = true;
    }
    
    // Check if time sync completed or timed out
    if (isTimeSynchronized() || millis() - startAttemptTime >= 10000) {
        if (isTimeSynchronized()) {
            syncSuccessful = true;
        } else {
            // Try next server
            serverIndex = (serverIndex + 1) % NTP_SERVER_COUNT;
            if (serverIndex == 0) {
                // We've tried all servers
                attemptInProgress = false;
            } else {
                // Try next server
                Serial.print("- Trying NTP Server: ");
                Serial.println(NTP_SERVERS[serverIndex]);
                configTime(GMT_OFFSET_SEC, DAYLIGHT_OFFSET_SEC, NTP_SERVERS[serverIndex]);
                startAttemptTime = millis();
            }
        }
    }

    if (syncSuccessful) {
        Serial.println("✅ Time Synchronization Successful!");
        
        // Print current time
        struct timeval tv;
        gettimeofday(&tv, NULL);
        unsigned long long epochMilliseconds = (tv.tv_sec * 1000LL) + (tv.tv_usec / 1000);
        Serial.print("Current Time (ms): ");
        Serial.println(epochMilliseconds);
        
        // Reset state for next time
        serverIndex = 0;
        attemptInProgress = false;
    } else if (!attemptInProgress) {
        Serial.println("❌ Time Synchronization Failed");
        
        // Print diagnostics
        Serial.println("Time Sync Diagnostics:");
        Serial.print("- WiFi Status: ");
        switch(WiFi.status()) {
            case WL_CONNECTED: Serial.println("Connected"); break;
            case WL_NO_SSID_AVAIL: Serial.println("No SSID Available"); break;
            case WL_CONNECT_FAILED: Serial.println("Connection Failed"); break;
            case WL_DISCONNECTED: Serial.println("Disconnected"); break;
            default: Serial.println("Unknown Status"); break;
        }

        Serial.println("Possible Causes:");
        Serial.println("1. Firewall blocking NTP");
        Serial.println("2. Unstable connection");
        Serial.println("3. DNS resolution issues");
        Serial.println("4. NTP server unavailability");
    }
}

unsigned long long isTimeSynchronized() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    // Convert to milliseconds
    unsigned long long epochMilliseconds = (tv.tv_sec * 1000LL) + (tv.tv_usec / 1000);

    // Convert seconds to check year validity
    struct tm timeinfo;
    localtime_r(&tv.tv_sec, &timeinfo);

    // Return 0 if time is not valid (before 2021), or the timestamp if valid
    return (timeinfo.tm_year > (2021 - 1900)) ? epochMilliseconds : 0;
}

// Non-blocking WiFi setup state machine
enum WiFiSetupState {
    SETUP_INIT,
    SETUP_LOAD_CREDENTIALS,
    SETUP_DISCONNECT,
    SETUP_CONNECT,
    SETUP_WAIT_CONNECTION,
    SETUP_FALLBACK_DISCONNECT,
    SETUP_FALLBACK_CONNECT,
    SETUP_FALLBACK_WAIT,
    SETUP_COMPLETE,
    SETUP_FAILED
};

static WiFiSetupState wifiSetupState = SETUP_INIT;
static unsigned long setupStateTime = 0;
static String wifiSetupSSID = "";
static String wifiSetupPass = "";
static int wifiSetupFailedAttempts = 0;
static unsigned long setupStartTime = 0;

void resetNonBlockingWiFiSetup() {
    wifiSetupState = SETUP_INIT;
    setupStateTime = 0;
    wifiSetupSSID = "";
    wifiSetupPass = "";
}

int nonBlockingWiFiSetup() {
    unsigned long currentTime = millis();
    
    // Initialize timers on first entry
    if (wifiSetupState == SETUP_INIT) {
        setupStartTime = currentTime;
        setupStateTime = currentTime;
    }
    
    // Safety timeout for entire setup process (10 seconds)
    if (currentTime - setupStartTime > 10000 && wifiSetupState != SETUP_COMPLETE) {
        Serial.println("❌ WiFi setup timed out");
        wifiSetupState = SETUP_FAILED;
    }
    
    // State machine implementation
    switch (wifiSetupState) {
        case SETUP_INIT:
            WiFi.onEvent(WiFiEventHandler);
            Serial.println("📡 Starting non-blocking WiFi setup...");
            WiFi.mode(WIFI_STA);
            wifiSetupState = SETUP_LOAD_CREDENTIALS;
            setupStateTime = currentTime;
            break;
            
        case SETUP_LOAD_CREDENTIALS:
            // Load credentials - minimal blocking operation
            loadWiFiCredentials(wifiSetupSSID, wifiSetupPass, wifiSetupFailedAttempts);
            
            // Use default credentials if none are stored
            if (wifiSetupSSID.length() == 0 || wifiSetupPass.length() == 0) {
                Serial.println("⚠️ No WiFi credentials found in flash. Using default credentials...");
                wifiSetupSSID = WIFI_SSID;
                wifiSetupPass = WIFI_PASSWORD;
            }
            
            setLEDStatus(STATUS_OFFLINE);
            Serial.print("📡 Will connect to: ");
            Serial.println(wifiSetupSSID);
            
            wifiSetupState = SETUP_DISCONNECT;
            setupStateTime = currentTime;
            break;
            
        case SETUP_DISCONNECT:
            // Disconnect from any existing networks
            WiFi.disconnect(true);
            wifiSetupState = SETUP_CONNECT;
            setupStateTime = currentTime;
            
            // Small state machine pause to ensure disconnection completes
            break;
            
        case SETUP_CONNECT:
            // Only attempt connection after disconnect has time to complete
            if (currentTime - setupStateTime >= 500) {
                WiFi.begin(wifiSetupSSID.c_str(), wifiSetupPass.c_str());
                Serial.print("📡 Connecting to WiFi");
                wifiSetupState = SETUP_WAIT_CONNECTION;
                setupStateTime = currentTime;
            }
            break;
            
        case SETUP_WAIT_CONNECTION:
            // Non-blocking connection check with progress indicator
            if (currentTime - setupStateTime >= 300) {
                Serial.print(".");
                setupStateTime = currentTime;
            }
            
            // Check if connected
            if (WiFi.status() == WL_CONNECTED) {
                Serial.println();
                Serial.print("✅ Connected to WiFi. IP: ");
                Serial.println(WiFi.localIP());
                
                // Reset failure count
                saveFailedAttempts(0);
                
                // Update Firebase status (non-blocking)
                updateFirebaseWiFiStatus(true);
                
                wifiSetupState = SETUP_COMPLETE;
                break;
            }
            
            // Check for timeout
            if (currentTime - setupStateTime > 5000) {
                Serial.println();
                Serial.println("❌ Connection timeout with primary credentials");
                
                // Increment and check failed attempts
                wifiSetupFailedAttempts++;
                saveFailedAttempts(wifiSetupFailedAttempts);
                
                Serial.print("⚠️ Failed Attempts Count: ");
                Serial.println(wifiSetupFailedAttempts);
                
                // Try fallback if too many failures
                if (wifiSetupFailedAttempts >= WIFI_MAX_FAILED_ATTEMPTS) {
                    Serial.println("⚠️ Too many failures. Attempting fallback network...");
                    wifiSetupState = SETUP_FALLBACK_DISCONNECT;
                } else {
                    wifiSetupState = SETUP_FAILED;
                }
                setupStateTime = currentTime;
            }
            break;
            
        case SETUP_FALLBACK_DISCONNECT:
            WiFi.disconnect();
            wifiSetupState = SETUP_FALLBACK_CONNECT;
            setupStateTime = currentTime;
            break;
            
        case SETUP_FALLBACK_CONNECT:
            // Wait after disconnect
            if (currentTime - setupStateTime >= 500) {
                Serial.println("📡 Trying default credentials...");
                WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
                wifiSetupState = SETUP_FALLBACK_WAIT;
                setupStateTime = currentTime;
            }
            break;
            
        case SETUP_FALLBACK_WAIT:
            // Non-blocking connection check with progress indicator
            if (currentTime - setupStateTime >= 300) {
                Serial.print(".");
                setupStateTime = currentTime;
            }
            
            // Check if connected
            if (WiFi.status() == WL_CONNECTED) {
                Serial.println();
                Serial.print("✅ Connected to default WiFi. IP: ");
                Serial.println(WiFi.localIP());
                
                // Default credentials worked, save them
                saveWiFiCredentials(WIFI_SSID, WIFI_PASSWORD);
                saveFailedAttempts(0);
                
                wifiSetupState = SETUP_COMPLETE;
                break;
            }
            
            // Check for timeout with fallback credentials
            if (currentTime - setupStateTime > 5000) {
                Serial.println();
                Serial.println("❌ Connection timeout with fallback credentials");
                wifiSetupState = SETUP_FAILED;
                setupStateTime = currentTime;
            }
            break;
            
        case SETUP_COMPLETE:
            // WiFi setup completed successfully
            return WIFI_SETUP_COMPLETE;
            
        case SETUP_FAILED:
            // WiFi setup failed
            return WIFI_SETUP_FAILED;
    }
    
    // Still in progress
    return WIFI_SETUP_IN_PROGRESS;
}
