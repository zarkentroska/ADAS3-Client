/*
 * ESP32-ADAS3 — Firmware unificado
 *
 *   1. Control remoto YIFON/ZIFON/YT2000 vía optoacoplador PC817 (Hailege),
 *      jumpers QUITADOS (Active HIGH). Comandos Bluetooth/USB existentes:
 *      UP / DOWN / LEFT / RIGHT / TEST. NO se rompe la compatibilidad.
 *
 *   2. Array acústico de 4 micrófonos MEMS I2S en dos parejas estéreo,
 *      cada una en su propio bus I2S del ESP32 clásico. Sin audio bruto
 *      por Bluetooth: sólo eventos JSON-Lines `heartbeat` y `acoustic`.
 *
 * Cableado definitivo (debe coincidir con el cliente/server):
 *
 *   Pareja A (I2S_NUM_0)   BCLK=GPIO14  LRCL=GPIO13  DOUT=GPIO34
 *     Mic1 SEL->GND  LEFT      Mic2 SEL->3V3 RIGHT
 *
 *   Pareja B (I2S_NUM_1)   BCLK=GPIO22  LRCL=GPIO21  DOUT=GPIO35
 *     Mic3 SEL->GND  LEFT      Mic4 SEL->3V3 RIGHT
 *
 *   Optoacoplador PC817 → mando YT2000:
 *     GPIO26=ARRIBA  GPIO27=ABAJO  GPIO32=IZQUIERDA  GPIO33=DERECHA
 *
 * NOTAS DE DISEÑO
 *   - Los GPIO 34/35 son INPUT-ONLY en el ESP32 clásico, lo cual es lo
 *     que necesitamos para I2S DOUT (datos hacia el MCU). No conectar
 *     nada de salida en esos pines.
 *   - El control de botones tiene PRIORIDAD: el loop atiende Bluetooth/USB
 *     antes que el muestreo I2S, y el muestreo I2S se hace en bloques no
 *     bloqueantes (timeout=0 en i2s_read). Una pulsación nunca se pierde
 *     por estar leyendo audio.
 *   - El cálculo de DOA aquí es una estimación coarse basada en el
 *     desbalance de energía L/R por pareja (placeholder determinista,
 *     bien documentado). La triangulación verdadera con GCC-PHAT
 *     entre buses queda como trabajo posterior cuando se calibre la
 *     geometría exacta y se sincronicen los timestamps.
 *   - El estimador acústico está deshabilitado por defecto en cuanto a
 *     emisión de `acoustic`: sólo se publica un heartbeat cada
 *     HEARTBEAT_PERIOD_MS. Los eventos `acoustic` se publican cuando
 *     la energía instantánea supera ENERGY_THRESHOLD Y la confianza
 *     supera CONFIDENCE_THRESHOLD (ambos ajustables).
 */

#include "BluetoothSerial.h"
#include "driver/i2s.h"
#include <math.h>

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error El Bluetooth no esta habilitado. Revisa la configuracion de tu placa.
#endif

BluetoothSerial SerialBT;

// =============================================================================
//  1. CONTROL DE BOTONES (existente, intacto)
// =============================================================================

#define PIN_UP    26
#define PIN_DOWN  27
#define PIN_LEFT  32
#define PIN_RIGHT 33

// Jumpers QUITADOS → HIGH activa, LOW reposo.
#define ACTIVE HIGH
#define IDLE   LOW

// Duración de la pulsación simulada (ms). Si el trípode no reacciona,
// sube a 400-500.
#define PRESS_MS 300

void pressPin(int pin, int ms) {
  digitalWrite(pin, ACTIVE);
  delay(ms);
  digitalWrite(pin, IDLE);
}

// =============================================================================
//  2. ARRAY ACUSTICO (nuevo)
// =============================================================================

// --- Pinout I2S (definitivo) ------------------------------------------------
#define PAIR_A_BCLK_GPIO   14
#define PAIR_A_LRCL_GPIO   13
#define PAIR_A_DOUT_GPIO   34

#define PAIR_B_BCLK_GPIO   22
#define PAIR_B_LRCL_GPIO   21
#define PAIR_B_DOUT_GPIO   35

// --- Parámetros DSP / sampling ---------------------------------------------
#define MIC_SAMPLE_RATE_HZ   16000
#define I2S_BITS_PER_SAMPLE  I2S_BITS_PER_SAMPLE_32BIT
#define I2S_FRAMES_PER_READ  256   // 256 frames estéreo = 1024 bytes a 32b
#define I2S_DMA_BUF_COUNT    4
#define I2S_DMA_BUF_LEN      256

// Umbrales para decidir cuándo emitir un evento `acoustic`. Pensados para
// SPH0645/INMP441: la muestra es int32 con sólo los 18-24 bits altos
// significativos; normalizamos a [0..1] dividiendo por 2^23.
#define MIC_SAMPLE_DIVISOR   (1 << 23)
#define ENERGY_THRESHOLD     0.05f   // 0..1
#define CONFIDENCE_THRESHOLD 0.55f
#define ACOUSTIC_MIN_PERIOD_MS 500   // debounce entre eventos `acoustic`
#define HEARTBEAT_PERIOD_MS         1000  // cadencia normal
#define HEARTBEAT_PERIOD_MS_AUDIO   3000  // cuando AUDIO_ON: 1/3 para
                                          // ceder ancho de banda SPP al PCM

