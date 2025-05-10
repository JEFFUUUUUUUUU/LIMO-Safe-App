#include "FirebaseHandler.h"
#include "secrets.h"
#include "OTPVerifier.h"
#include "UserManager.h"
#include "NanoCommunicator.h"
#include "RGBLed.h"
#include "WiFiSetup.h"
#include <Preferences.h>

// Firebase objects
FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;
Preferences preferences;

// Firebase paths and IDs
const char* const DEVICE_PATH = "devices/";
const char* const USERS_PATH = "users/";
const char* const WIFI_NODE = "/wifi";
const char* const REGISTERED_USERS_NODE = "/registeredUsers";
const char* const APPROVED_USERS_NODE = "/approvedUsers";
const char* const LAST_VERIFICATION_NODE = "/lastVerification";
const char* const OTP_NODE = "/otp";
String deviceId;

// Add to the existing global variables at the top
unsigned long lastFirebaseReconnectAttempt = 0;
int firebaseReconnectInterval = 2000; // Start with 2 seconds
const int maxFirebaseReconnectInterval = 60000; // Max 1 minute between attempts

// Track connection status
bool wasFirebaseConnected = false;

// Improved Firebase connection checking function
bool checkFirebaseConnection() {
    // Static variables for connection management
    static unsigned long lastFirebaseReconnectAttempt = 0;
    static int firebaseReconnectInterval = 2000; // Start with 2 seconds
    static bool wasFirebaseConnected = false;
    static int firebaseFailedAttempts = 0;
    const int maxFirebaseReconnectInterval = 60000; // Max 1 minute between attempts
    const int maxFirebaseFailedAttempts = 3; // Max failures before WiFi reset
    
    // Variables for current state
    bool wifiConnected = WiFi.isConnected();
    bool firebaseResponsive = false;
    unsigned long currentMillis = millis();

    // Step 1: Check if WiFi is connected
    if (!wifiConnected) {
        if (wasFirebaseConnected) {
            Serial.println("❌ WiFi disconnected, Firebase unavailable");
            setLEDStatus(STATUS_OFFLINE);
            wasFirebaseConnected = false;
        }
        return false;
    }

    // Step 2: Use DNS ping to check internet connectivity
    IPAddress ip;
    bool internetAvailable = (WiFi.hostByName("8.8.8.8", ip) == 1);
    
    if (!internetAvailable) {
        if (wasFirebaseConnected) {
            Serial.println("❌ Internet not available, Firebase cannot connect");
            setLEDStatus(STATUS_OFFLINE);
            wasFirebaseConnected = false;
        }
        return false;
    }

    // Step 3: Verify Firebase connection if internet is available
    if (Firebase.ready()) {
        // Only do the ping test if we have internet
        if (Firebase.RTDB.getInt(&fbdo, "/status/pingTest")) {
            firebaseResponsive = true;
            firebaseFailedAttempts = 0; // Reset failed attempts counter on success
        } else {
            firebaseResponsive = false;
            Serial.println("❌ Firebase ping test failed");
        }
    } else {
        firebaseResponsive = false;
    }

    // Handle connection state changes
    bool currentlyConnected = wifiConnected && internetAvailable && firebaseResponsive;
    if (currentlyConnected != wasFirebaseConnected) {
        if (currentlyConnected) {
            Serial.println("✅ Firebase connected");
            firebaseReconnectInterval = 2000; // Reset reconnect interval on success
            firebaseFailedAttempts = 0; // Reset failure count
            updateDeviceStatus(true, false, false);
        } else {
            Serial.println("❌ Firebase disconnected");
            setLEDStatus(STATUS_OFFLINE);
            // Print more detailed diagnostics
            if (!internetAvailable) {
                Serial.println("   - Internet not available despite WiFi connection");
            } else if (!firebaseResponsive) {
                Serial.println("   - Firebase not responsive");
            }
        }
        wasFirebaseConnected = currentlyConnected;
    }

    // Attempt reconnection if needed with exponential backoff
    if (!currentlyConnected && (currentMillis - lastFirebaseReconnectAttempt > firebaseReconnectInterval)) {
        Serial.println("🔄 Attempting Firebase reconnection...");
        lastFirebaseReconnectAttempt = currentMillis;
        
        // Full reconnection sequence
        bool reconnectSuccess = false;
        
        // Step 1: Reset Firebase configuration
        Firebase.reset(&config);
        
        // Step 2: Re-initialize Firebase with the current config
        // FIXED: Removed config.cert.verify = false line as it's not a valid property
        Firebase.begin(&config, &auth);
        Firebase.reconnectWiFi(true);
        
        // Step 3: Reset buffer sizes which can help with connection issues
        //fbdo.setBSSLBufferSize(4096, 2048); // Use larger buffers for reconnection
        // REMOVED: setSecure() call as it's a private method in the FirebaseData class
        
        // Step 4: Verify the reconnection worked
        delay(500); // Brief pause to allow connection to establish
        if (Firebase.ready() && Firebase.RTDB.getInt(&fbdo, "/status/pingTest")) {
            Serial.println("✅ Firebase reconnected successfully");
            reconnectSuccess = true;
            firebaseReconnectInterval = 2000; // Reset backoff timer
            firebaseFailedAttempts = 0; // Reset failure count
            wasFirebaseConnected = true;
            updateDeviceStatus(true, false, false);
            setLEDStatus(STATUS_ONLINE); // Or whatever your normal status is
        } else {
            // Increment failed attempts counter
            firebaseFailedAttempts++;
            
            // Implement exponential backoff with jitter
            firebaseReconnectInterval = min(
                (int)(firebaseReconnectInterval * 1.5 + random(500)), 
                maxFirebaseReconnectInterval
            );
            
            Serial.print("❌ Firebase reconnection failed. Attempt #");
            Serial.print(firebaseFailedAttempts);
            Serial.print(" of ");
            Serial.print(maxFirebaseFailedAttempts);
            Serial.print(". Next attempt in ");
            Serial.print(firebaseReconnectInterval / 1000);
            Serial.println(" seconds");
            
            // If we've failed too many times in a row, try resetting WiFi connection
            if (firebaseFailedAttempts >= maxFirebaseFailedAttempts) {
                Serial.println("⚠️ Too many Firebase failures. Attempting WiFi reset...");
                firebaseFailedAttempts = 0; // Reset counter
                
                // Try to use default credentials as a fallback
                String savedSSID, savedPass;
                int failedAttempts = 0;
                
                // Load current WiFi credentials
                Preferences wifiPrefs;
                if (wifiPrefs.begin("wifi", false)) {
                    savedSSID = wifiPrefs.getString("ssid", "");
                    savedPass = wifiPrefs.getString("pass", "");
                    wifiPrefs.end();
                }
                
                // Check if we're already using default credentials
                if (savedSSID == WIFI_SSID && savedPass == WIFI_PASSWORD) {
                    Serial.println("Already using default credentials, trying full network reset");
                    WiFi.disconnect(true);
                    delay(1000);
                    WiFi.reconnect();
                } else {
                    // Fall back to default credentials
                    Serial.println("Attempting to connect with default credentials");
                    updateWiFiCredentials(WIFI_SSID, WIFI_PASSWORD);
                }
                
                // Reset Firebase reconnect interval
                firebaseReconnectInterval = 5000; // A bit longer for WiFi to stabilize
            }
        }
    }

    return currentlyConnected;
}

