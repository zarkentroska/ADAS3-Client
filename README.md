# ADAS3 Android Client

**Versión:** 0.5 Alpha

ADAS3 Android Client es una aplicación de streaming de vídeo y audio en tiempo real para dispositivos Android. Permite convertir tu dispositivo Android en una cámara IP con capacidades avanzadas de configuración y múltiples opciones de conexión.

## 📱 Características Principales

### 🎥 Streaming de Vídeo
- **Streaming en tiempo real** de la cámara del dispositivo
- **Múltiples resoluciones** soportadas (720x480, 1280x720, 1920x1080 y más)
- **Control de calidad de imagen** ajustable (0-100%)
- **Control de FPS** (30 FPS Modo 1, 30 FPS Modo 2, Auto)
- **Retardo configurable** entre frames (delay en ms)
- **Vista previa en tiempo real** en la pantalla del dispositivo

### 🔊 Audio
- **Captura de audio** en tiempo real
- **Múltiples canales** (Mono/Estéreo)
- **Frecuencias de muestreo** configurables (8000 Hz, 16000 Hz, 44100 Hz, 48000 Hz)
- **Activación/desactivación** rápida mediante botón

### 🌐 Opciones de Conexión
- **Detección automática de IPs** disponibles
- **Priorización inteligente**: Tailscale > LAN/Wi-Fi > 4G/5G
- **Soporte para Tailscale** con detección automática y acceso rápido
- **Conexión ADB** para desarrollo y pruebas locales (127.0.0.1)
- **Puerto configurable** (por defecto 8080)
- **Actualización manual** de lista de IPs sin reiniciar la app
- **Etiquetado de IPs** según tipo de conexión:
  - `(Tailscale)` para IPs de Tailscale (100.x.x.x)
  - `(LAN/Wi-Fi)` para IPs locales (192.168.x.x)
  - `(4G/5G)` para IPs de datos móviles

### ⚙️ Configuración Avanzada
- **HTTP Basic Authentication** opcional (usuario/contraseña)
- **Soporte TLS/HTTPS** con certificados personalizados
- **Configuración persistente** de todas las opciones
- **Interfaz de ajustes** completa y organizada

### 🌍 Multiidioma
Soporte completo para 5 idiomas:
- 🇪🇸 **Español** (por defecto)
- 🇬🇧 **Inglés** (English)
- 🇫🇷 **Francés** (Français)
- 🇮🇹 **Italiano** (Italiano)
- 🇵🇹 **Portugués** (Português)

### 🔧 Integraciones
- **TinySA Helper**: Integración con dispositivos TinySA vía USB
- **Tailscale**: Integración directa con la aplicación Tailscale
- **ADB**: Soporte para conexión USB de depuración

## 📋 Requisitos

- **Android 7.0 (API 24)** o superior
- **Cámara** integrada en el dispositivo
- **Permisos necesarios**:
  - Cámara
  - Audio (opcional, para streaming de audio)
  - Internet
  - Acceso a red

## 🚀 Instalación

