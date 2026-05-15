# Selector de fuente de audio (Keras): móvil vs array ESP32

El cliente Android puede enviar al servidor ADAS3 **uno** de dos flujos de
audio PCM16 mono 44100 Hz que el detector Keras consumirá tal cual:

| Fuente | Cómo se captura | Camino al server |
|---|---|---|
| `phone_mic` (default) | `AudioRecord` del micro interno del móvil, 44100 Hz mono | `streamingServerHelper.sendAudioData(...)` → endpoint `/audio` |
| `esp32_array` | I2S pareja A del ESP32, downmix L+R, decimado a 8 kHz, base64 sobre SPP | ESP32 → SPP → Android (Ep32BluetoothHelper) → upsample lineal a 44100 → mismo `/audio` |

El servidor **no** distingue las dos fuentes a nivel de bytes — la
cabecera HTTP `Content-Type: audio/pcm; rate=44100; channels=1` es la
misma. Para saber qué fuente está activa, consulta `GET /adas3/audio-source`.

## 1. UI

`Settings → Fuente de audio → Origen del audio` (la opción la añade
`preferences.xml`). Persiste en `SharedPreferences` con la clave
`audio_source` (`phone_mic` | `esp32_array`).

Cambiarlo en runtime:
- Aplica el cambio inmediatamente (no hace falta reiniciar la app).
- Si era phone_mic → array: para `AudioRecord` y manda `AUDIO_ON` al ESP32 por SPP.
- Si era array → phone_mic: manda `AUDIO_OFF` al ESP32 y reabre `AudioRecord` (si el botón de audio está activo).

## 2. Endpoints HTTP nuevos

### `GET /adas3/audio-source`

```json
{
  "source": "phone_mic" | "esp32_array",
  "audio_enabled": true,
  "encoding": "pcm16",
  "channels": 1,
  "sample_rate": 44100,
  "array_audio_active": false,
  // sólo si source=esp32_array:
  "array_audio_source_rate": 8000,
  "array_audio_frames_in": 142,
  "array_audio_frames_forwarded": 138,
  "array_audio_last_frame_age_ms": 21,
  "bridge_connected": true
}
```

### `POST /adas3/audio-source`

Body:
```json
{"source": "phone_mic"} | {"source": "esp32_array"}
```
Alias aceptados: `phone`/`mic`/`internal` → `phone_mic`; `array`/`esp32`/`external` → `esp32_array`.

Respuesta:
- `200 {"status":"applied","snapshot":<GET payload>}` si se ha aplicado.
- `400 {"status":"invalid_payload"}` si el JSON o el `source` no son válidos.
- `405 {"status":"method_not_allowed"}` si no es GET/POST.

## 3. Contrato Bluetooth ESP32 → Android (modo audio)

El firmware unificado (`firmware/esp32-adas3/esp32-adas3.ino`, versión
0.3.0+) acepta dos comandos nuevos vía SPP/USB:

```
AUDIO_ON    — empieza a emitir frames PCM en JSONL
AUDIO_OFF   — para el streaming
```

Coexiste con `heartbeat` y `acoustic` en el mismo canal SPP. Frame:

```json
{
  "type": "audio",
  "seq": 42,
  "encoding": "pcm16",
  "sample_rate": 8000,
  "channels": 1,
  "samples": 160,
  "data": "<base64 320 bytes int16 LE>"
}
```

Detalles del DSP del firmware:
- Lectura I2S a 16 kHz estéreo (pareja A: GPIO14 BCLK, GPIO13 LRCL, GPIO34 DOUT).
- Downmix mono (L+R)/2.
- Decimación 2:1 → 8 kHz mono.
- Conversión int24 → int16 con saturación.
- Frames de 160 muestras = 20 ms a 8 kHz = 320 bytes raw → ~432 bytes base64.
- 50 frames/s × ~500 bytes/frame ≈ 25 kB/s. Cabe en SPP Bluetooth Classic.

El heartbeat ahora también indica el estado del modo audio:
```json
{
  ...
  "audio_streaming": true,
  "audio_format": {"encoding":"pcm16","sample_rate":8000,"channels":1,"frame_samples":160},
  "audio_frames_sent": 1024
}
```