// --- Identidad del firmware -------------------------------------------------
static const char* FIRMWARE_NAME = "esp32-adas3";
static const char* FIRMWARE_VERSION = "0.4.0";

// =============================================================================
//  HOLD / RELEASE (continuous press) — v0.4.0
// =============================================================================
//
// Antes, cualquier comando direccional (UP/DOWN/LEFT/RIGHT) hacía un
// `pressPin(pin, PRESS_MS=300ms)` y soltaba. Para que el trípode se
// mueva continuamente mientras el usuario mantiene pulsado el D-pad,
// añadimos:
//
//   - HOLD_UP / HOLD_DOWN / HOLD_LEFT / HOLD_RIGHT
//       Activa el GPIO correspondiente en modo "mantenido" y deja los
//       demás IDLE. Recibir HOLD_<otro> mientras otro está activo
//       implica liberar el anterior automáticamente.
//
//   - RELEASE  (alias: STOP)
//       Pone los cuatro GPIO a IDLE.
//
// Watchdog: si transcurren `HOLD_WATCHDOG_MS = 2000 ms` sin recibir
// ningún HOLD_* (refresh) ni RELEASE, el firmware libera por su
// cuenta. Cubre desconexiones BT/USB inesperadas para que el trípode
// nunca se quede moviéndose "solo". El server reenvía HOLD_<DIR>
// cada ~500 ms mientras siga pulsado, muy por debajo del watchdog.
//
// Legacy: UP/DOWN/LEFT/RIGHT siguen siendo pulsos cortos PRESS_MS,
// intactos para clientes/firmware antiguos.
#define HOLD_WATCHDOG_MS 2000

static int     activeHoldPin = -1;     // -1 = ninguno activo
static uint32_t activeHoldStartedMs = 0;
static uint32_t lastHoldRefreshMs = 0;

static void releaseAllHoldPins() {
  digitalWrite(PIN_UP,    IDLE);
  digitalWrite(PIN_DOWN,  IDLE);
  digitalWrite(PIN_LEFT,  IDLE);
  digitalWrite(PIN_RIGHT, IDLE);
  activeHoldPin = -1;
  activeHoldStartedMs = 0;
  lastHoldRefreshMs = 0;
}

static void startHold(int pin) {
  // Si ya hay otro pin en HOLD, suéltalo primero (sólo permitimos un
  // eje a la vez — el YT2000 no acepta dos a la vez por hardware).
  if (activeHoldPin >= 0 && activeHoldPin != pin) {
    digitalWrite(activeHoldPin, IDLE);
  }
  digitalWrite(pin, ACTIVE);
  activeHoldPin = pin;
  uint32_t now = millis();
  if (activeHoldStartedMs == 0) activeHoldStartedMs = now;
  lastHoldRefreshMs = now;
}

// Llamar desde loop() para auto-release si el server deja de
// refrescar (BT caído, server crasheado, etc.).
static void tickHoldWatchdog() {
  if (activeHoldPin < 0) return;
  if ((millis() - lastHoldRefreshMs) > (uint32_t)HOLD_WATCHDOG_MS) {
    Serial.println("HOLD watchdog: auto-release tras 2s sin refresh.");
    releaseAllHoldPins();
  }
}

// --- Estado runtime ---------------------------------------------------------
static bool i2sPairAReady = false;
static bool i2sPairBReady = false;

static int32_t i2sFrameBuf[I2S_FRAMES_PER_READ * 2]; // estéreo intercalado

static uint32_t lastHeartbeatMs = 0;
static uint32_t lastAcousticMs  = 0;

// Energía suavizada por canal (Mic1..Mic4)
static float micEnergyEMA[4] = {0.0f, 0.0f, 0.0f, 0.0f};
#define EMA_ALPHA 0.30f

