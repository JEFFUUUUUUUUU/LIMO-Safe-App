#ifndef MORSE_DECODER_H // Include guard start to prevent multiple inclusion
#define MORSE_DECODER_H // Define inclusion guard macro

#include <Arduino.h> // Include Arduino core library for String class

// Function prototypes (declared once, defined in MorseDecoder.cpp)
String decodeMorse(String morse); // Convert complete Morse code string to plaintext
String lookupMorse(String code); // Convert a single Morse code character to its alphanumeric equivalent

#endif // MORSE_DECODER_H // End of include guard
