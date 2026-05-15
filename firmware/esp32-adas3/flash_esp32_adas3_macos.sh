#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-/dev/cu.usbserial-0001}"
BAUD="${BAUD:-460800}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$SCRIPT_DIR/build/esp32-adas3-unified-merged.bin"
TOOL_TGZ="$SCRIPT_DIR/tools/esptool-v5.2.0-macos-arm64.tar.gz"
TOOL_DIR="/tmp/esptool-macos-arm64"
ESPTOOL="$TOOL_DIR/esptool"

if [[ ! -f "$BIN" ]]; then
  echo "ERROR: firmware bin not found: $BIN" >&2
  exit 1
fi

if [[ ! -x "$ESPTOOL" ]]; then
  if [[ ! -f "$TOOL_TGZ" ]]; then
    echo "ERROR: esptool package not found: $TOOL_TGZ" >&2
    exit 1
  fi
  rm -rf "$TOOL_DIR"
  tar -xzf "$TOOL_TGZ" -C /tmp
  chmod +x "$ESPTOOL"
fi

echo "Using port: $PORT"
echo "Using baud: $BAUD"
echo "Firmware: $BIN"
echo
echo "Checking ESP32 connection..."
"$ESPTOOL" --chip esp32 --port "$PORT" --baud 115200 chip-id

echo
echo "Flashing unified ADAS3 firmware..."
"$ESPTOOL" --chip esp32 --port "$PORT" --baud "$BAUD" write-flash -z 0x0 "$BIN"

echo
echo "Done. If the board does not reboot automatically, press EN/RST once."
echo "Open Serial Monitor at 115200 baud and Bluetooth-pair with ADAS3-ESP32."
