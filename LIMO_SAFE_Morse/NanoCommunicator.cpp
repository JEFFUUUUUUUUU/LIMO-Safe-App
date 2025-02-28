#include "NanoCommunicator.h"
#include "FirebaseHandler.h"

/*
void setupNanoCommunication() {
  Serial2.begin(UART_BAUD, SERIAL_8N1, UART_RX_PIN, UART_TX_PIN);
  Serial.println("✅ Nano UART Initialized");
}

void handleNanoData() {
  if (Serial2.available()) {
    String data = Serial2.readStringUntil('\n');
    Serial.print("[Nano→ESP32] ");
    Serial.println(data);

    // Process commands from Nano
    if (data.startsWith("MORSE:")) {
      String morseCode = data.substring(6);
      Serial.print("Full Morse Sequence Received: ");
      Serial.println(morseCode);
    }
    
    // Example: Forward temperature data to Firebase
    if (data.startsWith("TEMP:") && Firebase.ready()) {
      float temp = data.substring(5).toFloat();
      uploadTemperature(temp);
    }
  }
}

void sendCommandToNano(const String& command) {
  Serial2.println(command);
  Serial.print("[ESP32→Nano] ");
  Serial.println(command);
}
*/

// Firebase sensor data functions
void uploadSensorData(const String& sensorType, float value) {
    if (!Firebase.ready()) {
        Serial.println("❌ Firebase not ready when uploading sensor data");
        return;
    }

    String sensorPath = String(DEVICE_PATH) + deviceId + "/sensors/" + sensorType;
    
    if (!Firebase.RTDB.setFloat(&fbdo, sensorPath.c_str(), value)) {
        Serial.print("❌ Failed to upload sensor data: ");
        Serial.println(fbdo.errorReason());
        return;
    }
    
    Serial.printf("✅ %s data uploaded: %.2f\n", sensorType.c_str(), value);
}

void uploadTemperature(float temperature) {
    uploadSensorData("temperature", temperature);
}

void uploadLight(float light) {
    uploadSensorData("light", light);
}

void uploadHumidity(float humidity) {
    uploadSensorData("humidity", humidity);
}