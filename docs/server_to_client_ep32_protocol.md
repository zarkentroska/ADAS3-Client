# ADAS3 server → Android client ↔ ESP32 Bluetooth bridge

The Android app is the **Bluetooth master**: it owns the BT SPP socket to the
ESP32 and exposes an HTTP API for the ADAS3 server. The server **must not**
try to scan or open BT itself — its only job is to talk HTTP to the phone.
When `esp32 bt on` on the server side appeared to "hang scanning", the
likely cause was that the server tried to scan locally (or interpreted a
`409 not_connected` reply as "I have to scan myself") instead of asking the
phone via HTTP. This document spells out exactly what the server must send.

The HTTP server listens on the phone's bound IP and port (configurable in
the app, default `8080`). All endpoints are HTTP/1.1, JSON unless noted.

## 1. Quick reference

| Purpose | Method + path | Body | Reply |
|---|---|---|---|
| Enable BT bridge ("`esp32 bt on`") | `POST /adas3/ep32-control` | `{"action":"enable"}` | `202` + status JSON |
| Disable / stop bridge | `POST /adas3/ep32-control` | `{"action":"disable"}` | `202` + status JSON |
| Bounce the BT link | `POST /adas3/ep32-control` | `{"action":"reconnect"}` | `202` + status JSON |
| Poll status of the bridge | `GET /adas3/ep32-status` | (none) | `200` + status JSON |
| Send a direction key | `POST /adas3/ep32-command` | `{"command":"UP"\|"DOWN"\|"LEFT"\|"RIGHT"\|"TEST"\|"STATUS"}` | `200 accepted` / `409 not_connected` |
| Force a heartbeat from ESP32 | `POST /adas3/ep32-command` | `{"command":"STATUS"}` | `200 accepted` |
| Poll latest mic-array snapshot | `GET /adas3/mic-array/status` | (none) | `200` + JSON |
| Stream mic-array JSONL (heartbeat + acoustic) | `GET /adas3/mic-array/data` | (none) | `200` + `application/x-ndjson` |

## 2. Enable the bridge — the missing piece

The Android client only auto-connects to the ESP32 when the BT toggle is
on. Until this release, the only way to flip it was the in-app switch. The
new endpoint exposes that toggle to the server:

```http
POST /adas3/ep32-control HTTP/1.1
Host: <phone-ip>:8080
Content-Type: application/json
Content-Length: NN

{"action": "enable"}
```

Accepted values for `action`:

| `action` | Effect |
|---|---|
| `enable` / `on` / `start` | Persists `ep32_bluetooth_enabled=true`, flips the UI switch, calls `startEp32AutoConnectIfAllowed()` |
| `disable` / `off` / `stop` | Tears down the link and persists the preference as false |
| `reconnect` / `restart` | Stops and restarts the auto-connect cycle without touching the preference |

`type` is optional but accepted (`"type":"adas3-ep32-control"`).

Reply:

```json
{
  "connected": false,
  "state": "SCANNING",
  "detail": null,
  "enabled": true,
  "active": true,
  "bt_adapter_enabled": true,
  "permissions_granted": true
}
```

The HTTP status is `202 Accepted` because the work happens asynchronously
on the main thread (scan / connect take 1-10 s). Poll `/adas3/ep32-status`
to know when `state` becomes `CONNECTED`.

If the helper isn't initialised yet (e.g. app just launched), reply is
`503` with `{"status":"helper_unavailable"}`. Retry after 500 ms.

## 3. Poll bridge status before sending keys

```http
GET /adas3/ep32-status HTTP/1.1
Host: <phone-ip>:8080
```

```json
{
  "connected": true,
  "state": "CONNECTED",
  "detail": "ESP32-ADAS3 (AA:BB:CC:DD:EE:FF)",
  "enabled": true,
  "active": true,
  "bt_adapter_enabled": true,
  "permissions_granted": true,
  "firmware": "esp32-adas3 0.2.0",
  "mic_count": 4
}
```

`state` values come from `Ep32BluetoothHelper.State`:

- `OFF` — bridge stopped (the user disabled it, or it never started)
- `SCANNING` — BT discovery looking for `ESP32-ADAS3`
- `CONNECTING` — SPP socket negotiation in progress
- `CONNECTED` — ready to accept commands
- `ERROR` — see `detail` for the reason (e.g. `"Bluetooth is disabled"`,
  `"EP32 connection lost"`). The bridge will keep retrying automatically.

If `permissions_granted` is `false`, the user has not granted
`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (Android ≥ 12) or `ACCESS_FINE_LOCATION`
(Android ≤ 11) yet — the server should surface a UI hint to the user
because the OS will not show the runtime prompt unless someone interacts
with the app foreground (the request is fired from the activity).

## 4. Send a direction key

Once `state == CONNECTED`:

```http
POST /adas3/ep32-command HTTP/1.1
Host: <phone-ip>:8080
Content-Type: application/json
Content-Length: NN

