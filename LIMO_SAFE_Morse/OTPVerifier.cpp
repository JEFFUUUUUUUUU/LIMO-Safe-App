#include "OTPVerifier.h"
#include "FirebaseHandler.h"
#include "UserManager.h"
#include "RGBLed.h"

bool OTPVerifier::validateFormat(const String& receivedOTP, String& userTag, String& actualOTP) {
    Serial.print("Validating OTP format: ");
    Serial.println(receivedOTP);
    Serial.print("Length: ");
    Serial.println(receivedOTP.length());

    if (receivedOTP.length() != 7) {
        Serial.println("❌ Invalid OTP length");
        Serial.printf("Expected: 7, Got: %d\n", receivedOTP.length());
        return false;
    }
    
    userTag = receivedOTP.substring(0, 1);
    actualOTP = receivedOTP.substring(1);
    
    Serial.print("✅ Extracted User Tag: ");
    Serial.println(userTag);
    Serial.print("✅ Extracted OTP: ");
    Serial.println(actualOTP);
    
    return true;
}

bool OTPVerifier::verifyOTPCode(FirebaseData& fbdo, const String& deviceId, const String& receivedOTP, String& userTag, String& userId) {
    String actualOTP;
    if (!validateFormat(receivedOTP, userTag, actualOTP)) {
        return false;
    }

    // First, find the user with matching tag
    String usersPath = String(USERS_PATH);
    if (!Firebase.RTDB.getJSON(&fbdo, usersPath.c_str())) {
        Serial.print("❌ Failed to get users: ");
        Serial.println(fbdo.errorReason());
        return false;
    }

    FirebaseJson usersJson = fbdo.jsonObject();
    FirebaseJsonData jsonData;
    bool userFound = false;
    String storedOTP;

    size_t len = usersJson.iteratorBegin();
    for (size_t i = 0; i < len; i++) {
        String key, value;
        int type = 0;
        usersJson.iteratorGet(i, type, key, value);
        
        if (value.startsWith("{")) {  // Is JSON object
            FirebaseJson userJson;
            userJson.setJsonData(value);
            
            // Check user tag
            FirebaseJsonData tagData;
            userJson.get(tagData, "tag");
            if (tagData.success && tagData.stringValue == userTag) {
                // Found matching user, check OTP
                FirebaseJsonData otpData;
                userJson.get(otpData, "otp/code");
                if (otpData.success) {
                    storedOTP = otpData.stringValue;
                    userId = key;
                    userFound = true;
                    break;
                }
            }
        }
    }
    usersJson.iteratorEnd();

    if (!userFound) {
        Serial.print("❌ No user found with tag: ");
        Serial.println(userTag);
        return false;
    }

    // Check if this is first-time setup
    bool isFirstUser = UserManager::isFirstTimeUser(fbdo, deviceId);
    
    // If not first user, verify they are registered
    if (!isFirstUser) {
        String registeredPath = String(DEVICE_PATH) + deviceId + REGISTERED_USERS_NODE + "/" + userTag;
        if (!Firebase.RTDB.getString(&fbdo, registeredPath.c_str())) {
            Serial.println("❌ User not registered to this device");
            return false;
        }
    }

    Serial.print("🔍 User Found with ID: ");
    Serial.println(userId);
    Serial.print("🔍 Stored OTP: ");
    Serial.println(storedOTP);
    Serial.print("🔍 Received OTP: ");
    Serial.println(receivedOTP);

    // Remove tag from stored OTP if it exists (it should be N + actual OTP)
    String storedOTPWithoutTag = storedOTP;
    if (storedOTP.startsWith(userTag)) {
        storedOTPWithoutTag = storedOTP.substring(userTag.length());
    }

    if (storedOTPWithoutTag == actualOTP) {  // Compare OTPs without tags
        Serial.println("✅ OTP verified successfully");
        
        // Clear the OTP after successful verification
        String otpPath = String(USERS_PATH) + userId + OTP_NODE;
        FirebaseJson clearJson;
        clearJson.set("code", "");
        clearJson.set("timestamp", 0);
        clearJson.set("attempts", 0);
        
        if (Firebase.RTDB.updateNode(&fbdo, otpPath.c_str(), &clearJson)) {
            Serial.println("✅ OTP cleared after verification");
        } else {
            Serial.println("⚠️ Failed to clear OTP, but verification succeeded");
            Serial.println(fbdo.errorReason());
        }
        
        return true;
    } else {
        setLEDStatus(STATUS_OTP_ERROR);
        delay(3000);
        setColorRGB(COLOR_OFF);
        Serial.println("❌ Invalid OTP");
        Serial.print("Expected (without tag): ");
        Serial.println(storedOTPWithoutTag);
        Serial.print("Received: ");
        Serial.println(actualOTP);
        return false;
    }
}
