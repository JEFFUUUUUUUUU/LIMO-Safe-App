#include "UserManager.h"
#include "FirebaseHandler.h"

bool UserManager::isFirstTimeUser(FirebaseData& fbdo, const String& deviceId) {
    String regPath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    
    if (!Firebase.RTDB.getJSON(&fbdo, regPath.c_str())) {
        // Path doesn't exist - this means no users are registered yet
        // Check for specific error messages that indicate path absence
        String errorReason = fbdo.errorReason();
        if (errorReason == "path not found" || errorReason == "path not exist") {
            Serial.println("ℹ️ No registered users found - first time setup");
            return true;
        } else {
            // Some other error occurred
            Serial.print("❌ Firebase error: ");
            Serial.println(errorReason);
            return true; // Assume first time if we can't verify otherwise
        }
    }
    
    // Path exists, check if there are any registered users
    FirebaseJson* regJson = fbdo.jsonObjectPtr();
    if (regJson == nullptr) {
        Serial.println("ℹ️ No JSON data - first time setup");
        return true;
    }
    
    size_t count = 0;
    String key, value;
    int type = 0;
    
    size_t len = regJson->iteratorBegin();
    for (size_t i = 0; i < len; i++) {
        regJson->iteratorGet(i, type, key, value);
        if (value.length() > 0) {
            count++;
        }
    }
    regJson->iteratorEnd();
    
    // If no users found, it's first time setup
    if (count == 0) {
        Serial.println("ℹ️ No registered users found - first time setup");
        return true;
    }
    
    Serial.print("ℹ️ Found ");
    Serial.print(count);
    Serial.println(" registered users - not first time setup");
    return false;
}

bool UserManager::verifyUserTag(FirebaseData& fbdo, const String& userTag, const String& deviceId, bool isFirstUser, String& foundUserId) {
    Serial.print("📡 Querying Firebase for user tag: ");
    Serial.println(userTag);

    QueryFilter query;
    query.orderBy("tag");  
    query.equalTo(userTag);  
    query.limitToFirst(1);  

    if (!Firebase.RTDB.getJSON(&fbdo, "users", &query)) {  
        Serial.print("❌ Firebase Query Failed: ");
        Serial.println(fbdo.errorReason());
        return false;
    }

    FirebaseJson json = fbdo.jsonObject();
    FirebaseJsonData jsonData;
    size_t len = json.iteratorBegin();

    if (len == 0) {
        Serial.println("❌ No user found with this tag.");
        return false;
    }

    // 🔹 Extract user ID
    String key, value;
    int type = 0;
    json.iteratorGet(0, type, key, value);
    json.iteratorEnd();

    foundUserId = key;  // ✅ Assign user ID
    Serial.print("✅ Found User ID: ");
    Serial.println(foundUserId);
    
    return true;
}

bool UserManager::registerUserToDevice(FirebaseData& fbdo, const String& deviceId, const String& userId, const String& userTag, bool isFirstUser) {
    // Make sure we're using only the userTag, not the full OTP
    String path = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE + "/" + userTag;
    
    // Set the user ID as the value
    if (!Firebase.RTDB.setString(&fbdo, path.c_str(), userId)) {
        Serial.println("❌ Failed to register user to device");
        return false;
    }
    
    Serial.println("✅ User registered successfully");
    return true;
}

bool UserManager::updateUserDeviceRegistration(FirebaseData& fbdo, const String& userId, const String& deviceId, const String& userRole) {
    String userPath = String(USERS_PATH) + userId;
    String deviceRolePath = userPath + "/registeredDevices/" + deviceId + "/role";
    
    // Set the role for this device directly using the new structure
    if (!Firebase.RTDB.setString(&fbdo, deviceRolePath.c_str(), userRole)) {
        Serial.print("❌ Failed to update user's device registration: ");
        Serial.println(fbdo.errorReason());
        return false;
    }
    
    Serial.println("✅ User device registration updated with role");
    return true;
}