bool setupFirebase() {
    // Enhanced timeout and connection settings
    config.timeout.serverResponse = 30000; // 30 seconds
    Serial.println("🔥 Setting up Firebase...");

    // Get device ID from preferences or generate from MAC
    preferences.begin("device", false);
    deviceId = preferences.getString("id", "");

    if (deviceId.isEmpty()) {
        uint8_t mac[6];
        WiFi.macAddress(mac);
        deviceId = String(mac[0], HEX) + String(mac[1], HEX) + String(mac[2], HEX) +
                  String(mac[3], HEX) + String(mac[4], HEX) + String(mac[5], HEX);
        deviceId.toUpperCase();
       
        preferences.putString("id", deviceId);
        Serial.print("📱 Generated Device ID from MAC: ");
        Serial.println(deviceId);
    } else {
        Serial.print("📱 Using stored Device ID: ");
        Serial.println(deviceId);
    }
    preferences.end();

    // Validate Firebase configuration
    if (strlen(FIREBASE_HOST) == 0 || strlen(FIREBASE_AUTH) == 0) {
        Serial.println("❌ Firebase host or auth token not configured!");
        return false;
    }

    // Enhanced SSL/Network Configuration
    config.database_url = FIREBASE_HOST;
    config.signer.tokens.legacy_token = FIREBASE_AUTH;

    // Increase timeout and connection settings
    config.timeout.socketConnection = 10 * 1000;  // 10 seconds socket connection timeout
    config.timeout.serverResponse = 10 * 1000;    // 10 seconds server response timeout
    config.timeout.rtdbKeepAlive = 45 * 1000;     // 45 seconds keep-alive

    // Initialize Firebase connection with retries
    int initAttempts = 5;
    unsigned long startAttemptTime = millis();
    const unsigned long MAX_ATTEMPT_TIME = 60000;  // 1 minute total attempt time

    while (initAttempts > 0 && (millis() - startAttemptTime < MAX_ATTEMPT_TIME)) {
        Firebase.reset(&config);
        Firebase.begin(&config, &auth);
        Firebase.reconnectWiFi(true);

        // Apply SSL Fix for Firebase Connectivity
        fbdo.setBSSLBufferSize(4096, 2048); // Increase SSL buffer size

        // Set Firebase read timeout and write limit
        Firebase.RTDB.setReadTimeout(&fbdo, 1000 * 60);  // 1-minute read timeout
        Firebase.RTDB.setwriteSizeLimit(&fbdo, "small"); // Use correct function name

        // Check connection status with more detailed logging
        if (isFirebaseReady()) {
            Serial.println("✅ Firebase Connected Successfully!");
            break;
        }

        // Detailed error logging
        Serial.print("🚫 Firebase Connection Error: ");
        //Serial.println(fbdo.errorReason().c_str());

        Serial.print("🔄 Firebase initialization attempt failed. Retries left: ");
        Serial.println(initAttempts - 1);

        initAttempts--;
        delay(5000);  // Increased delay between attempts
    }

    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase initialization failed after multiple attempts!");
        Serial.print("Firebase Host: ");
        Serial.println(FIREBASE_HOST);
        Serial.print("Network Status: ");
        Serial.println(WiFi.status() == WL_CONNECTED ? "Connected" : "Disconnected");
        return false;
    }

    // Initialize device data in Firebase
    String path = String(DEVICE_PATH) + deviceId;
    FirebaseJson json;

    // First try to get existing data
    bool existingData = Firebase.RTDB.getJSON(&fbdo, path.c_str());
    if (existingData) {
        // If we got existing data, parse it
        json = fbdo.jsonObject();
        Serial.println("✅ Retrieved existing device data");
    }

    // Always ensure these basic fields exist
    json.set("id", deviceId);
    json.set("status/online", true);

    // Initialize WiFi node if it doesn't exist
    FirebaseJsonData result;
    if (!json.get(result, "wifi")) {
        FirebaseJson wifiJson;
        wifiJson.set("connected", false);
        json.set("wifi", wifiJson);
    }

    // Use updateNode instead of setJSON to preserve existing data
    if (Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
        Serial.println("✅ Device data initialized in Firebase");
    } else {
        Serial.println("❌ Failed to initialize device data");
        return false;
    }

    // Update device status
    if (updateDeviceStatus(true, false, false)) {

    // Now that we're connected, update WiFi status to "true"
    String wifiPath = path + WIFI_NODE + "/connected";
    Firebase.RTDB.setBool(&fbdo, wifiPath.c_str(), true);
    Serial.println("✅ WiFi connection status updated to 'connected'");
    
    // Also update online status to true
    String onlinePath = path + "/status/online";
    Firebase.RTDB.setBool(&fbdo, onlinePath.c_str(), true);
    Serial.println("✅ Online status updated to 'true'");

    return true;
    } else {
        Serial.println("❌ Failed to update device status");
        return false;
    }
}

