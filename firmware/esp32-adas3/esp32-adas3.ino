/*
 * ESP32-ADAS3 — Control remoto Zifon vía optoacopladores Hailege
 *
 * Módulo optoacoplador (jumpers QUITADOS = Active HIGH):
 *   GPIO 26 → IN1 → ARRIBA
 *   GPIO 27 → IN2 → ABAJO
 *   GPIO 32 → IN3 → IZQUIERDA
 *   GPIO 33 → IN4 → DERECHA
 */

#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error El Bluetooth no está habilitado. Revisa la configuración de tu placa.
#endif

BluetoothSerial SerialBT;

// ─── Pines del optoacoplador ─────────────────────────────────────────────────
#define PIN_UP    26
#define PIN_DOWN  27
#define PIN_LEFT  32
#define PIN_RIGHT 33

// Jumpers QUITADOS → HIGH activa, LOW reposo.
// Si no funciona, prueba intercambiar estos dos valores.
#define ACTIVE HIGH
#define IDLE   LOW

// Duración de la pulsación simulada (ms).
// Si el trípode no reacciona, sube a 400 o 500.
#define PRESS_MS 300

// ─── Función auxiliar ────────────────────────────────────────────────────────

void pressPin(int pin, int ms) {
  digitalWrite(pin, ACTIVE);
  delay(ms);
  digitalWrite(pin, IDLE);
}

// ─── Setup ───────────────────────────────────────────────────────────────────

void setup() {
  Serial.begin(115200);

  pinMode(PIN_UP,    OUTPUT);
  pinMode(PIN_DOWN,  OUTPUT);
  pinMode(PIN_LEFT,  OUTPUT);
  pinMode(PIN_RIGHT, OUTPUT);

  digitalWrite(PIN_UP,    IDLE);
  digitalWrite(PIN_DOWN,  IDLE);
  digitalWrite(PIN_LEFT,  IDLE);
  digitalWrite(PIN_RIGHT, IDLE);

  SerialBT.begin("ESP32-ADAS3");

  Serial.println("ESP32-ADAS3 listo. Buscame por Bluetooth.");
}

// ─── Loop ────────────────────────────────────────────────────────────────────

void loop() {
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() == 0) return;

    Serial.print("BT> ");
    Serial.println(cmd);

    if      (cmd == "UP")    pressPin(PIN_UP,    PRESS_MS);
    else if (cmd == "DOWN")  pressPin(PIN_DOWN,  PRESS_MS);
    else if (cmd == "LEFT")  pressPin(PIN_LEFT,  PRESS_MS);
    else if (cmd == "RIGHT") pressPin(PIN_RIGHT, PRESS_MS);
    else if (cmd == "TEST") {
      pressPin(PIN_UP,    500); delay(300);
      pressPin(PIN_DOWN,  500); delay(300);
      pressPin(PIN_LEFT,  500); delay(300);
      pressPin(PIN_RIGHT, 500);
      Serial.println("TEST completado.");
    }
    else Serial.println("Comando desconocido.");
  }

  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() == 0) return;

    Serial.print("USB> ");
    Serial.println(cmd);

    if      (cmd == "UP")    pressPin(PIN_UP,    PRESS_MS);
    else if (cmd == "DOWN")  pressPin(PIN_DOWN,  PRESS_MS);
    else if (cmd == "LEFT")  pressPin(PIN_LEFT,  PRESS_MS);
    else if (cmd == "RIGHT") pressPin(PIN_RIGHT, PRESS_MS);
    else if (cmd == "TEST") {
      pressPin(PIN_UP,    500); delay(300);
      pressPin(PIN_DOWN,  500); delay(300);
      pressPin(PIN_LEFT,  500); delay(300);
      pressPin(PIN_RIGHT, 500);
      Serial.println("TEST completado.");
    }
  }

  delay(10);
}
