#!/usr/bin/env bash
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/usr/lib/android-sdk}"
SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "${SDKMANAGER}" ]]; then
  echo "No se encontró sdkmanager en: ${SDKMANAGER}" >&2
  exit 1
fi

for _ in {1..10}; do
  yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
done

echo "Licencias de Android SDK aceptadas."
