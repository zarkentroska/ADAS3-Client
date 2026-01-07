#!/bin/bash
export ANDROID_HOME=/usr/lib/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# Aceptar todas las licencias
echo "y" | /usr/lib/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1

# Aceptar licencias una por una
for i in {1..10}; do
    echo "y" | /usr/lib/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1
done

echo "Licencias aceptadas"


