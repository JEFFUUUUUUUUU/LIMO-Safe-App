#include "UserManager.h"
#include "FirebaseHandler.h"

bool UserManager::isFirstTimeUser(FirebaseData& fbdo, const String& deviceId) {
    String regPath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    
    if (!Firebase.RTDB.getJSON(&fbdo, regPath.c_str())) {
        Serial.println("ℹ️ No registered users found - first time setup");
        return true;  // If path doesn't exist, it's first time setup
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
    // First check if user is already registered to this device
    String userDevicePath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE + "/" + userTag;
    if (Firebase.RTDB.getString(&fbdo, userDevicePath.c_str())) {
        Serial.println("ℹ️ User is already registered to this device");
        return true;  // Consider this a success case
    }

    // If not first user, check if they are approved in Firebase
    if (!isFirstUser) {
        String approvalPath = String(DEVICE_PATH) + deviceId + APPROVED_USERS_NODE + "/" + userId;
        if (!Firebase.RTDB.getBool(&fbdo, approvalPath.c_str())) {
            Serial.println("❌ Access Denied: User not approved by first user");
            return false;
        }
    }

    // Create the registeredUsers node with the user data
    FirebaseJson regJson;
    regJson.set(userTag, userId);  // Store userId under userTag
    
    // Set the JSON at the registeredUsers node
    String regPath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE;
    if (!Firebase.RTDB.setJSON(&fbdo, regPath.c_str(), &regJson)) {
        Serial.print("❌ Failed to register user to device: ");
        Serial.println(fbdo.errorReason());
        return false;
    }

    Serial.println("✅ User registered successfully");
    return true;
}

bool UserManager::updateUserDeviceRegistration(FirebaseData& fbdo, const String& userId, const String& deviceId) {
    String userPath = String(USERS_PATH) + userId;
    FirebaseJsonArray deviceArray;

    if (Firebase.RTDB.getJSON(&fbdo, userPath + "/registeredDevices")) {
        FirebaseJsonData devicesData;
        FirebaseJson userData = fbdo.jsonObject();
        userData.get(devicesData, "registeredDevices");
        
        if (devicesData.success && devicesData.type == "array") {
            FirebaseJsonArray existingArray;
            existingArray.setJsonArrayData(devicesData.stringValue);
            deviceArray = existingArray;
        }
    }

    // Check if device already exists in array
    bool deviceExists = false;
    size_t len = deviceArray.size();
    for (size_t i = 0; i < len; i++) {
        FirebaseJsonData item;
        deviceArray.get(item, i);
        if (item.success && item.stringValue == deviceId) {
            deviceExists = true;
            break;
        }
    }

    if (!deviceExists) {
        deviceArray.add(deviceId);
        FirebaseJson updateUser;
        updateUser.set("registeredDevices", deviceArray);
        
        if (!Firebase.RTDB.updateNode(&fbdo, userPath.c_str(), &updateUser)) {
            Serial.print("❌ Failed to update user's device registration: ");
            Serial.println(fbdo.errorReason());
            return false;
        }
    }

    return true;
}
