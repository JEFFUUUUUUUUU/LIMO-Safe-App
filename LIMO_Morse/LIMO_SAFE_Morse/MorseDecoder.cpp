#include "MorseDecoder.h"

// Morse Code Mapping Table
const char* const morseMap[][2] = {
    // Uppercase Letters
    {".-", "A"},   {"-...", "B"}, {"-.-.", "C"}, {"-..", "D"},   {".", "E"},
    {"..-.", "F"}, {"--.", "G"},  {"....", "H"}, {"..", "I"},    {".---", "J"},
    {"-.-", "K"},  {".-..", "L"}, {"--", "M"},   {"-.", "N"},    {"---", "O"},
    {".--.", "P"}, {"--.-", "Q"}, {".-.", "R"},  {"...", "S"},   {"-", "T"},
    {"..-", "U"},  {"...-", "V"}, {".--", "W"},  {"-..-", "X"},  {"-.--", "Y"},
    {"--..", "Z"},

    // Numbers
    {".----", "1"}, {"..---", "2"}, {"...--", "3"}, {"....-", "4"},
    {".....", "5"}, {"-....", "6"}, {"--...", "7"}, {"---..", "8"},
    {"----.", "9"}, {"-----", "0"},

    // Space Separator
    {"/", " "},  {"..--.-", "_"},
};


String decodeMorse(String morse) {
    String result = "";
    String token = "";
    for (unsigned int i = 0; i < morse.length(); i++) {
        char c = morse.charAt(i);
        if (c == ' ') {
            if (token.length() > 0) {
                result += lookupMorse(token);
                token = "";
            }
        } else if (c == '/') {
            if (token.length() > 0) {
                result += lookupMorse(token);
                token = "";
            }
            result += " ";
            if ((i + 1) < morse.length() && morse.charAt(i + 1) == ' ')
                i++;
        } else {
            token += c;
        }
    }
    if (token.length() > 0) {
        result += lookupMorse(token);
    }
    return result;
}

String lookupMorse(String code) {
    for (unsigned int i = 0; i < (sizeof(morseMap) / sizeof(morseMap[0])); i++) {
        if (strcmp(code.c_str(), morseMap[i][0]) == 0) {
            return String(morseMap[i][1]);
        }
    }
    return "?";
}
