#include "FirebaseHandler.h"
#include "secrets.h"
#include "OTPVerifier.h"
#include "UserManager.h"
#include "NanoCommunicator.h"
#include "RGBLed.h"
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

void tokenStatusCallback(TokenInfo info) {
    if (info.status == token_status_ready) {
        Serial.println("✅ Firebase Token Ready");
    } else if (info.status == token_status_error) {
        Serial.println("❌ Firebase Token Error!");
    }
}

bool setupFirebase() {
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
    
    // Set the database URL and auth token
    config.database_url = FIREBASE_HOST;
    config.signer.tokens.legacy_token = FIREBASE_AUTH;
    config.token_status_callback = tokenStatusCallback;

    // Attempt Firebase initialization with retry mechanism
    int initAttempts = 3;
    while (initAttempts > 0) {
        Firebase.begin(&config, &auth);
        Firebase.reconnectWiFi(true);
        
        // Set database read timeout to 1 minute
        Firebase.RTDB.setReadTimeout(&fbdo, 1000 * 60);
        // Set write size and response size to 4KB (minimum size)
        Firebase.RTDB.setwriteSizeLimit(&fbdo, "tiny");

        delay(1000); // Give Firebase a moment to initialize

        if (Firebase.ready()) {
            break;
        }
        
        Serial.print("🔄 Firebase initialization attempt failed. Retries left: ");
        Serial.println(initAttempts - 1);
        initAttempts--;
        delay(2000); // Wait before retrying
    }

    // Check if Firebase initialization was successful
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase initialization failed after multiple attempts!");
        return false;
    }

    Serial.println("✅ Firebase Ready!");
    
    // Initialize device data if needed
    String path = String(DEVICE_PATH) + deviceId;
    if (!Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
        FirebaseJson json;
        json.set("id", deviceId);
        json.set("status", "online");
                
        // Initialize WiFi node with connected = false as default
        FirebaseJson wifiJson;
        wifiJson.set("connected", false);  // Default to false
        json.set("wifi", wifiJson);
        
        if (Firebase.RTDB.setJSON(&fbdo, path.c_str(), &json)) {
            Serial.println("✅ Device data initialized in Firebase");
        } else {
            Serial.println("❌ Failed to initialize device data");
            return false;
        }
    }
    
    // Update device status
    if (updateDeviceStatus(false, false, false, false, false)) {
        Serial.println("✅ Device status updated in Firebase");
        
        // Now that we're connected, update the WiFi connection status to true
        String wifiPath = path + WIFI_NODE + "/connected";
        Firebase.RTDB.setBool(&fbdo, wifiPath.c_str(), true);
        Serial.println("✅ WiFi connection status updated to 'connected'");
        
        return true;
    } else {
        Serial.println("❌ Failed to update device status");
        return false;
    }
}

bool updateDeviceStatus(bool isOnline, bool isLocked, bool isSecure, bool isOtp, bool isFingerprint) {
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready when updating device status");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId;  // Firebase path for device status
    
    FirebaseJson json;
    json.set("status/online", isOnline);  // ✅ Update 'online' status
    json.set("status/locked", isLocked);  // ✅ Update 'locked' status
    json.set("status/secure", isSecure);  // ✅ Update 'secure' status
    json.set("status/otp", isOtp);
    json.set("status/fingerprint", isFingerprint);

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
    Serial.print(", OTP=");
    Serial.println(isOtp ? "true" : "false");
    Serial.print(", Fingerprint=");
    Serial.println(isFingerprint ? "true" : "false");

    return true;
}

bool updateWiFiCredentials(const String& ssid, const String& password) {
    if (!Firebase.ready()) {
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
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready for OTP verification");
        return false;
    }

    String userTag, userId;
    if (!OTPVerifier::verifyOTPCode(fbdo, deviceId, receivedOTP, userTag, userId)) {
        return false;
    }

    // Check if this is first-time pairing
    bool isFirstUser = UserManager::isFirstTimeUser(fbdo, deviceId);

    // Register user to device (this will auto-approve first user)
    if (!UserManager::registerUserToDevice(fbdo, deviceId, userId, userTag, isFirstUser)) {
        return false;
    }

    // Check if user already has a role on this device
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
        if (isFirstUser) {
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
    logEntry.set("timestamp", millis());
    logEntry.set("event", "otp_verified");
    logEntry.set("user/id", userId);
    logEntry.set("user/tag", userTag);
    logEntry.set("role", userRole);
    logEntry.set("is_first_user", isFirstUser);

    // Generate a unique log key
    String logPath = String("devices/") + deviceId + "/logs";
    FirebaseJson *json = &logEntry;
    
    // Use the correct push method
    if (!Firebase.RTDB.pushJSON(&fbdo, logPath.c_str(), json)) {
        Serial.println("❌ Failed to log OTP verification");
        Serial.println(fbdo.errorReason().c_str());
        // This is not a critical failure, so we'll continue
    }

    Serial.println("✅ OTP verified successfully");
    sendCommandToNano("UNLOCK");
    setLEDStatus(STATUS_UNLOCKED);
    delay(3000);
    setColorRGB(COLOR_OFF);
    return true;
}

bool isUserRegisteredToDevice(String userTag, String& userId) {
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready to check user registration");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    
    if (!Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
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

    // Update only necessary fields
    json.set("id", deviceId);
    json.set("lastSeen", millis());
    json.set("lastUpdated", millis());

    // Only set "status" if it doesn't already exist
    FirebaseJson statusJson;
    statusJson.set("online", true);
    statusJson.set("locked", false);
    statusJson.set("secure", false);
    json.set("status", statusJson);

    // Only add registeredUsers if it doesn't already exist
    FirebaseJson usersJson;
    json.set("registeredUsers", usersJson);

    // ✅ Add WiFi node with SSID and password
    FirebaseJson wifiJson;
    wifiJson.set("ssid", "your-SSID-here");         // Replace with actual SSID
    wifiJson.set("pass", "your-PASSWORD-here"); // Replace with actual password
    wifiJson.set("connected", WiFi.status() == WL_CONNECTED);
    json.set("wifi", wifiJson);

    // Use updateNode to prevent overwriting existing data
    if (Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
        Serial.println("✅ Device registered/updated successfully!");
        Serial.print("✅ Device ID: ");
        Serial.println(deviceId);
    } else {
        Serial.print("❌ Failed to update device: ");
        Serial.println(fbdo.errorReason());
    }
}