{"command": "UP"}
```

Accepted single-shot commands: `UP`, `DOWN`, `LEFT`, `RIGHT`, `TEST`,
`STATUS`. Case is normalised to upper-case by the client.

`sequence` is also accepted for multi-step movements (the client picks the
first element today):

```json
{"sequence": ["UP","UP","RIGHT"], "delay_ms": 200}
```

`type` is optional but accepted (`"type":"adas3-ep32-command"`).

Replies:

- `200 OK` + `{"status":"accepted"}` — command queued onto the BT socket
- `409 Conflict` + `{"status":"not_connected"}` — bridge not in CONNECTED
  state. **This is the response that the server must NOT treat as
  "scan locally"**. Instead: poll `/adas3/ep32-status`, and if `enabled`
  is `false`, call `/adas3/ep32-control` with `enable`.
- `400 Bad Request` + `{"status":"invalid_payload"}` — body wasn't valid JSON
  or neither `command` nor `sequence` was provided

## 5. Recommended server flow for `esp32 bt on`

```text
1. POST /adas3/ep32-control  body={"action":"enable"}
       → expect 202
2. Poll GET /adas3/ep32-status every 500 ms (max 15 s)
       → wait until state == "CONNECTED"
       → if state stays "SCANNING" and permissions_granted=false,
         show a UI hint: "Abre la app Android y concede permisos BT"
       → if bt_adapter_enabled=false, show: "Activa Bluetooth en el móvil"
3. POST /adas3/ep32-command  body={"command":"UP"}  (or DOWN/LEFT/RIGHT)
```

For `esp32 bt off`:

```text
POST /adas3/ep32-control  body={"action":"disable"}
       → expect 202; the phone tears down the SPP link
```

For "the link feels stuck":

```text
POST /adas3/ep32-control  body={"action":"reconnect"}
       → bounces the link without flipping the pref
```

## 6. Verifying the mic array from the server side

The same Bluetooth link that carries direction commands also pulls the
ESP32-emitted JSONL events. Two endpoints expose this:

### 6.1 Snapshot

```http
GET /adas3/mic-array/status HTTP/1.1
```

Returns the last heartbeat + the last acoustic event seen by the phone.
The reply always carries `wiring` (the definitive 4-mic + 2-pair layout)
so the server can render the pinout even before the first heartbeat:

```json
{
  "connected": true,
  "wiring": {
    "mic_count": 4,
    "mics": [{"index":1,"pair":"A","channel":"LEFT","sel_to":"GND"}, ...],
    "i2s": [
      {"pair":"A","bclk_gpio":14,"lrcl_gpio":13,"dout_gpio":34,
       "left_mic":1,"right_mic":2},
      {"pair":"B","bclk_gpio":22,"lrcl_gpio":21,"dout_gpio":35,
       "left_mic":3,"right_mic":4}
    ],
    "remote_control": {"up_gpio":26,"down_gpio":27,"left_gpio":32,"right_gpio":33}
  },
  "heartbeat": {"mic_count": 4, "firmware": "esp32-adas3 0.2.0"},
  "last_acoustic": {"detected": true, "doa_deg": 24.3, "energy": 0.082,
                    "confidence": 0.78, "mic_count": 4}
}
```

### 6.2 Live stream

```http
GET /adas3/mic-array/data HTTP/1.1
```

The connection stays open with `Content-Type: application/x-ndjson` and
the phone forwards every line received from the ESP32, one per event:

```text
{"type":"heartbeat","mic_count":4,"firmware":"esp32-adas3 0.2.0","wiring":{...}}
{"type":"acoustic","detected":true,"doa_deg":24.3,"energy":0.082,"confidence":0.78,"mic_count":4,"pair":"A"}
...
```

Use this from the server to detect that the array is alive (heartbeats
should arrive every ~1 s once the bridge is `CONNECTED`).

## 7. Manual `curl` smoke tests

Replace `PHONE_IP` with the bound IP shown in the Android app.

```bash
# 1. Enable the BT bridge
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"action":"enable"}' \
  http://PHONE_IP:8080/adas3/ep32-control

# 2. Wait until connected
watch -n1 'curl -s http://PHONE_IP:8080/adas3/ep32-status'

# 3. Send a direction key
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"command":"UP"}' \
  http://PHONE_IP:8080/adas3/ep32-command

# 4. Force a heartbeat from the ESP32 to verify the link end-to-end
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"command":"STATUS"}' \
  http://PHONE_IP:8080/adas3/ep32-command

# 5. Read the last mic-array snapshot
curl -s http://PHONE_IP:8080/adas3/mic-array/status | jq .

# 6. Tail the live JSONL stream
curl -s http://PHONE_IP:8080/adas3/mic-array/data
```

## 8. What the server must NOT do

- **Never scan Bluetooth on the server host.** The phone is the master.
  If the server box runs `bluetoothctl scan on` looking for the ESP32,
  it will hang forever — neither the ESP32 nor the phone advertises to
  the server.
- **Never interpret `409 not_connected` as "try harder locally".** That
  reply means the phone's bridge isn't in `CONNECTED` state. Poll
  `/adas3/ep32-status` and act on `state` + `enabled`.
- **Never send the bare string `UP` on a TCP socket.** The
  `/adas3/ep32-command` endpoint takes JSON in the request body.
