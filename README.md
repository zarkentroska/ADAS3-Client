# ADAS3 Android Client

**Version:** 0.5 Alpha

<div align="left">
  <a href="https://kotlinlang.org/">
    <img src="https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  </a>
  <a href="https://github.com/zarkentroska/ADAS3-Client/releases">
    <img src="https://img.shields.io/badge/Downloads-Latest-6B7280?style=flat-square&logoColor=white&labelColor=6B7280&color=22C55E" alt="Downloads">
  </a>
</div>

ADAS3 Android Client is a real-time video and audio streaming application for Android devices. It allows you to turn your Android device into an IP camera with advanced configuration capabilities and multiple connection options.

This application is part of a **client-server architecture project** that works together with [ADAS3 Server](https://github.com/zarkentroska/adas3-server). The ultimate goal of this combined project is **drone detection for various applications**, providing a complete solution for monitoring and detecting unmanned aerial vehicles (UAVs) in real-time.

<div align="center">
  <table>
    <tr>
      <td align="center" width="33%">
        <img src="https://raw.githubusercontent.com/zarkentroska/ADAS3-Client/main/interfaz1.jpg" alt="ADAS3 Client Interface 1" width="200">
      </td>
      <td align="center" width="33%">
        <img src="https://raw.githubusercontent.com/zarkentroska/ADAS3-Client/main/interfaz2.jpg" alt="ADAS3 Client Interface 2" width="200">
      </td>
      <td align="center" width="33%">
        <img src="https://raw.githubusercontent.com/zarkentroska/ADAS3-Client/main/interfaz3.jpg" alt="ADAS3 Client Interface 3" width="200">
      </td>
    </tr>
  </table>
  <p><em>Screenshots of the ADAS3 Android Client user interface</em></p>
</div>

## 📱 Main Features

### 🎥 Video Streaming
- **Real-time streaming** from the device camera
- **Multiple resolutions** supported (720x480, 1280x720, 1920x1080 and more)
- **Image quality control** adjustable (0-100%)
- **FPS control** (30 FPS Mode 1, 30 FPS Mode 2, Auto)
- **Configurable delay** between frames (delay in ms)
- **Real-time preview** on the device screen

### 🔊 Audio
- **Real-time audio capture**
- **Multiple channels** (Mono/Stereo)
- **Configurable sample rates** (8000 Hz, 16000 Hz, 44100 Hz, 48000 Hz)
- **Quick enable/disable** via button

### 🌐 Connection Options
- **Automatic IP detection** of available IPs
- **Smart prioritization**: Tailscale > LAN/Wi-Fi > 4G/5G
- **Tailscale support** with automatic detection and quick access
- **ADB connection** for development and local testing (127.0.0.1)
- **Configurable port** (default 8080)
- **Manual update** of IP list without restarting the app
- **IP labeling** by connection type:
  - `(Tailscale)` for Tailscale IPs (100.x.x.x)
  - `(LAN/Wi-Fi)` for local IPs (192.168.x.x)
  - `(4G/5G)` for mobile data IPs

### ⚙️ Advanced Configuration
- **Optional HTTP Basic Authentication** (username/password)
- **TLS/HTTPS support** with custom certificates
- **Persistent configuration** of all options
- **Complete and organized settings interface**

### 🌍 Multi-language
Full support for 5 languages:
- 🇪🇸 **Spanish** (default)
- 🇬🇧 **English**
- 🇫🇷 **French** (Français)
- 🇮🇹 **Italian** (Italiano)
- 🇵🇹 **Portuguese** (Português)

### 🔧 Integrations
- **TinySA Helper**: Integration with TinySA devices via USB
- **Tailscale**: Direct integration with Tailscale application
- **ADB**: Support for USB debugging connection

## 📋 Requirements

- **Android 7.0 (API 24)** or higher
- **Built-in camera** on the device
- **Required permissions**:
  - Camera
  - Audio (optional, for audio streaming)
  - Internet
  - Network access

## 🚀 Installation

### Option 1: Precompiled APK

<div align="center">
  <a href="https://github.com/zarkentroska/ADAS3-Client/releases/latest">
    <img src="https://user-images.githubusercontent.com/69304392/148696068-0cfea65d-b18f-4685-82b5-329a330b1c0d.png" alt="Get it on GitHub" width="200">
  </a>
</div>

1. Download the APK from [Releases](https://github.com/zarkentroska/ADAS3-Client/releases)
2. Enable "Unknown sources" on your Android device
3. Install the downloaded APK

### Option 2: Build from Source
```bash
# Clone the repository
git clone https://github.com/zarkentroska/ADAS3-Client.git
cd ADAS3-Client

# Build the project
./gradlew assembleDebug

# The APK will be in: app/build/outputs/apk/debug/
```

## ⚙️ Configuration

### Initial Configuration

When opening the application for the first time, the following default values will be applied:

- **Resolution**: 720x480 (or first available in priority order)
- **Image quality**: 50%
- **FPS**: 30 FPS Mode 2
- **Delay**: 0 ms
- **Audio channels**: Stereo
- **Sample rate**: 44100 Hz
- **Port**: 8080
- **Language**: Spanish

### Access Settings

1. Open the application
2. Tap the **Settings** button (⚙️) in the main interface
3. Configure options according to your needs

### Network Configuration

#### IP Selection
The application automatically detects all available IPs and prioritizes them in this order:
1. **Tailscale** (if available and active)
2. **LAN/Wi-Fi** (local networks)
3. **4G/5G** (mobile data)

You can:
- Manually select any available IP
- Use the **refresh** button (🔄) to update the list without restarting
- Select **ADB** if you have the device connected via USB

#### Connection Port
- Default: **8080**
- Configurable in Settings > Network Settings > Connection Port
- Valid range: 1-65535

### Streaming

Once configured, streaming will be available at:
```
http://[SELECTED_IP]:[PORT]/
```

For example:
- `http://192.168.1.100:8080/` (LAN/Wi-Fi)
- `http://100.64.1.2:8080/` (Tailscale)
- `http://127.0.0.1:8080/` (ADB)

## 🎮 Usage

### Start Streaming
1. Open the application
2. The camera will start automatically
3. Select the desired IP from the dropdown menu
4. Streaming will start automatically
5. Access the displayed URL from any device on the same network

### Audio Control
- Tap the **audio** button (🎤) to enable/disable audio streaming
- Status is shown via toast message

### Tailscale
- If you have Tailscale installed, a switch will appear in the main interface
- Tap the switch to open Tailscale
- Tailscale IPs are automatically detected and prioritized

### ADB Connection
- Connect your device via USB
- Enable USB debugging
- Select "ADB" in the IP menu
- Streaming will be available at `127.0.0.1:8080` on your computer

## 🔒 Security

### HTTP Basic Authentication
You can configure username and password in:
- Settings > HTTP Basic Authentication

### HTTPS/TLS
For secure streaming:
1. Settings > Certificate Settings
2. Enable "Enable TLS/HTTPS"
3. Select your TLS certificate
4. (Optional) Enter the certificate password
5. Restart the application

## 🛠️ Development

### Project Structure
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
│   │       ├── values/ (Spanish)
│   │       ├── values-en/ (English)
│   │       ├── values-fr/ (French)
│   │       ├── values-it/ (Italian)
│   │       └── values-pt/ (Portuguese)
│   └── build.gradle
├── build.gradle.kts
└── settings.gradle.kts
```

### Technologies Used
- **Kotlin** - Programming language
- **AndroidX CameraX** - Camera API
- **AndroidX Preference** - Preferences system
- **Material Design** - UI components
- **Coroutines** - Asynchronous programming

## 📝 License

See [LICENSE](LICENSE) file for details.

## 🔄 Version History

### v0.5 Alpha
- Initial version with all main features
- Multi-language support (5 languages)
- Tailscale integration
- ADB support
- Advanced video and audio configuration
- HTTP and HTTPS authentication
