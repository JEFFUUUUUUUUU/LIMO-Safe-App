#ifndef NETWORK_MANAGER_H
#define NETWORK_MANAGER_H

#include <Arduino.h>

// Network management functions
void setupNetworkChecks();
void handleNetworkTasks();

// External variables
extern unsigned long lastFirebaseCheck;
extern const unsigned long FIREBASE_CHECK_INTERVAL;

#endif // NETWORK_MANAGER_H 