### Opción 1: APK Precompilado
1. Descarga el APK desde [Releases](https://github.com/zarkentroska/ADAS3-Client/releases)
2. Habilita "Fuentes desconocidas" en tu dispositivo Android
3. Instala el APK descargado

### Opción 2: Compilar desde el Código
```bash
# Clonar el repositorio
git clone https://github.com/zarkentroska/ADAS3-Client.git
cd ADAS3-Client

# Compilar el proyecto
./gradlew assembleDebug

# El APK estará en: app/build/outputs/apk/debug/
```

## ⚙️ Configuración

### Configuración Inicial

Al abrir la aplicación por primera vez, se aplicarán los siguientes valores por defecto:

- **Resolución**: 720x480 (o la primera disponible en orden de prioridad)
- **Calidad de imagen**: 50%
- **FPS**: 30 FPS Modo 2
- **Delay**: 0 ms
- **Canales de audio**: Estéreo
- **Frecuencia de muestreo**: 44100 Hz
- **Puerto**: 8080
- **Idioma**: Español

### Acceder a Ajustes

1. Abre la aplicación
2. Toca el botón de **Ajustes** (⚙️) en la interfaz principal
3. Configura las opciones según tus necesidades

### Configuración de Red

#### Selección de IP
La aplicación detecta automáticamente todas las IPs disponibles y las prioriza en este orden:
1. **Tailscale** (si está disponible y activo)
2. **LAN/Wi-Fi** (redes locales)
3. **4G/5G** (datos móviles)

Puedes:
- Seleccionar manualmente cualquier IP disponible
- Usar el botón de **actualizar** (🔄) para refrescar la lista sin reiniciar
- Seleccionar **ADB** si tienes el dispositivo conectado por USB

#### Puerto de Conexión
- Por defecto: **8080**
- Configurable en Ajustes > Configuración de Red > Puerto de conexión
- Rango válido: 1-65535

### Streaming

Una vez configurado, el streaming estará disponible en:
```
http://[IP_SELECCIONADA]:[PUERTO]/
```

Por ejemplo:
- `http://192.168.1.100:8080/` (LAN/Wi-Fi)
- `http://100.64.1.2:8080/` (Tailscale)
- `http://127.0.0.1:8080/` (ADB)

## 🎮 Uso

### Iniciar Streaming
1. Abre la aplicación
2. La cámara se iniciará automáticamente
3. Selecciona la IP deseada del menú desplegable
4. El streaming comenzará automáticamente
5. Accede a la URL mostrada desde cualquier dispositivo en la misma red

### Control de Audio
- Toca el botón de **audio** (🎤) para activar/desactivar el streaming de audio
- El estado se muestra mediante un toast

### Tailscale
- Si tienes Tailscale instalado, aparecerá un interruptor en la interfaz principal
- Toca el interruptor para abrir Tailscale
- Las IPs de Tailscale se detectan y priorizan automáticamente

### Conexión ADB
- Conecta tu dispositivo por USB
- Habilita la depuración USB
- Selecciona "ADB" en el menú de IPs
- El streaming estará disponible en `127.0.0.1:8080` en tu ordenador

## 🔒 Seguridad

### Autenticación HTTP Básica
Puedes configurar usuario y contraseña en:
- Ajustes > Autenticación HTTP Básica

### HTTPS/TLS
Para streaming seguro:
1. Ajustes > Configuración de Certificado
2. Activa "Habilitar TLS/HTTPS"
3. Selecciona tu certificado TLS
4. (Opcional) Introduce la contraseña del certificado
5. Reinicia la aplicación

## 🛠️ Desarrollo

### Estructura del Proyecto
```
ADAS3-Client/
├── app/
│   ├── src/main/
│   │   ├── kotlin/.../activities/
│   │   │   ├── MainActivity.kt
│   │   │   └── SettingsActivity.kt
│   │   ├── kotlin/.../helpers/
│   │   │   ├── AudioCaptureHelper.kt
│   │   │   ├── CameraResolutionHelper.kt
│   │   │   ├── StreamingServerHelper.kt
│   │   │   └── TinySAHelper.kt
│   │   └── res/
│   │       ├── values/ (Español)
│   │       ├── values-en/ (Inglés)
│   │       ├── values-fr/ (Francés)
│   │       ├── values-it/ (Italiano)
│   │       └── values-pt/ (Portugués)
│   └── build.gradle
├── build.gradle.kts
└── settings.gradle.kts
```

### Tecnologías Utilizadas
- **Kotlin** - Lenguaje de programación
- **AndroidX CameraX** - API de cámara
- **AndroidX Preference** - Sistema de preferencias
- **Material Design** - Componentes UI
- **Coroutines** - Programación asíncrona

## 📝 Licencia

Ver archivo [LICENSE](LICENSE) para más detalles.

## 🤝 Contribuciones

Las contribuciones son bienvenidas. Por favor:
1. Fork el proyecto
2. Crea una rama para tu feature (`git checkout -b feature/AmazingFeature`)
3. Commit tus cambios (`git commit -m 'Add some AmazingFeature'`)
4. Push a la rama (`git push origin feature/AmazingFeature`)
5. Abre un Pull Request

## 📞 Soporte

Para reportar problemas o solicitar características, por favor abre un [Issue](https://github.com/zarkentroska/ADAS3-Client/issues) en GitHub.

## 🔄 Historial de Versiones

### v0.5 Alpha
- Versión inicial con todas las características principales
- Soporte multiidioma (5 idiomas)
- Integración con Tailscale
- Soporte ADB
- Configuración avanzada de vídeo y audio
- Autenticación HTTP y HTTPS

---

**Desarrollado con ❤️ para la comunidad**