// --- Streaming de audio PCM -------------------------------------------------
//
// Bluetooth Classic SPP soporta de forma estable ~16 kB/s. Por eso el
// streaming de audio va MONO PCM16 a 8 kHz (= 16 kB/s justo), con downmix
// L+R de la pareja A. La pareja B se sigue usando sólo para DOA/energía.
// Cada frame se encapsula como una linea JSON con base64 para que coexista
// con heartbeats/acoustics en el mismo canal SPP sin reinventar trama.
//
// Activar con el comando `AUDIO_ON`. Apagar con `AUDIO_OFF`. Estado en
// el heartbeat (campo `audio_streaming`).
#define AUDIO_OUT_RATE_HZ      8000
#define AUDIO_DECIMATION       (MIC_SAMPLE_RATE_HZ / AUDIO_OUT_RATE_HZ) // 2
#define AUDIO_PCM_BYTES_PER_S  (AUDIO_OUT_RATE_HZ * 2)
// 800 muestras PCM16 = 100 ms a 8 kHz = 1600 bytes raw -> ~2136 b base64.
// 10 frames/s = 22 kB/s, mismo ancho de banda total que con 160 muestras
// (50 frames/s) pero 5x menos overhead JSON/SPP/log/parser. Trade-off:
// 100 ms de latencia extra, irrelevante para detección Keras de drones.
#define AUDIO_FRAME_SAMPLES    800
// Shift total int32→int16 aplicado al sample del MEMS. INMP441/SPH0645
// entregan int24 dentro de un int32, con los **bits altos** ocupados
// por la señal (sign-extended hasta el bit 31) y los **8 bits bajos
// indefinidos** (datasheet Knowles: "reserved, do not rely on"). En
// la línea de lectura ya hacemos `>>8` para quitar esos 8 bits sucios
// y dejar un int24 limpio en `l` y `r`. Para pasar de int24 a int16
// necesitamos `>>8` MÁS — total `>>16`. La revisión 0.3.2 puso este
// shift a 14 (=>>6 extra) para "ganar 4x", pero un sample int24 puede
// ser hasta ±2^23 ≈ ±8.3 M; tras `>>6` sigue siendo ±131 k, que
// satura int16 con margen. Eso es exactamente lo que reportó el
// usuario (min=-32768, max=32759 sostenido).
//
// Con shift=16 el ambiente del cuarto da peak ~500-2000 (bajo pero
// NO saturado). Si necesitas más nivel para Keras, súbelo
// (a 15 o 14) DESPUÉS de aplicar el high-pass DC. NO bajes este
// shift sin el high-pass: la componente DC del MEMS sola puede
// saturar al amplificar.
#define AUDIO_INT16_SHIFT      16
// High-pass de un solo polo aplicado en el dominio int24 para eliminar
// el offset DC del MEMS antes del shift a int16. INMP441 tiene un
// offset DC documentado de hasta ±5% de full-scale; sin filtrarlo, la
// amplificación por software empuja el sample a saturación incluso
// con la habitación en silencio.
// y[n] = ALPHA * (y[n-1] + x[n] - x[n-1])
// ALPHA cerca de 1 → corner muy baja. ALPHA=0.995 a 16 kHz ≈ 13 Hz
// (eficaz para DC mientras preserva los 80-2000 Hz típicos del
// fundamental de motor de dron y voz humana).
// Implementación en Q16 punto fijo para evitar coma flotante por
// muestra: ALPHA_Q16 = round(0.995 * 65536) = 65209.
#define AUDIO_HPF_ENABLED      1
#define AUDIO_HPF_ALPHA_Q16    65209
static int32_t audioHpfPrevIn  = 0;
static int32_t audioHpfPrevOut = 0;
static int16_t audioFrameBuf[AUDIO_FRAME_SAMPLES];
static int audioFrameFill = 0;
static bool audioStreamingEnabled = false;
static uint32_t audioFramesSent = 0;

// Diagnóstico del último frame de audio emitido (se resetea al inicio
// de cada frame y se publica en el heartbeat). Permite responder "¿el
// PCM está saturado o lleva señal limpia?" sin tener que mirar al
// servidor.
static int32_t audioFrameMinSample    = 0;
static int32_t audioFrameMaxSample    = 0;
static uint32_t audioFrameAbsSum      = 0;   // Σ|sample|
static uint32_t audioFrameClippedHigh = 0;   // muestras a +32767
static uint32_t audioFrameClippedLow  = 0;   // muestras a -32768
static uint32_t audioFrameSamples     = 0;
// Últimos valores publicados (reset cada heartbeat para que el server
// vea sólo el frame más reciente, no acumulado total).
static int32_t  audioLastMin           = 0;
static int32_t  audioLastMax           = 0;
static int32_t  audioLastPeakAbs       = 0;
static uint32_t audioLastMeanAbsQ8     = 0;  // mean_abs * 256 (entero)
static uint32_t audioLastClippedHigh   = 0;
static uint32_t audioLastClippedLow    = 0;
static uint32_t audioLastFrameSamples  = 0;

// =============================================================================
//  3. INICIALIZACION I2S
// =============================================================================

// Devuelve true si el bus se ha podido inicializar.
static bool initI2sBus(i2s_port_t port, int bclk, int lrcl, int dout) {
  i2s_config_t i2s_config = {};
  i2s_config.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  i2s_config.sample_rate = MIC_SAMPLE_RATE_HZ;
  i2s_config.bits_per_sample = I2S_BITS_PER_SAMPLE;
  i2s_config.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;  // estéreo L+R
  i2s_config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  i2s_config.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  i2s_config.dma_buf_count = I2S_DMA_BUF_COUNT;
  i2s_config.dma_buf_len = I2S_DMA_BUF_LEN;
  i2s_config.use_apll = false;
  i2s_config.tx_desc_auto_clear = false;
  i2s_config.fixed_mclk = 0;

  i2s_pin_config_t pin_config = {};
  pin_config.bck_io_num = bclk;
  pin_config.ws_io_num = lrcl;
  pin_config.data_out_num = I2S_PIN_NO_CHANGE;
  pin_config.data_in_num = dout;

  esp_err_t err = i2s_driver_install(port, &i2s_config, 0, NULL);
  if (err != ESP_OK) {
    Serial.printf("i2s_driver_install(%d) falló: %d\n", (int)port, (int)err);
    return false;
  }
  err = i2s_set_pin(port, &pin_config);
  if (err != ESP_OK) {
    Serial.printf("i2s_set_pin(%d) falló: %d\n", (int)port, (int)err);
    i2s_driver_uninstall(port);
    return false;
  }
  return true;
}

