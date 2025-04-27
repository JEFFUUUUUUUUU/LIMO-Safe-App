#ifndef FirebaseHandler_h
#define FirebaseHandler_h

#include <Arduino.h>
#include <Firebase_ESP_Client.h>
#include <Preferences.h>

// Firebase objects
extern FirebaseData fbdo;
extern FirebaseAuth auth;
extern FirebaseConfig config;
extern Preferences preferences;

// Firebase paths
extern const char* const DEVICE_PATH;
extern const char* const USERS_PATH;
extern const char* const WIFI_NODE;
extern const char* const REGISTERED_USERS_NODE;
extern const char* const APPROVED_USERS_NODE;
extern const char* const LAST_VERIFICATION_NODE;
extern const char* const OTP_NODE;
extern String deviceId;

// Function declarations
void checkFirebaseConnection();
void tokenStatusCallback(TokenInfo info);
bool setupFirebase();
bool isFirebaseReady();
bool updateDeviceStatus(bool isOnline, bool isLocked, bool isSecure);
bool updateWiFiCredentials(const String& ssid, const String& password);
bool checkPeriodicWiFiCredentials(); 
bool checkForNewWiFiCredentials(String& newSSID, String& newPassword);
bool verifyOTP(String receivedOTP);
bool isUserRegisteredToDevice(String userTag, String& userId);
void registerDeviceToFirestore();

#endif
