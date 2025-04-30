#include "InputManager.h"
#include "LightSensor.h"
#include "FingerprintSensor.h"
#include "NanoCommunicator.h"

// Process all inputs in one function
void processAllInputs() {
    // Process light sensor input (decode Morse code)
    processLightInput();
    
    // Handle fingerprint sensor (high-current operation)
    handleFingerprint();
    
    // Process data from Arduino Nano
    handleNanoData();
} 