static void initI2sPairs() {
  i2sPairAReady = initI2sBus(I2S_NUM_0,
                             PAIR_A_BCLK_GPIO, PAIR_A_LRCL_GPIO, PAIR_A_DOUT_GPIO);
  if (i2sPairAReady) {
    Serial.println("I2S pareja A (Mic1/Mic2) inicializada en I2S_NUM_0");
  }
  // En el ESP32 clásico I2S_NUM_1 existe. En variantes (S2/S3) sólo
  // hay un controlador; si la build no expone I2S_NUM_1, el firmware
  // sigue funcionando con la pareja A únicamente y publica acústica
  // sólo de Mic1/Mic2 (queda explícito en el heartbeat).
#ifdef I2S_NUM_1
  i2sPairBReady = initI2sBus(I2S_NUM_1,
                             PAIR_B_BCLK_GPIO, PAIR_B_LRCL_GPIO, PAIR_B_DOUT_GPIO);
  if (i2sPairBReady) {
    Serial.println("I2S pareja B (Mic3/Mic4) inicializada en I2S_NUM_1");
  }
#else
  Serial.println("ADVERTENCIA: I2S_NUM_1 no disponible en esta build; pareja B desactivada.");
#endif
}

// =============================================================================
//  4. LECTURA Y PROCESADO LIGERO
// =============================================================================

// Lee un bloque del bus indicado y devuelve la energía media normalizada por
// canal. left_out y right_out reciben valores en [0..1+], no bloquea (timeout=0).
// Retorna true si el bloque venía con datos.
static bool readPairEnergy(i2s_port_t port, float* left_out, float* right_out) {
  *left_out = 0.0f;
  *right_out = 0.0f;
  size_t bytes_read = 0;
  esp_err_t err = i2s_read(port, (void*)i2sFrameBuf,
                           sizeof(i2sFrameBuf), &bytes_read, 0);
  if (err != ESP_OK || bytes_read == 0) {
    return false;
  }
  const size_t frames = bytes_read / (sizeof(int32_t) * 2);
  if (frames == 0) {
    return false;
  }
  double accLeft = 0.0;
  double accRight = 0.0;
  for (size_t i = 0; i < frames; i++) {
    // Cada mic MEMS pone los 18-24 bits altos en el int32. Desplazamos 8
    // bits para acercarnos a int24, luego normalizamos por 2^23.
    int32_t l = i2sFrameBuf[i * 2 + 0] >> 8;
    int32_t r = i2sFrameBuf[i * 2 + 1] >> 8;
    double fl = (double)l / (double)MIC_SAMPLE_DIVISOR;
    double fr = (double)r / (double)MIC_SAMPLE_DIVISOR;
    accLeft  += fl * fl;
    accRight += fr * fr;
  }
  *left_out  = (float)sqrt(accLeft / (double)frames);
  *right_out = (float)sqrt(accRight / (double)frames);
  return true;
}

// Estimación coarse de DOA en grados a partir del desbalance L/R por pareja.
// Convenio: 0deg = frente del mando; positivo = derecha; pareja A define
// el eje principal, pareja B aporta confirmación. Este estimador es un
// placeholder determinista hasta que se calibre la geometría real y se
// implemente GCC-PHAT entre los dos buses.
//
//   ratio = (R - L) / (R + L + eps)   en [-1, +1]
//   doa_deg = ratio * 90              en [-90, +90]
static float estimateDoaDeg(float lA, float rA, float lB, float rB) {
  const float eps = 1e-6f;
  float ratioA = (rA - lA) / (rA + lA + eps);
  float ratioB = (rB - lB) / (rB + lB + eps);
  // Si pareja B está inactiva (no hay datos), usamos sólo A.
  float ratio;
  if ((lB + rB) < 1e-5f) {
    ratio = ratioA;
  } else {
    ratio = 0.5f * (ratioA + ratioB);
  }
  if (ratio > 1.0f) ratio = 1.0f;
  if (ratio < -1.0f) ratio = -1.0f;
  return ratio * 90.0f;
}

// =============================================================================
//  5. EMISION JSONL POR BLUETOOTH (+espejo en USB para debug)
// =============================================================================

