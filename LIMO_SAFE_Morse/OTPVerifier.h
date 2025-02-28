#ifndef OTP_VERIFIER_H
#define OTP_VERIFIER_H

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include "UserManager.h"

class OTPVerifier {
public:
    // Validates OTP format and extracts user tag and actual OTP
    static bool validateFormat(const String& receivedOTP, String& userTag, String& actualOTP);
    
    // Verifies OTP code against Firebase
    static bool verifyOTPCode(FirebaseData& fbdo, const String& deviceId, const String& receivedOTP, String& userTag, String& userId);
};

#endif