bool updateDeviceStatus(bool isOnline, bool isLocked, bool isSecure) {
    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase not ready when updating device status");
        return false; 
    }

    String path = String(DEVICE_PATH) + deviceId;  // Firebase path for device status
    
    FirebaseJson json;
    json.set("status/online", isOnline);  // ✅ Update 'online' status
    json.set("status/locked", isLocked);  // ✅ Update 'locked' status
    json.set("status/secure", isSecure);  // ✅ Update 'secure' status
    json.set("status/timestamp", isTimeSynchronized());

    if (!Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
        //Serial.print("❌ Failed to update device status: ");
        //Serial.println(fbdo.errorReason());
        return false;
    }

    /*Serial.print("✅ Device status updated: ");
    Serial.print("Online=");
    Serial.print(isOnline ? "true" : "false");
    Serial.print(", Locked=");
    Serial.print(isLocked ? "true" : "false");
    Serial.print(", Secure=");
    Serial.println(isSecure ? "true" : "false"); */
    return true;
}

bool updateWiFiCredentialsInFirebase(const String& ssid, const String& password) {
    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase not ready when updating WiFi credentials");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId + WIFI_NODE;
    
    FirebaseJson wifiJson;
    wifiJson.set("ssid", ssid);
    wifiJson.set("password", password);
    wifiJson.set("lastUpdated", millis());

    if (!Firebase.RTDB.updateNode(&fbdo, path.c_str(), &wifiJson)) {
        Serial.print("❌ Failed to update WiFi credentials: ");
        //Serial.println(fbdo.errorReason());
        return false;
    }

    Serial.println("✅ WiFi credentials updated in Firebase");
    return true;
}

