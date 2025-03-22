#include "UserManager.h"
#include "FirebaseHandler.h"

bool UserManager::isFirstTimeUser(FirebaseData& fbdo, const String& deviceId) {
    String regPath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    
    if (!Firebase.RTDB.getJSON(&fbdo, regPath.c_str())) {
        // Check specific error reason before assuming first-time setup
        if (fbdo.errorReason() == "path not found") {
            Serial.println("ℹ️ No registered users found - first time setup");
            return true;
        } else {
            Serial.print("❌ Firebase error: ");
            Serial.println(fbdo.errorReason());
            return false; // Return an error state instead of assuming first time
        }
    }
    
    FirebaseJson regJson = fbdo.jsonObject();
    size_t count = 0;
    String key, value;
    int type = 0;
    
    size_t len = regJson.iteratorBegin();
    for (size_t i = 0; i < len; i++) {
        regJson.iteratorGet(i, type, key, value);
        if (value.length() > 0) {
            count++;
        }
    }
    regJson.iteratorEnd();
    
    return count == 0;
}

bool UserManager::verifyUserTag(FirebaseData& fbdo, const String& userTag, const String& deviceId, bool isFirstUser, String& foundUserId) {
    String globalUserPath = String(USERS_PATH);
    
    if (!Firebase.RTDB.getJSON(&fbdo, globalUserPath.c_str())) {
        Serial.print("❌ Failed to get users: ");
        Serial.println(fbdo.errorReason());
        return false;
    }
    
    FirebaseJson usersJson = fbdo.jsonObject();
    bool userFound = false;
    String key, value;
    int type = 0;
    
    size_t len = usersJson.iteratorBegin();
    for (size_t i = 0; i < len; i++) {
        usersJson.iteratorGet(i, type, key, value);
        if (value.startsWith("{") && value.endsWith("}")) {  // Check if value is a JSON object
            FirebaseJson userJson;
            userJson.setJsonData(value);
            FirebaseJsonData tagData;
            userJson.get(tagData, "tag");
            
            if (tagData.success && tagData.type == "string" && tagData.stringValue == userTag) {
                foundUserId = key;
                userFound = true;
                break;
            }
        }
    }
    usersJson.iteratorEnd();
    
    return userFound;
}

bool UserManager::registerUserToDevice(FirebaseData& fbdo, const String& deviceId, const String& userId, const String& userTag, bool isFirstUser) {
    // Get existing registered users first
    String regPath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    FirebaseJson existingRegJson;
    
    if (Firebase.RTDB.getJSON(&fbdo, regPath.c_str())) {
        existingRegJson = fbdo.jsonObject();
    }
    
    // Update rather than replace
    existingRegJson.set(userTag, userId);
    
    // Set the updated JSON
    if (!Firebase.RTDB.updateNode(&fbdo, regPath.c_str(), &existingRegJson)) {
        Serial.print("❌ Failed to register user to device: ");
        Serial.println(fbdo.errorReason());
        return false;
    }
    
    Serial.println("✅ User registered successfully");
    return true;
}

bool UserManager::updateUserDeviceRegistration(FirebaseData& fbdo, const String& userId, const String& deviceId, const String& userRole) {
    String userPath = String(USERS_PATH) + userId;
    FirebaseJson updateUser;
    FirebaseJson deviceRoles;
    
    // Check if user already has registered devices
    if (Firebase.RTDB.getJSON(&fbdo, userPath + "/registeredDevices")) {
        FirebaseJson userData = fbdo.jsonObject();
        deviceRoles = userData;
    }
    
    // Set or update the role for this device
    deviceRoles.set(deviceId, userRole);
    
    // Update the user's registered devices with the role
    updateUser.set("registeredDevices", deviceRoles);
    
    if (!Firebase.RTDB.updateNode(&fbdo, userPath.c_str(), &updateUser)) {
        Serial.print("❌ Failed to update user's device registration: ");
        Serial.println(fbdo.errorReason());
        return false;
    }
    
    Serial.println("✅ User device registration updated with role");
    return true;
}
