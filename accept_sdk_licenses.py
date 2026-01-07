#!/usr/bin/env python3
import subprocess
import sys
import os

os.environ['ANDROID_HOME'] = '/usr/lib/android-sdk'
os.environ['PATH'] = os.environ.get('PATH', '') + ':/usr/lib/android-sdk/cmdline-tools/latest/bin'

sdkmanager = '/usr/lib/android-sdk/cmdline-tools/latest/bin/sdkmanager'

# Aceptar licencias
process = subprocess.Popen(
    [sdkmanager, '--licenses'],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True
)

# Enviar 'y' múltiples veces
output, error = process.communicate(input='y\n' * 50)

if 'All SDK package licenses accepted' in output or 'All SDK package licenses accepted' in error:
    print("✓ Todas las licencias aceptadas")
    sys.exit(0)
else:
    print("⚠ Algunas licencias pueden no haberse aceptado")
    print("Intentando instalar paquetes de todas formas...")
    sys.exit(1)


