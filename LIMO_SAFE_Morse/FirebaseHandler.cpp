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

void checkFirebaseConnection() {
    static unsigned long lastAttempt = 0;
    static int attemptCount = 0;
    const unsigned long delayIntervals[] = {5000, 10000, 30000, 60000};  // 5s, 10s, 30s, 60s

    if (isFirebaseReady()) {
        attemptCount = 0;  // Reset on success
        return;
    }

    unsigned long now = millis();
    if (now - lastAttempt >= delayIntervals[min(attemptCount, 3)]) {
        Serial.println("🔄 Firebase disconnected! Attempting reconnection...");

        Firebase.reset(&config);
        Firebase.begin(&config, &auth);
        Firebase.reconnectWiFi(true);
        fbdo.setBSSLBufferSize(1024, 1024);

        if (isFirebaseReady()) {
            Serial.println("✅ Firebase Reconnected!");
            attemptCount = 0;
        } else {
            Serial.println("❌ Firebase reconnection failed!");
            attemptCount++;  // Increase delay
        }

        lastAttempt = now;
    }
}

void tokenStatusCallback(TokenInfo info) {
    if (info.status == token_status_ready) {
        Serial.println("✅ Firebase Token Ready");
    } else if (info.status == token_status_error) {
        Serial.println("❌ Firebase Token Error!");
    }
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
    config.token_status_callback = tokenStatusCallback;

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
        Serial.println(fbdo.errorReason().c_str());

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
    if (!Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
        FirebaseJson json;
        json.set("id", deviceId);
        json.set("status", "online");

        // Initialize WiFi node with connected = false as default
        FirebaseJson wifiJson;
        wifiJson.set("connected", false);
        json.set("wifi", wifiJson);

        if (Firebase.RTDB.setJSON(&fbdo, path.c_str(), &json)) {
            Serial.println("✅ Device data initialized in Firebase");
        } else {
            Serial.println("❌ Failed to initialize device data");
            return false;
        }
    }

    // Update device status
    if (updateDeviceStatus(true, false, false)) {
    Serial.println("✅ Device status updated in Firebase");

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
        checkFirebaseConnection();  // Attempt reconnection
        if (!isFirebaseReady()) return false; 
    }

    String path = String(DEVICE_PATH) + deviceId;  // Firebase path for device status
    
    FirebaseJson json;
    json.set("status/online", isOnline);  // ✅ Update 'online' status
    json.set("status/locked", isLocked);  // ✅ Update 'locked' status
    json.set("status/secure", isSecure);  // ✅ Update 'secure' status

    if (!Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
        Serial.print("❌ Failed to update device status: ");
        Serial.println(fbdo.errorReason());
        return false;
    }

    Serial.print("✅ Device status updated: ");
    Serial.print("Online=");
    Serial.print(isOnline ? "true" : "false");
    Serial.print(", Locked=");
    Serial.print(isLocked ? "true" : "false");
    Serial.print(", Secure=");
    Serial.println(isSecure ? "true" : "false");
    return true;
}

bool updateWiFiCredentials(const String& ssid, const String& password) {
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
        Serial.println(fbdo.errorReason());
        return false;
    }

    Serial.println("✅ WiFi credentials updated in Firebase");
    return true;
}

bool checkForNewWiFiCredentials(String& newSSID, String& newPassword) {
    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase not ready to check WiFi credentials");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId + WIFI_NODE;

    // 🔥 Force Firebase to refresh data
    Firebase.RTDB.setwriteSizeLimit(&fbdo, "tiny"); 
    Firebase.RTDB.getJSON(&fbdo, path.c_str()); // Force full refresh

    // 🔍 Debugging: Check Firebase response
    if (Firebase.RTDB.getString(&fbdo, path + "/ssid")) {
        newSSID = fbdo.stringData();
        Serial.print("📡 Received SSID from Firebase: ");
        Serial.println(newSSID);
    } else {
        Serial.println("❌ Failed to get SSID from Firebase.");
    }

    if (Firebase.RTDB.getString(&fbdo, path + "/password")) {
        newPassword = fbdo.stringData();
        Serial.print("🔑 Received Password from Firebase: ");
        Serial.println(newPassword);
    } else {
        Serial.println("❌ Failed to get Password from Firebase.");
    }

    if (newSSID.length() > 0 && newPassword.length() > 0) {
        Serial.println("✅ Found WiFi credentials in Firebase");
        return true;
    }

    Serial.println("❌ No new WiFi credentials found in Firebase");
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
        return false;
    }
    
    // Verify OTP and extract user details
    if (!OTPVerifier::verifyOTPCode(fbdo, userTag, receivedOTP, userId, storedOTP)) {
        setLEDStatus(STATUS_OTP_ERROR);
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
            return false;
        }
    }
    
    // At this point, the user should be registered - verify one more time
    if (!isUserRegisteredToDevice(userTag, userId)) {
        Serial.println("❌ User registration verification failed!");
        return false;
    }
    // Determine user role
    String userRole = "user"; // Default role
   
    // Path to the user's role for this device
    String userRolePath = String(USERS_PATH) + userId + "/registeredDevices/" + deviceId;
   
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

    // Successful verification actions
    Serial.println("✅ OTP verified successfully");
    
    // Unlock sequence
    sendCommandToNano("UNLOCK");
    setLEDStatus(STATUS_UNLOCKED);
    
    // Visual feedback
    delay(3000);
    setColorRGB(COLOR_OFF);
    
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
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase Not Ready!");
        setLEDStatus(STATUS_OFFLINE);
        return false;
    }
    return true;
}

void registerDeviceToFirestore() {
    if (!isFirebaseReady()) {
        Serial.println("❌ Firebase Not Ready! Skipping device registration.");
        return;
    }

    String path = String(DEVICE_PATH) + deviceId;

    FirebaseJson json;
    FirebaseJsonData result;  // Used for checking existing data

    // Read existing data first
    if (Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
        Serial.println("✅ Existing data found. Merging updates...");
        json.setJsonData(fbdo.to<FirebaseJson>().raw());
    } else {
        Serial.println("⚠️ No existing data. Creating new entry...");
    }

    // Ensure ID is set
    json.set("id", deviceId);

    // Update lastSeen & lastUpdated timestamp
    json.set("lastSeen", millis());
    json.set("lastUpdated", millis());

    // Ensure status object exists and only update missing values
    FirebaseJson statusJson;
    if (json.get(result, "status") && result.typeNum == FirebaseJson::JSON_OBJECT) {
        statusJson.setJsonData(result.to<String>());
    }
    if (!statusJson.get(result, "online")) statusJson.set("online", true);
    if (!statusJson.get(result, "locked")) statusJson.set("locked", false);
    if (!statusJson.get(result, "secure")) statusJson.set("secure", false);
    json.set("status", statusJson);

    // Ensure registeredUsers exists
    FirebaseJson usersJson;
    if (!json.get(result, "registeredUsers")) {
        json.set("registeredUsers", usersJson);
    }

    // Ensure WiFi details exist
    FirebaseJson wifiJson;
    if (json.get(result, "wifi") && result.typeNum == FirebaseJson::JSON_OBJECT) {
        wifiJson.setJsonData(result.to<String>());
    }
    wifiJson.set("connected", WiFi.status() == WL_CONNECTED);
    json.set("wifi", wifiJson);

    // ✅ Use updateNode to prevent overwriting everything
    if (Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
        Serial.println("✅ Device registered/updated successfully!");
        Serial.print("✅ Device ID: ");
        Serial.println(deviceId);
    } else {
        Serial.print("❌ Failed to update device: ");
        Serial.println(fbdo.errorReason());
    }
}