// Bloque `wiring` compacto, mismo shape que el server espera. Lo enviamos
// dentro del heartbeat para que el cliente Android lo pueda reenviar
// verbatim al server sin tener que inventar nada.
static const char* WIRING_JSON =
  "{"
    "\"power_rail\":\"3V3\","
    "\"common_ground\":\"GND\","
    "\"mic_count\":4,"
    "\"mics\":["
      "{\"index\":1,\"pair\":\"A\",\"side\":\"LEFT\",\"sel_to\":\"GND\"},"
      "{\"index\":2,\"pair\":\"A\",\"side\":\"RIGHT\",\"sel_to\":\"3V3\"},"
      "{\"index\":3,\"pair\":\"B\",\"side\":\"LEFT\",\"sel_to\":\"GND\"},"
      "{\"index\":4,\"pair\":\"B\",\"side\":\"RIGHT\",\"sel_to\":\"3V3\"}"
    "],"
    "\"buses\":["
      "{\"pair\":\"A\",\"bclk_gpio\":14,\"lrcl_gpio\":13,\"dout_gpio\":34,"
        "\"left_mic\":1,\"right_mic\":2},"
      "{\"pair\":\"B\",\"bclk_gpio\":22,\"lrcl_gpio\":21,\"dout_gpio\":35,"
        "\"left_mic\":3,\"right_mic\":4}"
    "],"
    "\"remote_control\":{"
      "\"up_gpio\":26,\"down_gpio\":27,\"left_gpio\":32,\"right_gpio\":33"
    "}"
  "}";

static void emitLine(const String& line) {
  // Bluetooth como canal principal. Eco por USB para depuración local.
  SerialBT.println(line);
  Serial.println(line);
}

static void emitHeartbeat() {
  // Reflejamos en el heartbeat qué parejas están activas: si una falla,
  // mic_count baja a 2 para que el server lo sepa sin tener que inferirlo.
  int micCount = 0;
  if (i2sPairAReady) micCount += 2;
  if (i2sPairBReady) micCount += 2;
  if (micCount == 0) micCount = 0;

  String line;
  line.reserve(800);
  line  = "{\"type\":\"heartbeat\",\"mic_count\":";
  line += String(micCount);
  line += ",\"firmware\":\"";
  line += FIRMWARE_NAME;
  line += " ";
  line += FIRMWARE_VERSION;
  line += "\",\"pair_a_ready\":";
  line += (i2sPairAReady ? "true" : "false");
  line += ",\"pair_b_ready\":";
  line += (i2sPairBReady ? "true" : "false");
  line += ",\"audio_streaming\":";
  line += (audioStreamingEnabled ? "true" : "false");
  line += ",\"audio_format\":{\"encoding\":\"pcm16\",\"sample_rate\":";
  line += String(AUDIO_OUT_RATE_HZ);
  line += ",\"channels\":1,\"frame_samples\":";
  line += String(AUDIO_FRAME_SAMPLES);
  line += ",\"int16_shift\":";
  line += String(AUDIO_INT16_SHIFT);
  line += ",\"hpf\":";
  line += (AUDIO_HPF_ENABLED ? "true" : "false");
  line += "}";
  // Diagnóstico del último frame de audio emitido. El servidor lee
  // estos campos para responder "¿el PCM está saturado/limpio?" sin
  // necesidad de capturar el stream en vivo. Si `clipped_high` +
  // `clipped_low` representan más del 5% de `frame_samples`, el
  // shift es demasiado agresivo o hay un problema de cableado (SEL
  // mal, 3V3 fuera de rango).
  // mean_abs_q8 = mean(|sample|) * 256 (entero). El server lo
  // divide por 256 antes de pintar.
  line += ",\"audio_stats\":{";
  line += "\"min\":";          line += String((long)audioLastMin);
  line += ",\"max\":";         line += String((long)audioLastMax);
  line += ",\"peak_abs\":";    line += String((long)audioLastPeakAbs);
  line += ",\"mean_abs_q8\":"; line += String((unsigned long)audioLastMeanAbsQ8);
  line += ",\"clipped_high\":";line += String((unsigned long)audioLastClippedHigh);
  line += ",\"clipped_low\":"; line += String((unsigned long)audioLastClippedLow);
  line += ",\"samples\":";     line += String((unsigned long)audioLastFrameSamples);
  line += "}";
  line += ",\"audio_frames_sent\":";
  line += String((unsigned long)audioFramesSent);
  line += ",\"wiring\":";
  line += WIRING_JSON;
  line += "}";
  emitLine(line);
}

static void emitAcoustic(bool detected, float doaDeg, float energy,
                         float confidence, const char* pair) {
  // Limita decimales para no inflar la línea.
  String line;
  line.reserve(256);
  line  = "{\"type\":\"acoustic\",\"detected\":";
  line += (detected ? "true" : "false");
  line += ",\"doa_deg\":";
  line += String(doaDeg, 1);
  line += ",\"energy\":";
  line += String(energy, 3);
  line += ",\"confidence\":";
  line += String(confidence, 2);
  line += ",\"mic_count\":4";
  if (pair && pair[0]) {
    line += ",\"pair\":\"";
    line += pair;
    line += "\"";
  }
  line += "}";
  emitLine(line);
}

