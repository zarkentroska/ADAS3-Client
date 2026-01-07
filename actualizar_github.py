#!/usr/bin/env python3
"""
Script para actualizar GitHub: sube APK y actualiza release notes
"""
import os
import sys
from pathlib import Path

# Intentar importar requests
try:
    import requests
except ImportError:
    print("Instalando requests...")
    try:
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "requests", "-q"])
        import requests
    except Exception as e:
        print(f"Error instalando requests: {e}")
        print("Por favor instala requests manualmente: pip install requests")
        sys.exit(1)

# Configuración
PROJECT = Path("/home/zarkentroska/others/ADAS3 Android Client")
REPO = "zarkentroska/ADAS3-Client"
RELEASE_TAG = "v0.5Alpha"

# Leer token desde variable de entorno o archivo local (no versionado)
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN")
if not GITHUB_TOKEN:
    # Intentar leer desde archivo local (no versionado)
    token_file = PROJECT / ".github_token"
    if token_file.exists():
        try:
            GITHUB_TOKEN = token_file.read_text().strip()
        except Exception:
            pass

if not GITHUB_TOKEN:
    print("ERROR: GITHUB_TOKEN no encontrado")
    print("Configura el token de una de estas formas:")
    print("  1. Variable de entorno: export GITHUB_TOKEN='tu_token'")
    print("  2. Archivo local: echo 'tu_token' > .github_token")
    sys.exit(1)

os.chdir(PROJECT)

print("="*70)
print("  ACTUALIZANDO GITHUB - ADAS3 Android Client")
print("="*70)

headers = {"Authorization": f"token {GITHUB_TOKEN}", "Accept": "application/vnd.github.v3+json"}

# 1. Buscar APK
print("\n[1/5] Buscando APK...")
apk_file = None

# Buscar APK en diferentes ubicaciones posibles
for location in [
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/build/outputs/apk/release/app-release.apk",
    "app/release/app-release.apk",
    "*.apk"
]:
    if "*" in location:
        # Buscar cualquier APK
        for apk in PROJECT.rglob("*.apk"):
            if "release" in str(apk) or "debug" in str(apk):
                apk_file = apk
                break
    else:
        apk = PROJECT / location
        if apk.exists():
            apk_file = apk
            break

if apk_file:
    size_mb = apk_file.stat().st_size / 1024 / 1024
    print(f"  ✓ APK encontrado: {apk_file.name} ({size_mb:.1f} MB)")
else:
    print("  ✗ No se encontró APK")
    print("  Buscando en: app/build/outputs/apk/")
    sys.exit(1)

# 2. Obtener release
print("\n[2/5] Obteniendo release...")
try:
    r = requests.get(f"https://api.github.com/repos/{REPO}/releases/tags/{RELEASE_TAG}", 
                    headers=headers, timeout=15)
    if r.status_code != 200:
        print(f"  ✗ Error {r.status_code}: {r.text[:200]}")
        print("  Intentando crear release...")
        # Intentar crear el release si no existe
        r = requests.post(
            f"https://api.github.com/repos/{REPO}/releases",
            headers={**headers, "Content-Type": "application/json"},
            json={
                "tag_name": RELEASE_TAG,
                "name": "ADAS3 Android Client v0.5 Alpha",
                "body": "## ADAS3 Android Client v0.5 Alpha\n\nCliente Android para ADAS3 Server",
                "draft": False,
                "prerelease": True
            },
            timeout=15
        )
        if r.status_code != 201:
            print(f"  ✗ Error creando release: {r.status_code} - {r.text[:200]}")
            sys.exit(1)
    release_id = r.json()["id"]
    print(f"  ✓ Release ID: {release_id}")
except Exception as e:
    print(f"  ✗ Error: {e}")
    sys.exit(1)

# 3. Eliminar assets antiguos
print("\n[3/5] Eliminando assets antiguos...")
try:
    assets = requests.get(f"https://api.github.com/repos/{REPO}/releases/{release_id}/assets",
                         headers=headers, timeout=15).json()
    for a in assets:
        name = a["name"]
        if name.endswith(".apk"):
            r = requests.delete(f"https://api.github.com/repos/{REPO}/releases/assets/{a['id']}",
                          headers=headers, timeout=15)
            if r.status_code == 204:
                print(f"  ✓ Eliminado: {name}")
except Exception as e:
    print(f"  ⚠ Error eliminando: {e}")

# 4. Subir nuevo APK
print("\n[4/5] Subiendo APK...")
upload_hdrs = {**headers, "Content-Type": "application/vnd.android.package-archive"}

try:
    print(f"  Subiendo {apk_file.name}... (esto puede tardar varios minutos)")
    with open(apk_file, "rb") as f:
        r = requests.post(
            f"https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets?name=ADAS3-Client-0.5Alpha.apk",
            headers=upload_hdrs, data=f, timeout=600
        )
    if r.status_code == 201:
        print(f"  ✓ APK subido correctamente")
    else:
        print(f"  ✗ Error: {r.status_code} - {r.text[:200]}")
except Exception as e:
    print(f"  ✗ Error subiendo APK: {e}")

# 5. Actualizar notas
print("\n[5/5] Actualizando notas del release...")
notes = """## ADAS3 Android Client v0.5 Alpha

### 🚀 Descarga

- **ADAS3-Client-0.5Alpha.apk** - Aplicación Android para conectarse a ADAS3 Server
  - Instalación directa en dispositivos Android
  - Requiere Android 8.0 (API 26) o superior

### ✨ Características principales

* **Transmisión de video en tiempo real** - Streaming de cámara IP
* **Control remoto de TinySA Ultra** - Análisis de espectro RF
* **Detección de drones** - Integración con ADAS3 Server
* **Interfaz intuitiva** - Diseño moderno y fácil de usar
* **Conexión VPN** - Soporte para Tailscale

### 📋 Requisitos

* **Android 8.0 (API 26) o superior**
* **Permisos de cámara y almacenamiento**
* **Conexión a Internet** para conectarse a ADAS3 Server

### 🎮 Uso

1. Descarga e instala el APK
2. Concede los permisos necesarios
3. Configura la conexión a ADAS3 Server
4. ¡Disfruta de la detección de drones!

### 📝 Notas

* Esta es una versión alpha - puede contener bugs
* El APK requiere instalación desde "fuentes desconocidas"
* Compatible con ADAS3 Server v0.5 Alpha"""

try:
    r = requests.patch(
        f"https://api.github.com/repos/{REPO}/releases/{release_id}",
        headers={**headers, "Content-Type": "application/json"},
        json={"body": notes},
        timeout=15
    )
    if r.status_code == 200:
        print("  ✓ Notas actualizadas correctamente")
    else:
        print(f"  ✗ Error: {r.status_code} - {r.text[:200]}")
except Exception as e:
    print(f"  ✗ Error actualizando notas: {e}")

print("\n" + "="*70)
print("  ¡COMPLETADO!")
print("="*70)
print(f"\nRepositorio: https://github.com/{REPO}")
print(f"Release: https://github.com/{REPO}/releases/tag/{RELEASE_TAG}")
print("\n" + "="*70)
print("\nNOTA: Este script solo actualiza el APK y las notas del release.")
print("Para actualizar el código en el repositorio, ejecuta:")
print("  cd /home/zarkentroska/others/ADAS3\\ Android\\ Client")
print("  git add -A")
print("  git commit -m 'Actualización: mejoras y nuevas características'")
print("  git push origin main\n")

