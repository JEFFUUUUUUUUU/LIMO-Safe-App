#include "OTPVerifier.h"
#include "FirebaseHandler.h"
#include "UserManager.h"
#include "RGBLed.h"

bool OTPVerifier::validateFormat(const String& receivedOTP, String& userTag, String& actualOTP) {
    Serial.print("📡 DEBUG - Raw Received OTP: ");
    Serial.println(receivedOTP);

    if (receivedOTP.length() < 2) {
        Serial.println("❌ Invalid OTP format");
        return false;
    }

    userTag = receivedOTP.substring(0, 1);  // 🔍 Extract first character
    actualOTP = receivedOTP.substring(1);   // 🔍 Extract remaining OTP

    Serial.print("✅ DEBUG - Extracted User Tag: ");
    Serial.println(userTag);
    Serial.print("✅ DEBUG - Extracted OTP: ");
    Serial.println(actualOTP);

    return true;
}


bool OTPVerifier::verifyOTPCode(FirebaseData& fbdo, const String& userTag, const String& inputOTP, String& userId, String& storedOTP) {
    
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("❌ WiFi not connected for OTP verification!");
        return false;
    }

    // Check if Firebase is ready
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready for OTP verification!");
        delay(500); // Small delay
        return false;
    }

    Serial.print("📡 Querying Firebase for tag: ");
    Serial.print("📡 DEBUG - Querying Firebase with Tag: ");
    Serial.println(userTag);

    QueryFilter query;
    query.orderBy("tag");  
    query.equalTo(userTag);   // ✅ Force string format
    query.limitToFirst(1);

    if (!Firebase.RTDB.getJSON(&fbdo, "users", &query)) {  
        Serial.print("❌ Firebase Query Failed: ");
        Serial.println(fbdo.errorReason());
        
        Serial.println("🔥 Raw Firebase Response:");
        Serial.println(fbdo.jsonString()); // ✅ Print full JSON response for debugging
        return false;
    }

    FirebaseJson json = fbdo.jsonObject();
    FirebaseJsonData jsonData;
    size_t len = json.iteratorBegin();

    if (len == 0) {
        Serial.println("❌ No user found for this tag.");
        Serial.println("🔥 Raw Firebase Response:");
        Serial.println(fbdo.jsonString()); // ✅ Debugging
        return false;
    }

    // 🔹 Extract user ID
    String key, value;
    int type = 0;
    json.iteratorGet(0, type, key, value);
    json.iteratorEnd();

    userId = key;
    Serial.print("✅ Found User ID: ");
    Serial.println(userId);

    // 🔹 Fetch OTP using user ID
    String otpPath = "users/" + userId + "/otp/code";  

    if (!Firebase.RTDB.getString(&fbdo, otpPath.c_str())) {
        Serial.print("❌ Error fetching OTP: ");
        Serial.println(fbdo.errorReason());
        return false;
    }

    storedOTP = fbdo.stringData();
    Serial.print("✅ OTP Retrieved: ");
    Serial.println(storedOTP);

    // 🔹 Compare OTP
    if (inputOTP == storedOTP) {
        Serial.println("✅ OTP Verified Successfully!");
        if (Firebase.RTDB.deleteNode(&fbdo, otpPath.c_str())) {
            Serial.println("🗑️ OTP Deleted Successfully!");
        } else {
            Serial.print("❌ Failed to delete OTP: ");
            Serial.println(fbdo.errorReason());
        }
        return true;
    } else {
        Serial.println("❌ OTP Mismatch!");
        return false;
    }
}