// --- Base64 encoder minúsculo --------------------------------------------
// No queremos depender de mbedtls aquí porque cambia entre cores. 12 líneas
// y zero allocs por frame.
static const char B64_TABLE[] =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// out_buf debe tener al menos 4*((in_len+2)/3) + 1 bytes. Devuelve la
// longitud sin contar el null final.
static size_t base64Encode(const uint8_t* in, size_t in_len, char* out_buf) {
  size_t i = 0;
  size_t j = 0;
  while (i + 3 <= in_len) {
    uint32_t v = ((uint32_t)in[i] << 16) | ((uint32_t)in[i + 1] << 8) | in[i + 2];
    out_buf[j++] = B64_TABLE[(v >> 18) & 0x3F];
    out_buf[j++] = B64_TABLE[(v >> 12) & 0x3F];
    out_buf[j++] = B64_TABLE[(v >> 6) & 0x3F];
    out_buf[j++] = B64_TABLE[v & 0x3F];
    i += 3;
  }
  if (i < in_len) {
    uint32_t v = (uint32_t)in[i] << 16;
    if (i + 1 < in_len) v |= (uint32_t)in[i + 1] << 8;
    out_buf[j++] = B64_TABLE[(v >> 18) & 0x3F];
    out_buf[j++] = B64_TABLE[(v >> 12) & 0x3F];
    out_buf[j++] = (i + 1 < in_len) ? B64_TABLE[(v >> 6) & 0x3F] : '=';
    out_buf[j++] = '=';
  }
  out_buf[j] = '\0';
  return j;
}

// Emite un frame de audio JSONL con base64. 160 muestras int16 = 320 bytes
// => 432 bytes base64 + ~80 de envoltura JSON. SPP a 16 kB/s soporta
// holgadamente 50 frames/s (8000/160).
static void emitAudioFrame(const int16_t* pcm, int n_samples) {
  static char b64Buf[4 * ((AUDIO_FRAME_SAMPLES * 2 + 2) / 3) + 4];
  const uint8_t* raw = reinterpret_cast<const uint8_t*>(pcm);
  size_t blen = base64Encode(raw, (size_t)(n_samples * 2), b64Buf);
  (void)blen;

  String line;
  // 800 muestras PCM16 (=1600 bytes raw) -> ~2136 base64 + ~90 envoltura.
  // Reservamos 2400 para evitar reallocs en cada frame.
  line.reserve(2400);
  line  = "{\"type\":\"audio\",\"seq\":";
  line += String((unsigned long)audioFramesSent);
  line += ",\"encoding\":\"pcm16\",\"sample_rate\":";
  line += String(AUDIO_OUT_RATE_HZ);
  line += ",\"channels\":1,\"samples\":";
  line += String(n_samples);
  line += ",\"data\":\"";
  line += b64Buf;
  line += "\"}";
  emitLine(line);
  audioFramesSent++;
}

// Lee un bloque del bus A y, **mientras** se procesa para audio si está
// activado, también acumula energía RMS L/R para alimentar heartbeat/acoustic.
// Esto evita que las dos rutas (audio + DOA) compitan por la misma cola DMA.
// Si `audioStreamingEnabled` es false, sólo computa energía.
// Devuelve true si había datos en el bloque.
static bool readPairAAudioAndEnergy(float* l_energy, float* r_energy) {
  *l_energy = 0.0f;
  *r_energy = 0.0f;
  size_t bytes_read = 0;
  esp_err_t err = i2s_read(I2S_NUM_0, (void*)i2sFrameBuf,
                           sizeof(i2sFrameBuf), &bytes_read, 0);
  if (err != ESP_OK || bytes_read == 0) return false;
  const size_t frames = bytes_read / (sizeof(int32_t) * 2);
  if (frames == 0) return false;

  double accL = 0.0;
  double accR = 0.0;
  // Decimamos 2:1 sólo para el audio; la energía la calculamos sobre todas
  // las muestras para mantener buena resolución del estimador.
  for (size_t i = 0; i < frames; i++) {
    int32_t l = i2sFrameBuf[i * 2 + 0] >> 8;
    int32_t r = i2sFrameBuf[i * 2 + 1] >> 8;

    // Energía RMS normalizada [0..1+] (igual que readPairEnergy original).
    double fl = (double)l / (double)MIC_SAMPLE_DIVISOR;
    double fr = (double)r / (double)MIC_SAMPLE_DIVISOR;
    accL += fl * fl;
    accR += fr * fr;

    // Audio: tomamos una muestra de cada AUDIO_DECIMATION (2 → 8 kHz mono).
    if (audioStreamingEnabled && (i % AUDIO_DECIMATION) == 0) {
      // l y r ya vienen pre-desplazados >>8 desde la lectura I2S, o sea
      // son int24 con signo extendido (rango ±2^23). Bajar este sample
      // a int16 = dividir por 2^8 = `>>8`. Total acumulado desde el
      // int32 original: AUDIO_INT16_SHIFT bits (default 16).
      //
      // 1) Downmix L+R en int24.
      int32_t mono24 = (l + r) / 2;

      // 2) High-pass DC blocker en int24 (antes de bajar a int16).
      //    y = α·(y_prev + x − x_prev)
#if AUDIO_HPF_ENABLED
      int64_t hpf_diff = (int64_t)mono24 - (int64_t)audioHpfPrevIn
                       + (int64_t)audioHpfPrevOut;
      int32_t hpf_out  = (int32_t)((hpf_diff * AUDIO_HPF_ALPHA_Q16) >> 16);
      audioHpfPrevIn   = mono24;
      audioHpfPrevOut  = hpf_out;
      int32_t mono24_dc = hpf_out;
#else
      int32_t mono24_dc = mono24;
#endif

      // 3) Shift extra para llegar a int16. extra_shift = TOTAL − 8
      //    (porque ya hicimos >>8 al leer del DMA).
      const int extra_shift = AUDIO_INT16_SHIFT - 8;
      int32_t pcm = (extra_shift > 0)
          ? (mono24_dc >> extra_shift)
          : (mono24_dc << (-extra_shift));

      // 4) Saturación con conteo para diagnóstico.
      if (pcm > 32767) { pcm = 32767; audioFrameClippedHigh++; }
      else if (pcm < -32768) { pcm = -32768; audioFrameClippedLow++; }

      // 5) Estadísticas del frame (sin coma flotante).
      if (audioFrameSamples == 0) {
        audioFrameMinSample = pcm;
        audioFrameMaxSample = pcm;
      } else {
        if (pcm < audioFrameMinSample) audioFrameMinSample = pcm;
        if (pcm > audioFrameMaxSample) audioFrameMaxSample = pcm;
      }
      int32_t abs_pcm = pcm < 0 ? -pcm : pcm;
      audioFrameAbsSum += (uint32_t)abs_pcm;
      audioFrameSamples++;

      audioFrameBuf[audioFrameFill++] = (int16_t)pcm;
      if (audioFrameFill >= AUDIO_FRAME_SAMPLES) {
        // Publicar las estadísticas en variables "last_*" y resetear
        // los acumuladores para el siguiente frame. El heartbeat lee
        // las "last_*" — siempre reflejan el frame más reciente.
        audioLastMin           = audioFrameMinSample;
        audioLastMax           = audioFrameMaxSample;
        int32_t pa = audioFrameMinSample < 0 ? -audioFrameMinSample : audioFrameMinSample;
        int32_t pb = audioFrameMaxSample < 0 ? -audioFrameMaxSample : audioFrameMaxSample;
        audioLastPeakAbs       = (pa > pb) ? pa : pb;
        audioLastMeanAbsQ8     = (audioFrameSamples > 0)
            ? (uint32_t)((((uint64_t)audioFrameAbsSum) << 8) / audioFrameSamples)
            : 0;
        audioLastClippedHigh   = audioFrameClippedHigh;
        audioLastClippedLow    = audioFrameClippedLow;
        audioLastFrameSamples  = audioFrameSamples;
        audioFrameMinSample    = 0;
        audioFrameMaxSample    = 0;
        audioFrameAbsSum       = 0;
        audioFrameClippedHigh  = 0;
        audioFrameClippedLow   = 0;
        audioFrameSamples      = 0;
        emitAudioFrame(audioFrameBuf, audioFrameFill);
        audioFrameFill = 0;
      }
    }
  }
  *l_energy = (float)sqrt(accL / (double)frames);
  *r_energy = (float)sqrt(accR / (double)frames);
  return true;
}

