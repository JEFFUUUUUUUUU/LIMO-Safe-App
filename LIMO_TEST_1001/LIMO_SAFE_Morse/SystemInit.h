#ifndef SYSTEM_INIT_H
#define SYSTEM_INIT_H

#include <Arduino.h>

// System initialization functions
void initializeSystem();
bool handleSystemInitialization();
bool isSystemInitialized();
void completeSystemInitialization(bool withFirebase);

// External state variables
extern bool systemInitialized;
extern unsigned long wifiSetupTimer;
extern bool wifiSetupInProgress;
extern int wifiSetupAttempts;
extern unsigned long firebaseSetupTimer;
extern bool firebaseSetupInProgress;
extern int firebaseSetupAttempts;

#endif // SYSTEM_INIT_H 