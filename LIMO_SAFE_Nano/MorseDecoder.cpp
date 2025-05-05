#include "MorseDecoder.h"

// Morse Code Mapping Table - Array of morse code patterns and their corresponding characters
const char* const morseMap[][2] = {
    // Uppercase Letters
    {".-", "A"},   {"-...", "B"}, {"-.-.", "C"}, {"-..", "D"},   {".", "E"}, // A-E
    {"..-.", "F"}, {"--.", "G"},  {"....", "H"}, {"..", "I"},    {".---", "J"}, // F-J
    {"-.-", "K"},  {".-..", "L"}, {"--", "M"},   {"-.", "N"},    {"---", "O"}, // K-O
    {".--.", "P"}, {"--.-", "Q"}, {".-.", "R"},  {"...", "S"},   {"-", "T"}, // P-T
    {"..-", "U"},  {"...-", "V"}, {".--", "W"},  {"-..-", "X"},  {"-.--", "Y"}, // U-Y
    {"--..", "Z"}, // Z

    // Numbers
    {".----", "1"}, {"..---", "2"}, {"...--", "3"}, {"....-", "4"}, // 1-4
    {".....", "5"}, {"-....", "6"}, {"--...", "7"}, {"---..", "8"}, // 5-8
    {"----.", "9"}, {"-----", "0"}, // 9-0

    // Space Separator and Underscore
    {"/", " "},  {"..--.-", "_"}, // Special characters
};


String decodeMorse(String morse) {
    String result = ""; // Output string to build
    String token = ""; // Current morse character being processed
    for (unsigned int i = 0; i < morse.length(); i++) { // Iterate through each character
        char c = morse.charAt(i); // Get current character
        if (c == ' ') { // Space indicates end of a morse character
            if (token.length() > 0) { // If we have a token to process
                result += lookupMorse(token); // Convert morse to character and add to result
                token = ""; // Reset token for next character
            }
        } else if (c == '/') { // Slash indicates word break
            if (token.length() > 0) { // Process any pending token
                result += lookupMorse(token); // Add last character before space
                token = ""; // Reset token
            }
            result += " "; // Add space between words
            if ((i + 1) < morse.length() && morse.charAt(i + 1) == ' ') // Skip extra space if exists
                i++; // Skip next character (the space)
        } else {
            token += c; // Add dot or dash to current token
        }
    }
    if (token.length() > 0) { // Process final token if exists
        result += lookupMorse(token); // Add last character to result
    }
    return result; // Return decoded message
}

String lookupMorse(String code) {
    for (unsigned int i = 0; i < (sizeof(morseMap) / sizeof(morseMap[0])); i++) { // Iterate through morse mappings
        if (strcmp(code.c_str(), morseMap[i][0]) == 0) { // Compare morse code to each entry
            return String(morseMap[i][1]); // Return corresponding character if match found
        }
    }
    return "?"; // Return question mark for unknown morse patterns
} 