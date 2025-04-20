#include "OTPVerifier.h"
#include "FirebaseHandler.h"
#include "UserManager.h"
#include "RGBLed.h"

bool OTPVerifier::validateFormat(const String& receivedOTP, String& userTag, String& actualOTP) {
    // Early return for invalid length
    if (receivedOTP.length() < 2) {
        Serial.println(F("❌ Invalid OTP format"));
        return false;
    }
    
    // Extract user tag and OTP more efficiently
    userTag = receivedOTP.charAt(0);  // More efficient than substring(0,1)
    actualOTP = receivedOTP.substring(1);
    return true;
}

bool OTPVerifier::verifyOTPCode(FirebaseData& fbdo, const String& userTag, const String& inputOTP, String& userId, String& storedOTP) {
    // Pre-check connectivity to fail fast
    if (WiFi.status() != WL_CONNECTED || !Firebase.ready()) {
        Serial.println(F("❌ Network not ready for OTP verification"));
        return false;
    }
    
    // Build query once
    QueryFilter query;
    query.orderBy("tag");
    query.equalTo(userTag);
    query.limitToFirst(1);  // Only need one result
    
    // Fetch user with optimized error handling
    if (!Firebase.RTDB.getJSON(&fbdo, "users", &query)) {
        Serial.print(F("❌ Firebase Query Failed: "));
        Serial.println(fbdo.errorReason());
        return false;
    }
    
    // Process JSON response more efficiently
    FirebaseJson& json = fbdo.jsonObject();
    size_t len = json.iteratorBegin();
    
    if (len == 0) {
        Serial.println(F("❌ No user found for this tag"));
        json.iteratorEnd(); // Clean up iterator
        return false;
    }
    
    // Extract user ID in one step
    String key;
    String value;
    int type = 0;
    json.iteratorGet(0, type, key, value);
    json.iteratorEnd(); // Clean up iterator immediately
    
    userId = key;
    
    // Use static buffer for path to avoid String concatenation
    char otpPath[64];
    snprintf(otpPath, sizeof(otpPath), "users/%s/otp/code", userId.c_str());
    
    if (!Firebase.RTDB.getString(&fbdo, otpPath)) {
        Serial.print(F("❌ Error fetching OTP: "));
        Serial.println(fbdo.errorReason());
        return false;
    }
    
    storedOTP = fbdo.stringData();
    
    // Compare OTP efficiently
    if (inputOTP.equals(storedOTP)) {
        Serial.println(F("✅ OTP Verified Successfully!"));
        
        // Delete the used OTP
        if (Firebase.RTDB.deleteNode(&fbdo, otpPath)) {
            Serial.println(F("🗑️ OTP Deleted"));
        }
        
        return true;
    }
    
    Serial.println(F("❌ OTP Mismatch!"));
    return false;
}