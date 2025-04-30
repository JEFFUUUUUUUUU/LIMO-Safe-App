#ifndef RGB_LED_H
#define RGB_LED_H

#include <Arduino.h>

// Define RGB LED pins
#define RED_PIN 25
#define GREEN_PIN 26
#define BLUE_PIN 27

// Define system statuses as enum
typedef enum {
    STATUS_IDLE,         
    STATUS_LOCKED,       
    STATUS_UNLOCKED,     
    STATUS_OTP_VERIFIED,
    STATUS_REGISTERED,
    STATUS_ERROR,
    STATUS_OTP_ERROR,    
    STATUS_ONLINE,
    STATUS_NORMAL,       
    STATUS_OFFLINE,      
    STATUS_SECURE,       
    STATUS_TAMPERED,     
    STATUS_SCANNING,     
    STATUS_FINGERPRINT_OK,
    STATUS_OPEN,
    STATUS_WAITING
} Status;

// Define RGB color combinations (bits: R,G,B)
#define COLOR_OFF    0b000
#define COLOR_RED    0b100
#define COLOR_GREEN  0b010
#define COLOR_BLUE   0b001
#define COLOR_YELLOW 0b110
#define COLOR_PURPLE 0b101
#define COLOR_CYAN   0b011
#define COLOR_WHITE  0b111

// Function declarations
void initRGB();
void setLEDStatus(Status status);
void setColorRGB(uint8_t color);
void pulseColor(uint8_t color, uint8_t times, unsigned int delayTime);
void blinkColor(uint8_t color, uint8_t times, unsigned int onTime, unsigned int offTime);

// LED effect functions
void startUnlockEffect();
void updateLEDEffects();

// External effect variables
extern unsigned long unlockLedTimer;
extern bool unlockLedActive;

#endif