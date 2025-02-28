#include "FirebaseHandler.h"
#include "secrets.h"
#include "OTPVerifier.h"
#include "UserManager.h"
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
    
    // Set the database URL and auth token
    config.database_url = FIREBASE_HOST;
    config.signer.tokens.legacy_token = FIREBASE_AUTH;
    config.token_status_callback = tokenStatusCallback;

    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
    
    // Set database read timeout to 1 minute
    Firebase.RTDB.setReadTimeout(&fbdo, 1000 * 60);
    // Set write size and response size to 4KB (minimum size)
    Firebase.RTDB.setwriteSizeLimit(&fbdo, "tiny");

    delay(1000); // Give Firebase a moment to initialize

    if (Firebase.ready()) {
        Serial.println("✅ Firebase Ready!");
        
        // Initialize device data if needed
        String path = String(DEVICE_PATH) + deviceId;
        if (!Firebase.RTDB.getJSON(&fbdo, path.c_str())) {
            FirebaseJson json;
            json.set("id", deviceId);
            json.set("status", "online");
            json.set("lastSeen", millis());
            
            if (Firebase.RTDB.setJSON(&fbdo, path.c_str(), &json)) {
                Serial.println("✅ Device data initialized in Firebase");
            } else {
                Serial.println("❌ Failed to initialize device data");
                return false;
            }
        }
        
        // Update device status
        if (updateDeviceStatus(true)) {
            Serial.println("✅ Device status updated in Firebase");
            return true;
        } else {
            Serial.println("❌ Failed to update device status");
            return false;
        }
    } else {
        Serial.println("❌ Firebase initialization failed!");
        return false;
    }
}

bool updateDeviceStatus(bool isOnline) {
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready when updating device status");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId;
    String status = isOnline ? "online" : "offline";
    
    FirebaseJson json;
    json.set("status", status);
    json.set("lastUpdated", millis());

    if (!Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
        Serial.print("❌ Failed to update device status: ");
        Serial.println(fbdo.errorReason());
        return false;
    }

    Serial.print("✅ Device status updated: ");
    Serial.println(status);
    return true;
}

bool updateWiFiCredentials(const String& ssid, const String& password) {
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready when updating WiFi credentials");
        return false;
    }

    String path = String(DEVICE_PATH) + deviceId + WIFI_NODE;
    
    FirebaseJson json;
    json.set("ssid", ssid);
    json.set("password", password);
    json.set("lastUpdated", millis());

    if (!Firebase.RTDB.updateNode(&fbdo, path.c_str(), &json)) {
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
    
    if (Firebase.RTDB.getString(&fbdo, path + "/ssid")) {
        newSSID = fbdo.stringData();
        if (Firebase.RTDB.getString(&fbdo, path + "/password")) {
            newPassword = fbdo.stringData();
            
            if (newSSID.length() > 0 && newPassword.length() > 0) {
                Serial.println("✅ Found WiFi credentials in Firebase");
                return true;
            }
        }
    }
    
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

    // Update user's device registration
    if (!UserManager::updateUserDeviceRegistration(fbdo, userId, deviceId)) {
        return false;
    }

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
    json.set("id", deviceId);
    json.set("status", "online");
    json.set("lastSeen", millis());
    json.set("registeredUsers", "{}");  // Initialize empty object for users
    json.set("otp", "");  // Initialize empty OTP field
    
    if (Firebase.RTDB.setJSON(&fbdo, path.c_str(), &json)) {
        Serial.println("✅ Device registered successfully!");
        Serial.print("✅ Device ID: ");
        Serial.println(deviceId);
    } else {
        Serial.print("❌ Failed to register device: ");
        Serial.println(fbdo.errorReason());
    }
}