unsigned long lastWiFiCheckTime = 0;
const unsigned long WIFI_CHECK_INTERVAL = 30000; // Check every 30 seconds

// Enum for WiFi check state machine
enum WiFiCheckState {
    CHECK_IDLE,
    CHECK_FETCH_CREDENTIALS,
    CHECK_COMPARE_CREDENTIALS,
    CHECK_UPDATE_CREDENTIALS,
    CHECK_RECONNECT
};

WiFiCheckState wifiCheckState = CHECK_IDLE;
String newSSID, newPassword;
unsigned long stateEntryTime = 0;

bool checkPeriodicWiFiCredentials() {
    // Time-based check to start the state machine
    if (wifiCheckState == CHECK_IDLE) {
        if (millis() - lastWiFiCheckTime >= WIFI_CHECK_INTERVAL) {
            lastWiFiCheckTime = millis();
            wifiCheckState = CHECK_FETCH_CREDENTIALS;
            stateEntryTime = millis();
            return false; // No action yet, just started the state machine
        }
        return false; // Nothing to do
    }
    
    // Timeout protection - reset state machine if stuck in any state too long
    if (millis() - stateEntryTime > 5000) { // 5 second timeout
        Serial.println("⚠️ WiFi check timeout, resetting state");
        wifiCheckState = CHECK_IDLE;
        return false;
    }
    
    // State machine implementation
    if (wifiCheckState == CHECK_FETCH_CREDENTIALS) {
        // Non-blocking Firebase check
        if (!isFirebaseReady()) {
            wifiCheckState = CHECK_IDLE; // Reset on error
            return false;
        }
        
        String path = String(DEVICE_PATH) + deviceId + WIFI_NODE;
        
        // This is potentially blocking but hard to make non-blocking with Firebase API
        if (!Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
            wifiCheckState = CHECK_IDLE; // Reset on error
            return false;
        }
        
        // Process Firebase response
        FirebaseJson* json = fbdo.jsonObjectPtr();
        if (json == nullptr) {
            wifiCheckState = CHECK_IDLE;
            return false;
        }
        
        // Extract credentials
        FirebaseJsonData ssidData, passwordData;
        json->get(ssidData, "ssid");
        json->get(passwordData, "password");
        
        if (ssidData.success && passwordData.success && 
            ssidData.type == "string" && passwordData.type == "string") {
            newSSID = ssidData.stringValue;
            newPassword = passwordData.stringValue;
            
            if (newSSID.length() > 0 && newPassword.length() > 0) {
                wifiCheckState = CHECK_COMPARE_CREDENTIALS;
                stateEntryTime = millis();
            } else {
                wifiCheckState = CHECK_IDLE; // No credentials available
            }
        } else {
            wifiCheckState = CHECK_IDLE; // Reset on error
        }
        return false;
    }
    
    else if (wifiCheckState == CHECK_COMPARE_CREDENTIALS) {
        // Compare with stored credentials - minimal blocking operation
        Preferences wifiPrefs;
        wifiPrefs.begin("wifi", false);
        String currentSSID = wifiPrefs.getString("ssid", "");
        String currentPass = wifiPrefs.getString("pass", "");
        wifiPrefs.end();
        
        if (currentSSID != newSSID || currentPass != newPassword) {
            // Different credentials, proceed with update
            Serial.println("📡 New WiFi credentials detected");
            wifiCheckState = CHECK_UPDATE_CREDENTIALS;
            stateEntryTime = millis();
        } else {
            // Same credentials, nothing to do
            wifiCheckState = CHECK_IDLE;
        }
        return false;
    }
    
    else if (wifiCheckState == CHECK_UPDATE_CREDENTIALS) {
        // Update Firebase with new credentials
        if (updateWiFiCredentialsInFirebase(newSSID, newPassword)) {
            // Update local storage
            Preferences wifiPrefs;
            wifiPrefs.begin("wifi", false);
            wifiPrefs.putString("ssid", newSSID);
            wifiPrefs.putString("pass", newPassword);
            wifiPrefs.end();
            
            Serial.println("📡 WiFi credentials updated");
            wifiCheckState = CHECK_RECONNECT;
            stateEntryTime = millis();
        } else {
            Serial.println("❌ Failed to update WiFi credentials");
            wifiCheckState = CHECK_IDLE;
        }
        return false;
    }
    
    else if (wifiCheckState == CHECK_RECONNECT) {
        // Perform WiFi reconnection - potentially blocking but necessary
        Serial.println("📡 Reconnecting WiFi with new credentials...");
        WiFi.disconnect();
        delay(100); // Minimal delay to allow disconnect
        WiFi.reconnect();
        
        // Reset state machine
        wifiCheckState = CHECK_IDLE;
        return true; // Signal successful credential update
    }
    
    return false;
}