// =============================================================================
//  6. SETUP
// =============================================================================

void setup() {
  Serial.begin(115200);
  delay(50);

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

  initI2sPairs();
  if (!i2sPairAReady && !i2sPairBReady) {
    Serial.println("Sin buses I2S; el array acustico se publicara como apagado.");
  }

  lastHeartbeatMs = millis();
  lastAcousticMs = 0;
}

// =============================================================================
//  7. HANDLERS DE COMANDOS (compatibilidad total)
// =============================================================================

static void handleControlCommand(const String& cmd, const char* origin) {
  Serial.print(origin);
  Serial.print("> ");
  Serial.println(cmd);

  if      (cmd == "UP")    pressPin(PIN_UP,    PRESS_MS);
  else if (cmd == "DOWN")  pressPin(PIN_DOWN,  PRESS_MS);
  else if (cmd == "LEFT")  pressPin(PIN_LEFT,  PRESS_MS);
  else if (cmd == "RIGHT") pressPin(PIN_RIGHT, PRESS_MS);
  // --- Hold / release continuo (v0.4.0+) ---
  else if (cmd == "HOLD_UP")    startHold(PIN_UP);
  else if (cmd == "HOLD_DOWN")  startHold(PIN_DOWN);
  else if (cmd == "HOLD_LEFT")  startHold(PIN_LEFT);
  else if (cmd == "HOLD_RIGHT") startHold(PIN_RIGHT);
  else if (cmd == "RELEASE" || cmd == "STOP") releaseAllHoldPins();
  else if (cmd == "TEST") {
    pressPin(PIN_UP,    500); delay(300);
    pressPin(PIN_DOWN,  500); delay(300);
    pressPin(PIN_LEFT,  500); delay(300);
    pressPin(PIN_RIGHT, 500);
    Serial.println("TEST completado.");
  }
  else if (cmd == "STATUS") {
    // Comando nuevo de diagnóstico: fuerza un heartbeat fuera de cadencia.
    emitHeartbeat();
  }
  else if (cmd == "AUDIO_ON") {
    if (!i2sPairAReady) {
      Serial.println("AUDIO_ON ignorado: I2S pareja A no disponible.");
    } else {
      audioStreamingEnabled = true;
      audioFrameFill = 0;
      audioFramesSent = 0;
      // Reset del estado del HPF y de los diagnósticos. Sin esto, un
      // ciclo OFF→ON dejaría un primer chunk con valores acumulados
      // del frame parcial anterior + un transitorio largo del HPF.
      audioHpfPrevIn = 0;
      audioHpfPrevOut = 0;
      audioFrameMinSample = 0;
      audioFrameMaxSample = 0;
      audioFrameAbsSum = 0;
      audioFrameClippedHigh = 0;
      audioFrameClippedLow = 0;
      audioFrameSamples = 0;
      audioLastMin = 0;
      audioLastMax = 0;
      audioLastPeakAbs = 0;
      audioLastMeanAbsQ8 = 0;
      audioLastClippedHigh = 0;
      audioLastClippedLow = 0;
      audioLastFrameSamples = 0;
      Serial.println("AUDIO_ON: streaming PCM16 mono 8kHz JSONL base64.");
      emitHeartbeat();  // anuncia el estado nuevo
    }
  }
  else if (cmd == "AUDIO_OFF") {
    audioStreamingEnabled = false;
    audioFrameFill = 0;
    Serial.println("AUDIO_OFF: streaming detenido.");
    emitHeartbeat();
  }
  else {
    Serial.println("Comando desconocido.");
  }
}