## 4. Camino del audio en el cliente Android cuando `source=esp32_array`

```
ESP32 (I2S pair A) ─► firmware downmix+decim ─► JSONL "audio" base64 ─► SPP
        │
        ▼
Ep32BluetoothHelper.dispatchPayload("audio")
  └─► Base64.decode → AudioFrame{pcm: ByteArray, sampleRate: 8000, ...}
        │
        ▼
MainActivity.handleEsp32AudioFrame
  └─► upsamplePcm16Mono(pcm, 8000 → 44100)  [interp lineal]
        │
        ▼
streamingServerHelper.sendAudioData(resampled)
        │
        ▼
Server /audio (Content-Type: audio/pcm; rate=44100; channels=1)
        │
        ▼
Keras audio detection worker
```

El upsampler mantiene la última muestra entre frames para evitar clicks.

## 5. Compatibilidad con UI existente del audio del móvil

- El botón circular de "audio on/off" sigue funcionando exactamente igual:
  enciende/apaga el envío de PCM al server.
- Cuando `audio_source=esp32_array`, pulsar el botón también envía
  `AUDIO_ON` / `AUDIO_OFF` al ESP32 (no se intenta abrir el micro del móvil).
- Si pulsas el botón en modo array y el EP32 BT aún no está conectado,
  el flag local pasa a "on" y al conectar, la app re-envía `AUDIO_ON`
  automáticamente (lo hace `handleEp32State` al pasar a `CONNECTED`).

## 6. Verificación

### A. Que el array está conectado y emite heartbeats con campos audio

```bash
PHONE=192.168.1.42:8080
curl -s http://$PHONE/adas3/mic-array/status | jq '.heartbeat'
# Debe verse:
# {
#   "mic_count": 4,
#   "firmware": "esp32-adas3 0.3.0",
#   "audio_streaming": false,
#   ...
# }
```

### B. Que el server ve la fuente correcta

```bash
curl -s http://$PHONE/adas3/audio-source | jq .
```

### C. Cambiar a array desde el server

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"source":"esp32_array"}' \
  http://$PHONE/adas3/audio-source | jq .
```

Si el endpoint del firmware ESP32 todavía no estaba arrancado, el helper
manda `AUDIO_ON` en cuanto la app pone en marcha el puente.

### D. Que llegan frames PCM al server

```bash
curl -s http://$PHONE/adas3/audio-source | jq '.array_audio_frames_forwarded'
# Debe incrementarse con el tiempo (segundos).
```

### E. Confirmación end-to-end Keras

Mira la UI del server (`testcam.py`):
- Badge inferior `AUDIO DRON DETECTADO: NN%` reacciona a sonidos
  captados por el ESP32 (palmadas cerca del array). Si reacciona a
  sonido cerca del móvil **y no** del array → el servidor está usando
  `phone_mic`, revisa el preference.

## 7. Caveats

1. **8 kHz upsampled a 44100 ≠ 44100 nativo**. La interpolación lineal
   conserva la información hasta ~4 kHz (Nyquist a 8 kHz). Para detección
   Keras de drones (fundamentales 200-2000 Hz) suele ser más que
   suficiente. Si el modelo se entrenó con espectros >4 kHz, los rasgos
   de alta frecuencia no estarán; en ese caso hay que reentrenar o
   capturar más muestras desde el array.
2. **SPP Bluetooth Classic**: 25 kB/s sostenidos es el techo cómodo.
   Si ves discontinuidades, baja `AUDIO_FRAME_SAMPLES` en el firmware
   (más frames pequeños, menos latencia pero más overhead) o sube
   `AUDIO_DECIMATION` (más decimado → menor bitrate, pierdes ancho de
   banda agudo).
3. **`pair B` no se usa para audio**, sólo para DOA/energía. Esto es a
   propósito para no doblar el bitrate sobre SPP.
4. **Si EP32 BT está OFF y eliges `esp32_array`**, la app muestra un
   toast "esperando conexión Bluetooth". Activa EP32 BT y se enganchará.
5. **El permiso `RECORD_AUDIO`** ya no es estrictamente necesario en
   modo `esp32_array`, pero la app lo deja pedido como hasta ahora
   (no es destructivo).
