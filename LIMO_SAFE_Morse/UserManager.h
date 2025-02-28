#ifndef USER_MANAGER_H
#define USER_MANAGER_H

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include "FirebaseHandler.h"

class UserManager {
public:
    // Check if this is the first user registration for the device
    static bool isFirstTimeUser(FirebaseData& fbdo, const String& deviceId);
    
    // Verify user tag exists and get user ID
    static bool verifyUserTag(FirebaseData& fbdo, const String& userTag, const String& deviceId, bool isFirstUser, String& foundUserId);
    
    // Register user to device, handles duplicate registrations
    static bool registerUserToDevice(FirebaseData& fbdo, const String& deviceId, const String& userId, const String& userTag, bool isFirstUser);
    
    // Update user's registered devices list, maintains array of devices
    static bool updateUserDeviceRegistration(FirebaseData& fbdo, const String& userId, const String& deviceId);
};

#endif
