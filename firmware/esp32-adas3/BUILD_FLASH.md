# ESP32-ADAS3 — Build & flash (macOS)

Firmware unificado que controla el mando YIFON/ZIFON/YT2000 vía PC817 **y**
publica eventos del array acústico de 4 micros I2S por Bluetooth SPP.

## 0. Cableado requerido

| Función | GPIO |
|---|---|
| PC817 ARRIBA | 26 |
| PC817 ABAJO | 27 |
| PC817 IZQUIERDA | 32 |
| PC817 DERECHA | 33 |
| I2S pareja A BCLK | 14 |
| I2S pareja A LRCL/WS | 13 |
| I2S pareja A DOUT | 34 (input-only OK) |
| I2S pareja B BCLK | 22 |
| I2S pareja B LRCL/WS | 21 |
| I2S pareja B DOUT | 35 (input-only OK) |

Mic1 (LEFT pair A) → `SEL → GND`. Mic2 (RIGHT pair A) → `SEL → 3V3`.
Mic3 (LEFT pair B) → `SEL → GND`. Mic4 (RIGHT pair B) → `SEL → 3V3`.
3V3 a Mic1..4 en paralelo, GND común a mics y PC817.

## 1. Opción A — Arduino IDE

1. Instala el ESP32 core de Espressif (Boards Manager → "esp32 by Espressif
   Systems", >= 2.0.14). El core trae `BluetoothSerial.h` y `driver/i2s.h`.
2. **Tools → Board**: `ESP32 Dev Module` (ESP32 clásico). En las variantes
   S2/S3 sólo hay un controlador I2S; el firmware lo detecta y publica
   `pair_b_ready=false` en el heartbeat.
3. **Tools → Partition Scheme**: `Huge APP (3MB No OTA/1MB SPIFFS)` —
   recomendado porque el blob de Bluedroid no entra cómodamente en la
   partición por defecto.
4. **Tools → Port**: el puerto USB del ESP32 (suele ser
   `/dev/cu.usbserial-XXXX` o `/dev/cu.SLAB_USBtoUART`).
5. Abrir `firmware/esp32-adas3/esp32-adas3.ino` y pulsar Upload.

## 2. Opción B — `arduino-cli`

```bash
# Una sola vez:
brew install arduino-cli
arduino-cli config init
arduino-cli core update-index
arduino-cli core install esp32:esp32

# Compilar
cd firmware/esp32-adas3
arduino-cli compile \
  --fqbn esp32:esp32:esp32:PartitionScheme=huge_app \
  .

# Flashear (ajusta /dev/cu.usbserial-XXXX al puerto real)
arduino-cli upload \
  --fqbn esp32:esp32:esp32:PartitionScheme=huge_app \
  -p /dev/cu.usbserial-XXXX \
  .
```

Si el upload falla con `A fatal error occurred: Failed to connect`, mantén
pulsado **BOOT** en la placa al iniciar el upload (algunas DevKit no
hacen el reset automático bien).

## 3. Comprobación post-flash

1. Abre el monitor serie a 115200 baudios:
   ```bash
   arduino-cli monitor -p /dev/cu.usbserial-XXXX -c baudrate=115200
   ```
   Deberías ver:
   ```
   ESP32-ADAS3 listo. Buscame por Bluetooth.
   I2S pareja A (Mic1/Mic2) inicializada en I2S_NUM_0
   I2S pareja B (Mic3/Mic4) inicializada en I2S_NUM_1
   ```
   y un heartbeat JSONL cada segundo:
   ```
   {"type":"heartbeat","mic_count":4,...,"wiring":{...}}
   ```
2. Empareja `ESP32-ADAS3` por Bluetooth desde el cliente Android. La app
   debería mostrar el array conectado y un mic_count=4.
3. Comandos legacy intactos vía Bluetooth o USB serie:
   `UP`, `DOWN`, `LEFT`, `RIGHT`, `TEST`. Nuevo: `STATUS` fuerza un
   heartbeat inmediato.
4. Comandos nuevos del modo audio (firmware 0.3.0+):
   - `AUDIO_ON` — empieza a emitir frames `{"type":"audio",...}` con
     PCM16 mono 8 kHz base64 cada 20 ms. Refleja `audio_streaming:true`
     en el siguiente heartbeat.
   - `AUDIO_OFF` — detiene la emisión y vuelve a `audio_streaming:false`.
5. Háblale al array (palmada, voz) → debería aparecer una línea
   `{"type":"acoustic","detected":true,...}` cada >= 500 ms cuando se
   supere el umbral, **y** (si has hecho `AUDIO_ON`) frames `audio`
   continuos a 50 Hz.

## 4. Ajustes finos in-code

Todos en lo alto de `esp32-adas3.ino`:

| Constante | Default | Para qué |
|---|---|---|
| `PRESS_MS` | 300 | Duración de pulsación del mando |
| `ENERGY_THRESHOLD` | 0.05 | Umbral para emitir `acoustic` (0..1) |
| `CONFIDENCE_THRESHOLD` | 0.55 | Confianza mínima |
| `ACOUSTIC_MIN_PERIOD_MS` | 500 | Debounce entre `acoustic` |
| `HEARTBEAT_PERIOD_MS` | 1000 | Cadencia heartbeat |
| `MIC_SAMPLE_RATE_HZ` | 16000 | Sample rate I2S |
| `AUDIO_OUT_RATE_HZ` | 8000 | Sample rate del PCM emitido por SPP |
| `AUDIO_FRAME_SAMPLES` | 160 | Muestras por frame JSONL (20 ms a 8 kHz) |

## 5. Notas / caveats

- **DOA es placeholder**. El cálculo actual es un balance L/R por pareja
  (ver `estimateDoaDeg` en el `.ino`). Para una DOA real con GCC-PHAT
  entre los dos buses hace falta calibrar la geometría exacta y
  sincronizar timestamps; queda fuera del scope de este firmware
  unificado.
- **GPIO34/35 son INPUT-ONLY** en el ESP32 clásico, lo que es lo
  correcto para I2S DOUT. No se puede usar esos pines para otra cosa.
- **ESP32-S2/S3** sólo tienen un controlador I2S; el firmware detecta
  la ausencia de `I2S_NUM_1` con `#ifdef` y publica `pair_b_ready=false`.
- **No se transmite audio bruto** por Bluetooth, sólo eventos JSON-Lines
  (heartbeat + acoustic).
- El control de botones tiene **prioridad** sobre el muestreo I2S: los
  reads I2S usan `timeout=0` (no bloquean) y la atención de
  Bluetooth/USB se hace al principio del loop.
