#include "RGBLed.h"
#include <Arduino.h>

// Initialize RGB LED pins
void initRGB() {
    pinMode(RED_PIN, OUTPUT);
    pinMode(GREEN_PIN, OUTPUT);
    pinMode(BLUE_PIN, OUTPUT);
    
    // Turn off LED initially
    setColorRGB(COLOR_OFF);
}

// Set LED color based on system status
void setLEDStatus(Status status) {
    switch (status) {
        case STATUS_IDLE:
            pulseColor(COLOR_BLUE, 5, 800); // Slow pulsing blue (Waiting)
            break;
        case STATUS_LOCKED:
            setColorRGB(COLOR_RED);  // Solid Red
            break;
        case STATUS_UNLOCKED:
        case STATUS_OTP_VERIFIED:
        case STATUS_FINGERPRINT_OK:
        case STATUS_SECURE:
            setColorRGB(COLOR_GREEN);  // Solid Green
            break;
        case STATUS_ERROR:
        case STATUS_OTP_ERROR:
            blinkColor(COLOR_RED, 3, 500, 500); // 3 Blinks Red (OTP Error)
            break;
        case STATUS_ONLINE:
        case STATUS_NORMAL:
            setColorRGB(COLOR_BLUE);  // Solid Blue
            break;
        case STATUS_OFFLINE:
            blinkColor(COLOR_BLUE, 5, 300, 300); // Fast blinking blue (Offline)
            break;
        case STATUS_TAMPERED:
            pulseColor(COLOR_YELLOW, 5, 800);  // Solid Yellow (Red + Green)
            break;
        case STATUS_SCANNING:
            pulseColor(COLOR_YELLOW, 5, 400); // Pulsing Yellow
            break;
    }
}

// Function to set a specific color using a single byte
void setColorRGB(uint8_t color) {
    digitalWrite(RED_PIN, (color & 0b100) ? HIGH : LOW);
    digitalWrite(GREEN_PIN, (color & 0b010) ? HIGH : LOW);
    digitalWrite(BLUE_PIN, (color & 0b001) ? HIGH : LOW);
}

// Improved pulsing effect with smooth transitions
void pulseColor(uint8_t color, uint8_t times, unsigned int delayTime) {
    // For simplicity in this implementation, we're just doing on/off
    // A full implementation would use analogWrite for true fading
    for (uint8_t i = 0; i < times; i++) {
        setColorRGB(color);
        delay(delayTime / 2);
        setColorRGB(COLOR_OFF);
        delay(delayTime / 2);
    }
}

// Function for blinking with controllable on and off times
void blinkColor(uint8_t color, uint8_t times, unsigned int onTime, unsigned int offTime) {
    for (uint8_t i = 0; i < times; i++) {
        setColorRGB(color);
        delay(onTime);
        setColorRGB(COLOR_OFF);
        if (i < times - 1) {  // No delay after the last off
            delay(offTime);
        }
    }
}