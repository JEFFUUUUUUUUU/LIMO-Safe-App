#ifndef MORSE_DECODER_H
#define MORSE_DECODER_H

#include <Arduino.h>

// Function prototypes (declared once, defined in MorseDecoder.cpp)
String decodeMorse(String morse);
String lookupMorse(String code);

#endif