bool verifyOTP(String receivedOTP) {
    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase not ready for OTP verification");
        return false;
    }
    String userTag = "";
    String userId = "";
    String actualOTP = "";
    String storedOTP;

    if (!OTPVerifier::validateFormat(receivedOTP, userTag, actualOTP)) {
        Serial.println("❌ Invalid OTP format");
        setLEDStatus(STATUS_OTP_ERROR);
        
        // Log OTP format validation failure
        FirebaseJson logEntry;
        logEntry.set("timestamp", isTimeSynchronized());
        logEntry.set("event", "otp_format_invalid");
        logEntry.set("attempted_otp", receivedOTP);
        
        String logPath = String("devices/") + deviceId + "/logs";
        Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
        
        return false;
    }
    
    // Verify OTP and extract user details
    if (!OTPVerifier::verifyOTPCode(fbdo, userTag, receivedOTP, userId, storedOTP)) {
        setLEDStatus(STATUS_OTP_ERROR);
        
        // Log OTP verification failure
        FirebaseJson logEntry;
        logEntry.set("timestamp", isTimeSynchronized());
        logEntry.set("event", "otp_verification_failed");
        logEntry.set("user_tag", userTag);
        
        String logPath = String("devices/") + deviceId + "/logs";
        Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
        
        return false;
    }
    
    // Check if this is first-time pairing
    bool isFirstTimeDevice = UserManager::isFirstTimeUser(fbdo, deviceId);
    Serial.print("Is first time device setup? ");
    Serial.println(isFirstTimeDevice ? "Yes" : "No");
    
    // Check if user is registered (or register them if first time)
    bool isUserRegistered = false;
    
    if (isFirstTimeDevice) {
        // For first time users, register them to the device
        if (!UserManager::registerUserToDevice(fbdo, deviceId, userId, userTag, true)) {
            Serial.println("❌ Failed to register first user to device!");
            
            // Log user registration failure
            FirebaseJson logEntry;
            logEntry.set("timestamp", isTimeSynchronized());
            logEntry.set("event", "first_user_registration_failed");
            logEntry.set("user_id", userId);
            logEntry.set("user_tag", userTag);
            
            String logPath = String("devices/") + deviceId + "/logs";
            Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            
            return false;
        }
        isUserRegistered = true; // They should be registered now
    } else {
        // For existing devices, check if user is already registered
        isUserRegistered = isUserRegisteredToDevice(userTag, userId);
        Serial.print("Is this user registered to device? ");
        Serial.println(isUserRegistered ? "Yes" : "No");
        
        if (!isUserRegistered) {
            Serial.println("❌ User is NOT registered to this device and device already has users!");
            
            // Log unauthorized user attempt
            FirebaseJson logEntry;
            logEntry.set("timestamp", isTimeSynchronized());
            logEntry.set("event", "unauthorized_user_attempt");
            logEntry.set("user_tag", userTag);
            if (userId.length() > 0) {
                logEntry.set("user_id", userId);
            }
            
            String logPath = String("devices/") + deviceId + "/logs";
            Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
            
            return false;
        }
    }
    
    // At this point, the user should be registered - verify one more time
    if (!isUserRegisteredToDevice(userTag, userId)) {
        Serial.println("❌ User registration verification failed!");
        
        // Log user verification failure
        FirebaseJson logEntry;
        logEntry.set("timestamp", isTimeSynchronized());
        logEntry.set("event", "user_verification_failed");
        logEntry.set("user_tag", userTag);
        if (userId.length() > 0) {
            logEntry.set("user_id", userId);
        }
        
        String logPath = String("devices/") + deviceId + "/logs";
        Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), &logEntry);
        
        return false;
    }
    // Determine user role
    String userRole = "user"; // Default role
   
    // Path to the user's role for this device
    String userRolePath = String(USERS_PATH) + userId + "/registeredDevices/" + deviceId + "/role";
   
    // Try to get existing role
    if (Firebase.RTDB.getString(&fbdo, userRolePath.c_str())) {
        if (fbdo.stringData().length() > 0) {
            // User already has a role, preserve it
            userRole = fbdo.stringData();
            Serial.print("ℹ️ Preserving existing user role: ");
            Serial.println(userRole);
        }
    } else {
        // Set special role for first user
        if (isFirstTimeDevice) {
            userRole = "admin";
            Serial.println("ℹ️ Setting admin role for first user");
        } else {
            Serial.println("ℹ️ Setting default user role");
        }
    }

    // Update user's device registration with appropriate role
    if (!UserManager::updateUserDeviceRegistration(fbdo, userId, deviceId, userRole)) {
        Serial.println("❌ Failed to update user device registration");
        return false;
    }

    // Prepare log entry for device logs
    FirebaseJson logEntry;
    // Use current time as timestamp
    logEntry.set("timestamp", isTimeSynchronized());
    logEntry.set("event", "otp_verified");
    logEntry.set("user", userId);

    // Generate a unique log key
    String logPath = String("devices/") + deviceId + "/logs";
    FirebaseJson *json = &logEntry;
    
    // Use the correct push method
    if (!Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), json)) {
        Serial.println("❌ Failed to log OTP verification");
        Serial.println(fbdo.errorReason().c_str());
        // This is not a critical failure, so we'll continue
    }
    
    return true;
}

