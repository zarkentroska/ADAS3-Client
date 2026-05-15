# Acoustic mic-array — definitive wiring (client side)

Source of truth: `MicArrayWiring.kt`. The ESP32 does all signal processing
(beamforming / GCC-PHAT) locally on 4 mics across 2 I2S pairs and emits
JSONL over Bluetooth SPP; the Android client only parses, enriches with
this wiring (if missing) and forwards to the ADAS3 server. The phone-mic
audio path is independent and is **not** mixed with mic-array events.

## Power / ground

- ESP32 3V3 single pin feeds Mic1..Mic4 in parallel.
- GND common to Mic1..Mic4 and PC817 optocouplers.

## SEL (local, no GPIO)

| Mic  | SEL  | Channel | Pair |
|------|------|---------|------|
| Mic1 | GND  | LEFT    | A    |
| Mic2 | 3V3  | RIGHT   | A    |
| Mic3 | GND  | LEFT    | B    |
| Mic4 | 3V3  | RIGHT   | B    |

## I2S pairs

| Pair | BCLK   | LRCL   | DOUT   | Left mic | Right mic |
|------|--------|--------|--------|----------|-----------|
| A    | GPIO14 | GPIO13 | GPIO34 | Mic1     | Mic2      |
| B    | GPIO22 | GPIO21 | GPIO35 | Mic3     | Mic4      |

## Remote control YT2000 + PC817

| Direction | GPIO   |
|-----------|--------|
| UP        | GPIO26 |
| DOWN      | GPIO27 |
| LEFT      | GPIO32 |
| RIGHT     | GPIO33 |

GND/COM shared with mic array ground.

## Bluetooth JSONL contract

The client accepts both the legacy minimal contract and the enriched
contract from the ESP32 firmware:

```jsonl
{"type":"heartbeat","mic_count":4,"firmware":"esp32-adas3 v0.3.3"}
{"type":"acoustic","detected":true,"doa_deg":42.5,"energy":0.81,"confidence":0.93,"mic_count":4}
```

Optional enriched fields (preserved verbatim when present): `pair`,
`bus`, `wiring`, `config`, plus any other top-level key (carried as
extras into the outbound payload).

If the firmware omits `wiring`, the client injects the JSON form of
`MicArrayWiring` so the server always receives a fully-described event.
`mic_count` is forced to 4 when missing or implausible.

### Heartbeat: audio_format y audio_stats (firmware ≥ v0.3.3)

Cuando `AUDIO_ON`, el heartbeat lleva dos bloques relacionados con el
PCM que el firmware está emitiendo:

```jsonc
"audio_format": {
  "encoding": "pcm16",
  "sample_rate": 8000,       // Hz, mono
  "channels": 1,
  "frame_samples": 800,      // 100 ms por frame
  "int16_shift": 16,         // shift int32→int16 (16 = conservador)
  "hpf": true                // high-pass DC blocker activado
},
"audio_stats": {
  "min": -1432,              // mínimo del último frame (int16)
  "max":  1610,              // máximo del último frame (int16)
  "peak_abs": 1610,
  "mean_abs_q8": 6912,       // mean(|sample|) * 256 → divide entre 256 = 27
  "clipped_high": 0,         // # samples a +32767
  "clipped_low": 0,          // # samples a -32768
  "samples": 800
}
```

Cómo interpretarlo en el servidor (`mic_check.md` §5):

| Síntoma en audio_stats | Diagnóstico | Acción |
|---|---|---|
| `min ≈ -32768`, `max ≈ 32767` durante segundos, `clipped_high+clipped_low > 0.05 * samples` | **Saturación**. Era el bug de v0.3.2: shift demasiado agresivo. | Asegurar `int16_shift: 16` y `hpf: true`. Flashear v0.3.3. |
| `mean_abs_q8/256` cerca de 0 con habitación normal (< 5) | Array mudo: SEL flotante, 3V3 fuera de rango, o cable I2S abierto. | Comprobar wiring de pareja A. |
| `mean_abs_q8/256` ≈ 20-200 en habitación normal, sube a >2000 con palmadas | **OK**. Equivalente al perfil del micrófono del móvil. | Continuar. |
| `hpf: false` y `min`/`max` constantes muy distintos de cero | DC offset del MEMS no filtrado. | El default es `hpf: true`; si está en `false` es porque alguien recompiló con `AUDIO_HPF_ENABLED 0`. Restaurar. |

### Sobre la conversión I2S → int16

INMP441 y SPH0645 entregan int24 dentro de un contenedor int32, con
los 8 bits bajos reservados (datasheet Knowles). El firmware:

1. `i2s_read` → buffer de int32, estéreo intercalado L/R.
2. `>> 8` para descartar los 8 bits inferiores ruidosos → int24 con
   signo extendido.
3. Downmix mono `(L+R)/2`.
4. **High-pass DC** (α=0.995, ~13 Hz a 16 kHz). Sin este filtro,
   el offset DC nativo del MEMS (~5% FS) se amplifica al hacer
   shift agresivo y satura int16.
5. `>> (AUDIO_INT16_SHIFT − 8)` para llevar de int24 a int16. Con
   shift total = 16, dividimos por 256, llevando peak típico de
   habitación a ~500-2000.
6. Saturación con conteo (`clipped_high`/`clipped_low`).

Si necesitas más nivel para el clasificador (drone lejano), baja
`AUDIO_INT16_SHIFT` a 15 o 14 — **pero sólo si el HPF está activo**.
Sin HPF, bajar el shift mete DC saturado en cada frame.

## HTTP endpoints exposed to the server

- `GET /adas3/mic-array/data` — keep-alive NDJSON stream of every JSONL
  payload received from the ESP32, enriched with wiring metadata.
- `GET /adas3/mic-array/status` — one-shot JSON snapshot with
  `connected`, full `wiring`, last `heartbeat` and last `last_acoustic`.

The server is responsible for queueing an internal `acoustic_array`
event. The client does **not** trigger Telegram for these events.
