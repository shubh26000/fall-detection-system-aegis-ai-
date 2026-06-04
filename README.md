<div align="center">

# 🛡️ Aegis Care

### AI-Powered Fall Detection & Remote Healthcare Monitoring System

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Language-Java-ED8B00?style=for-the-badge&logo=java&logoColor=white)](https://www.java.com)
[![Gemini AI](https://img.shields.io/badge/AI-Gemini_2.5_Flash-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://ai.google.dev)
[![ESP8266](https://img.shields.io/badge/Hardware-ESP8266-E7352C?style=for-the-badge&logo=espressif&logoColor=white)](https://www.espressif.com)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

*A premium AMOLED liquid-glass Android healthcare app for real-time fall detection, AI-powered health insights, and emergency response — built with enterprise-grade security.*

</div>

---

## 📖 Table of Contents

- [Overview](#-overview)
- [Features](#-features)
- [Security](#-security)
- [System Architecture](#-system-architecture)
- [Screenshots](#-screenshots)
- [Hardware Setup (ESP8266)](#-hardware-setup-esp8266)
- [Getting Started](#-getting-started)
- [Configuration](#-configuration)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Contributing](#-contributing)

---

## 🌟 Overview

**Aegis Care** is an Android application designed for elderly fall detection and remote care monitoring. It pairs with an **ESP8266-based wearable sensor** that broadcasts its IP via UDP — no hardcoded network configuration required. When a fall is detected, the app triggers an alarm, vibrates, and presents an emergency response panel while simultaneously notifying caregivers.

The app is built with a **futuristic AMOLED liquid-glass UI** inspired by Apple Liquid Glass, Samsung One UI, and Google Material You — delivering a premium experience that doesn't compromise on functionality.

> **Target Users:** Elderly patients at fall risk, family caregivers, and healthcare assistants.

---

## ✨ Features

### 🤖 Gemini AI Healthcare Assistant
- Conversational AI named **Aegis AI** powered by **Google Gemini 2.5 Flash**
- **Multi-turn conversation memory** — maintains full chat context across messages (up to 20 turns)
- Context-aware: knows patient's name, age, medical condition, fall history, and current status
- Answers questions on fall prevention, safe movement, medication reminders, and emergency steps
- Animated typing indicator with theme-colored dots while generating responses
- Chat history preserved across screen switches

### 📡 Smart Fall Detection
- Polls ESP8266 sensor every **1 second** over HTTP
- On fall detection:
  - Looping alarm sound (customizable)
  - Haptic vibration
  - Full-screen red alert banner with pulse animation
  - Liquid-glass emergency bottom sheet
- **20-second cooldown** after "Mark Safe" to prevent false re-triggers
- Automatic re-discovery if sensor connection is lost (after 4 consecutive errors)
- Retry reset mechanism for ESP state synchronization

### 🔍 Automatic ESP Discovery (UDP Broadcast)
- **Zero configuration** — no hardcoded IP address
- Listens on **UDP port 4444** for ESP broadcast message: `ESP_FALL_DETECTOR:<ip>`
- **Security validation** — only accepts IPs from private network ranges (RFC 1918)
- ESP broadcasts its IP every 3 seconds; app connects automatically
- UI shows live discovery status with smooth transitions
- Re-discovers automatically if connection drops

### 🎨 Dynamic Theme Engine
10 premium neon themes that transform the **entire app atmosphere**:

| Theme | Color | Emoji |
|-------|-------|-------|
| Cyan AI (Default) | `#00F5D4` | 🩵 |
| Emerald | `#00E676` | 💚 |
| Royal Purple | `#BB86FC` | 💜 |
| Crimson Red | `#FF4D5E` | ❤️ |
| Ocean Blue | `#2196F3` | 🔵 |
| Sunset Orange | `#FF9800` | 🧡 |
| Rose Pink | `#FF80AB` | 🌸 |
| White Frost | `#E0E0E0` | 🤍 |
| Midnight Gold | `#FFD700` | ✨ |
| Auto AI | Time-based shift | 🤖 |

Every surface responds: hero card gradients, toolbar, navigation bar, card borders, section titles, buttons, graphs, and risk meter. Theme selection persists across app restarts.

### 📊 AI Health Report
- **Circular Risk Meter** — animated arc showing fall risk score (0–100%)
- **Animated Line Graph** — daily / weekly / monthly activity trends (swipe to switch)
- **AI Insight Typewriter** — health summary typed character-by-character
- Real fall event data — records timestamps, builds graphs from actual history
- 6-month data retention with automatic pruning

### 🚨 Premium Emergency UX
- **Liquid-glass alert banner** — dark red glass with neon border
- **Slide-up emergency sheet** with:
  - 📞 Call Emergency (red glow button)
  - 👤 View Member Profile (glass button)
  - ✅ Mark as Safe (theme-colored button)
- **Liquid SOS Button** — hold-to-activate with progress arc, ripple animation, and haptic feedback
- Tap outside to dismiss with smooth slide-down animation

### 🌤️ AI Weather System
- Real-time weather via **Open-Meteo API** (no API key required)
- Physics-based particle weather animations (rain streaks, snowflakes, sun rays, mist)
- **Aegis Weather Insights** AI card with health-relevant weather advice
- City configurable and saved across restarts
- Main screen weather icon updates automatically

### 👤 Member Profile System
- Patient details: Name, Age, Medical Condition, Blood Group, Emergency Contact, Address, Notes
- Blood group selection via dropdown picker
- Each field displayed as a **styled glass row** with accent borders
- All inputs **sanitized** — control characters stripped, length limits enforced
- All edits **persisted to encrypted storage** — survives app restarts

### 📱 WiFi Configuration
- In-app wearable WiFi configuration — send new SSID/password directly to the ESP
- Phone WiFi settings shortcut
- Last used WiFi name remembered

---

## 🔒 Security

Aegis Care handles sensitive health data and implements multiple layers of security:

| Feature | Implementation |
|---|---|
| **Encrypted Storage** | AES-256-GCM via `EncryptedSharedPreferences` — all health data, fall events, emergency numbers, and settings encrypted at rest |
| **Automatic Migration** | Existing plaintext data auto-migrates to encrypted storage on first launch |
| **Screenshot Prevention** | `FLAG_SECURE` blocks screenshots, screen recording, and recent apps thumbnails |
| **Network Security** | ESP IP validation against RFC 1918 private ranges — rejects spoofed/public IPs |
| **Response Whitelisting** | ESP HTTP responses restricted to `FALL`, `NORMAL`, `RESET_OK` only |
| **Input Sanitization** | All user inputs stripped of control characters with enforced max lengths |
| **ADB Backup Disabled** | `android:allowBackup="false"` prevents physical data extraction |
| **R8 Code Obfuscation** | Release builds minified and obfuscated via R8/ProGuard |
| **API Key Protection** | Gemini API key stored in `local.properties` (git-ignored), injected via `BuildConfig` |
| **Resource Cleanup** | Proper `onDestroy` lifecycle — Handler, MediaPlayer, Vibrator all cleaned up |
| **Connection Management** | All `HttpURLConnection` instances properly disconnected in `finally` blocks |

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────┐
│              Aegis Care (Android)            │
│                                             │
│  ┌───────────┐   ┌──────────┐  ┌─────────┐ │
│  │ Gemini AI │   │Open-Meteo│  │  ESP    │ │
│  │ 2.5 Flash │   │ Weather  │  │ HTTP    │ │
│  └─────┬─────┘   └────┬─────┘  └────┬────┘ │
│        │              │              │      │
│        └──────────────┴──────────────┘      │
│                 MainActivity                │
│      ┌──────────────────────────────┐       │
│      │  EncryptedSharedPreferences  │       │
│      │  ThemeManager (10 themes)    │       │
│      │  startMonitoring() 1s poll   │       │
│      │  discoverESP() UDP :4444     │       │
│      │  Multi-turn Gemini Chat      │       │
│      │  AnimatedLineGraphView       │       │
│      │  CircularRiskMeterView       │       │
│      │  WeatherBackgroundView       │       │
│      │  LiquidSOSButton             │       │
│      └──────────────────────────────┘       │
└─────────────────────────────────────────────┘
                      ▲
                      │ UDP Broadcast (port 4444)
                      │ HTTP Poll (port 80)
                      │
        ┌─────────────┴─────────────┐
        │     ESP8266 Wearable      │
        │  - MPU6050 Accelerometer  │
        │  - Broadcasts IP via UDP  │
        │  - Serves FALL/NORMAL/OK  │
        │  - WiFi OTA config        │
        └───────────────────────────┘
```

---

## 📱 Screenshots

> *AMOLED liquid-glass UI — dark, premium, futuristic*

| Home Screen | Fall Alert | Emergency Sheet |
|---|---|---|
| Live status, AI chat, health report | Red alert banner + pulsing animation | Slide-up glass panel with actions |

| Theme Picker | Member Profile | AI Health Report |
|---|---|---|
| 10 neon color dots with glow | Styled field rows with accent borders | Risk meter + graph + typewriter |

| Weather Panel | SOS Button | Settings |
|---|---|---|
| Particle animations + AI insights | Hold-to-call with progress arc | Device setup, alarm, themes |

---

## 🔧 Hardware Setup (ESP8266)

The app expects the ESP8266 to:

**1. Broadcast its IP via UDP every 3 seconds:**
```cpp
// UDP broadcast on port 4444
WiFiUDP udp;
udp.beginPacket("255.255.255.255", 4444);
String msg = "ESP_FALL_DETECTOR:" + WiFi.localIP().toString();
udp.print(msg);
udp.endPacket();
```

**2. Serve fall status over HTTP on port 80:**
```
GET http://<esp-ip>/       → returns "FALL" or "NORMAL"
GET http://<esp-ip>/reset  → resets fall latch, returns "RESET_OK"
```

**3. (Optional) Accept WiFi configuration:**
```
GET http://<esp-ip>/config?ssid=<name>&pass=<password> → returns "WIFI_SAVED"
```

**Recommended sensors:**
- **MPU6050** — 6-axis accelerometer/gyroscope for fall detection
- Any ESP8266 board (NodeMCU, Wemos D1 Mini, etc.)

---

## 🚀 Getting Started

### Prerequisites
- Android Studio (Ladybug or newer, JBR / Java 21+)
- Android device running **API 24+** (Android 7.0+)
- ESP8266 with fall detection firmware (see hardware section)
- Google Gemini API key (free at [ai.google.dev](https://ai.google.dev))

### Installation

**1. Clone the repository**
```bash
git clone https://github.com/shubh26000/fall-detection-system-aegis-ai-.git
cd fall-detection-system-aegis-ai-
```

**2. Add your Gemini API key**

Create or edit `local.properties` in the project root:
```properties
GEMINI_API_KEY=your_gemini_api_key_here
```

> ⚠️ **Important:** `local.properties` is git-ignored and never committed. Your API key stays local.

**3. Open in Android Studio**
```
File → Open → select the cloned folder
```

**4. Build & Run**
```bash
./gradlew assembleDebug
# or use Android Studio's Run button (▶)
```

**5. Make sure your phone and ESP8266 are on the same Wi-Fi network**

The app will automatically discover the ESP via UDP — no IP configuration needed.

---

## ⚙️ Configuration

All user-configurable settings are accessible in the **Settings** tab:

| Setting | Description | Storage |
|---|---|---|
| Theme | Choose from 10 neon themes | Encrypted Prefs |
| Alarm Sound | Custom ringtone from device | Encrypted Prefs |
| Emergency Number | Default: 112 (validated 10-digit) | Encrypted Prefs |
| Weather City | Default: New York | Encrypted Prefs |
| Member Profile | Name, age, condition, etc. | Encrypted Prefs |
| Wearable WiFi | Send new WiFi to ESP | Encrypted Prefs |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 11 (Android SDK) |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 36 |
| Build System | Gradle 9.x + AGP |
| AI | Google Gemini 2.5 Flash (REST/JSON) |
| Weather | Open-Meteo API (free, no key) |
| Sensor Protocol | HTTP (polling) + UDP (discovery) |
| Security | AndroidX Security Crypto (AES-256) |
| UI | Custom Canvas views + GradientDrawable |
| Animations | ObjectAnimator, ValueAnimator |
| Storage | EncryptedSharedPreferences |
| Obfuscation | R8 / ProGuard (release builds) |

---

## 📁 Project Structure

```
FallDetectionApp/
├── app/src/main/
│   ├── java/com/example/falldetectionapp/
│   │   └── MainActivity.java          # Main app (~3700 lines)
│   │       ├── ThemeManager           # Theme engine (10 themes + Auto AI)
│   │       ├── AnimatedLineGraphView  # Canvas activity graph with draw animation
│   │       ├── CircularRiskMeterView  # Canvas risk meter arc
│   │       ├── WeatherBackgroundView  # Canvas weather particles (rain/snow/sun)
│   │       ├── LiquidSOSButton        # Canvas hold-to-call SOS button
│   │       ├── TypingDotsView         # AI typing indicator dots
│   │       ├── ChatMessage            # Chat data model
│   │       └── MemberProfile          # Patient data model
│   ├── res/
│   │   ├── layout/activity_main.xml   # Main layout (toolbar, nav, containers)
│   │   ├── drawable/                  # Glass card backgrounds, button styles
│   │   ├── xml/
│   │   │   └── network_security_config.xml  # Network security policy
│   │   ├── values/
│   │   │   ├── colors.xml             # AMOLED neon color palette
│   │   │   ├── strings.xml            # App name and string resources
│   │   │   └── themes.xml             # App theme (AMOLED dark)
│   │   └── raw/alert.mp3             # Default alarm sound
│   └── AndroidManifest.xml            # Permissions, security config
├── app/build.gradle.kts               # Dependencies, R8, security-crypto
├── app/proguard-rules.pro             # R8 keep rules for custom views
├── gradle.properties                  # JVM args, JDK path
├── local.properties                   # API key (git-ignored)
└── README.md
```

---

## 📋 Permissions Used

| Permission | Purpose |
|---|---|
| `INTERNET` | API calls to Gemini, Open-Meteo, ESP8266 |
| `VIBRATE` | Haptic alert on fall detection |
| `CHANGE_WIFI_MULTICAST_STATE` | UDP broadcast reception for ESP discovery |

---

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you'd like to change.

1. Fork the repository
2. Create your feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m 'feat: add your feature'`
4. Push to the branch: `git push origin feature/your-feature`
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**Built with ❤️ for safer, smarter elderly care**

*Aegis Care — Protecting what matters most*

</div>
