#ifndef MORSE_DECODER_H
#define MORSE_DECODER_H

#include <Arduino.h>

// Function prototypes
String decodeMorse(String morse); // Convert complete Morse code string to plaintext
String lookupMorse(String code); // Convert a single Morse code character to its alphanumeric equivalent

#endif // MORSE_DECODER_H 