bool isUserRegisteredToDevice(String userTag, String& userId) {
    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase not ready to check user registration");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    
    if (!Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
        // Check specific error reason
        if (fbdo.errorReason() == "path not exist" || fbdo.errorReason() == "path not found") {
            // This is likely a first-time setup
            Serial.println("⚠️ Registered users node doesn't exist yet");
            return false; // Return false, but the calling function will check isFirstTimeUser separately
        }
        
        Serial.print("❌ Firebase error: ");
        Serial.println(fbdo.errorReason());
        Serial.println("❌ Failed to get registered users");
        return false;
    }

    FirebaseJson* json = fbdo.jsonObjectPtr();
    if (json == nullptr) {
        Serial.println("❌ No registered users found");
        return false;
    }

    FirebaseJsonData data;
    json->get(data, userTag);

    if (!data.success || data.type != "string") {
        Serial.println("❌ User not found or invalid format");
        return false;
    }

    userId = data.stringValue;
    return true;
}

bool isFirebaseReady() {
    static unsigned long lastStatusCheck = 0;
    static bool lastStatus = false;
    const unsigned long STATUS_CHECK_INTERVAL = 500; // Check at most twice per second
    
    // Return cached status if checked recently to avoid repeated calls
    unsigned long currentMillis = millis();
    if (currentMillis - lastStatusCheck < STATUS_CHECK_INTERVAL) {
        return lastStatus;
    }
    
    // Just do a simple quick check
    lastStatusCheck = currentMillis;
    lastStatus = Firebase.ready();
    
    return lastStatus;
}