// =============================================================================
//  8. LOOP
// =============================================================================

void loop() {
  // --- 8.1 Control de botones (PRIORIDAD) ---------------------------------
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) handleControlCommand(cmd, "BT");
  }
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) handleControlCommand(cmd, "USB");
  }

  // --- 8.1.b Watchdog del HOLD continuo -----------------------------------
  // Auto-release si llevamos > HOLD_WATCHDOG_MS sin refresh. El server
  // reenvía HOLD_<DIR> cada ~500 ms; cualquier silencio >2 s significa
  // que perdió la conexión o el usuario soltó pero el RELEASE no llegó.
  tickHoldWatchdog();

  // --- 8.2 Muestreo I2S no bloqueante -------------------------------------
  // En la pareja A usamos un combinado que computa energía Y, si el
  // streaming de audio está activo, emite frames PCM por SPP. La pareja B
  // sólo se usa para energía/DOA.
  float lA = 0.0f, rA = 0.0f, lB = 0.0f, rB = 0.0f;
  bool gotA = false, gotB = false;
  if (i2sPairAReady) gotA = readPairAAudioAndEnergy(&lA, &rA);
#ifdef I2S_NUM_1
  if (i2sPairBReady) gotB = readPairEnergy(I2S_NUM_1, &lB, &rB);
#endif

  if (gotA || gotB) {
    // EMA por canal para suavizar.
    if (gotA) {
      micEnergyEMA[0] = (1.0f - EMA_ALPHA) * micEnergyEMA[0] + EMA_ALPHA * lA;
      micEnergyEMA[1] = (1.0f - EMA_ALPHA) * micEnergyEMA[1] + EMA_ALPHA * rA;
    }
    if (gotB) {
      micEnergyEMA[2] = (1.0f - EMA_ALPHA) * micEnergyEMA[2] + EMA_ALPHA * lB;
      micEnergyEMA[3] = (1.0f - EMA_ALPHA) * micEnergyEMA[3] + EMA_ALPHA * rB;
    }

    // Energía total del array como media de los canales activos.
    int activeCh = 0;
    float energy = 0.0f;
    for (int i = 0; i < 4; i++) {
      if (micEnergyEMA[i] > 0.0f) {
        energy += micEnergyEMA[i];
        activeCh++;
      }
    }
    if (activeCh > 0) energy /= activeCh;

    // Confianza muy simple: relación entre energía y umbral, clip a [0..1].
    float confidence = energy / (ENERGY_THRESHOLD * 4.0f);
    if (confidence > 1.0f) confidence = 1.0f;
    if (confidence < 0.0f) confidence = 0.0f;

    // DOA placeholder (ver comentario en estimateDoaDeg).
    float doa = estimateDoaDeg(micEnergyEMA[0], micEnergyEMA[1],
                               micEnergyEMA[2], micEnergyEMA[3]);

    bool detected = (energy >= ENERGY_THRESHOLD) &&
                    (confidence >= CONFIDENCE_THRESHOLD);

    uint32_t now = millis();
    if (detected && (now - lastAcousticMs) >= ACOUSTIC_MIN_PERIOD_MS) {
      lastAcousticMs = now;
      // pair informativo: si sólo está activa una pareja, lo decimos.
      const char* pair = "";
      if (gotA && !gotB) pair = "A";
      else if (gotB && !gotA) pair = "B";
      emitAcoustic(true, doa, energy, confidence, pair);
    }
  }

  // --- 8.3 Heartbeat periódico --------------------------------------------
  uint32_t now = millis();
  uint32_t hbPeriod = audioStreamingEnabled
      ? HEARTBEAT_PERIOD_MS_AUDIO : HEARTBEAT_PERIOD_MS;
  if ((now - lastHeartbeatMs) >= hbPeriod) {
    lastHeartbeatMs = now;
    emitHeartbeat();
  }

  // Ceder al scheduler. NO sustituye al delay de pressPin (sigue siendo
  // bloqueante porque la pulsación así debe ser por diseño del mando).
  delay(2);
}
