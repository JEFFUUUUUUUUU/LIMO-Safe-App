#ifndef ESP_COMMUNICATION_H
#define ESP_COMMUNICATION_H

#include <SoftwareSerial.h>

extern SoftwareSerial espSerial;
extern int messageCount;

void initializeESPCommunication();
void sendStatusToESP(bool isSafeClosed, bool motionDetected);
void checkESPResponse();
void processESPCommand(const char* command);

#endif // ESP_COMMUNICATION_H
