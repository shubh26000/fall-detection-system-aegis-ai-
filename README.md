<div align="center">

# 🛡️ Aegis Care

### AI-Powered Fall Detection & Remote Healthcare Monitoring System

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Language-Java-ED8B00?style=for-the-badge&logo=java&logoColor=white)](https://www.java.com)
[![Gemini AI](https://img.shields.io/badge/AI-Gemini-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://ai.google.dev)
[![ESP8266](https://img.shields.io/badge/Hardware-ESP8266-E7352C?style=for-the-badge&logo=espressif&logoColor=white)](https://www.espressif.com)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

*A premium AMOLED liquid-glass Android healthcare app for real-time fall detection, AI-powered health insights, and emergency response.*

</div>

---

## 📖 Table of Contents

- [Overview](#-overview)
- [Features](#-features)
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
- Conversational AI named **Aegis AI** powered by Google Gemini API
- Context-aware: knows patient's name, age, medical condition, and current fall status
- Answers questions on fall prevention, safe movement, medication reminders, and emergency steps
- Animated typing indicator while generating responses
- Chat history preserved across screen switches

### 📡 Smart Fall Detection
- Polls ESP8266 sensor every **1 second** over HTTP
- On fall detection:
  - Looping alarm sound (customizable)
  - Haptic vibration
  - Full-screen red alert banner
  - Liquid-glass emergency bottom sheet
- **4-second cooldown** after "Mark Safe" to prevent false re-triggers
- Automatic re-discovery if sensor connection is lost

### 🔍 Automatic ESP Discovery (UDP Broadcast)
- **Zero configuration** — no hardcoded IP address
- Listens on **UDP port 4444** for ESP broadcast message: `ESP_FALL_DETECTOR:<ip>`
- ESP broadcasts its IP every 3 seconds; app connects automatically
- UI shows live discovery status:
  - `Searching for ESP device...` → `ESP found — monitoring active`
- Re-discovers automatically if connection drops

### 🎨 Dynamic Theme Engine
10 premium neon themes that transform the **entire app atmosphere**:

| Theme | Color |
|-------|-------|
| Cyan (Default) | `#00FFFF` |
| Emerald | `#00FF88` |
| Royal Purple | `#AA44FF` |
| Crimson | `#FF2244` |
| Midnight Gold | `#FFD700` |
| Ocean Blue | `#0088FF` |
| Rose Pink | `#FF44AA` |
| Arctic White | `#E0E8FF` |
| Neon Orange | `#FF6600` |
| Auto AI | Time-based shift |

Every surface responds: hero card gradients, toolbar, navigation bar, card borders, section titles, buttons, graphs, and risk meter.

### 📊 AI Health Report
- **Circular Risk Meter** — animated arc showing fall risk score (0–100%)
- **Animated Line Graph** — daily / weekly / monthly activity trends (swipe to switch)
- **AI Insight Typewriter** — health summary typed character-by-character
- Card dynamically expands to fit any length of AI-generated text

### 🚨 Premium Emergency UX
- **Liquid-glass alert banner** — dark red glass with neon border
- **Slide-up emergency sheet** with:
  - 📞 Call Emergency (red glow button)
  - 👤 View Member Profile (glass button)
  - ✅ Mark as Safe (theme-colored button)
- Tap outside to dismiss with smooth slide-down animation

### 🌤️ AI Weather System
- Real-time weather via **Open-Meteo API** (no API key required)
- Physics-based particle weather animations (rain, snow, sun rays, mist)
- **Aegis Weather Insights** AI card with health-relevant weather advice
- City configurable and saved across restarts

### 👤 Member Profile System
- Patient details: Name, Age, Medical Condition, Blood Group, Emergency Contact, Address, Notes
- Blood group selection via dropdown picker
- Each field displayed as a **styled glass row** (label + value)
- All edits **persisted to SharedPreferences** — survives app restarts

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────┐
│              Aegis Care (Android)            │
│                                             │
│  ┌───────────┐   ┌──────────┐  ┌─────────┐ │
│  │ Gemini AI │   │Open-Meteo│  │  ESP    │ │
│  │   Chat    │   │ Weather  │  │ HTTP    │ │
│  └─────┬─────┘   └────┬─────┘  └────┬────┘ │
│        │              │              │      │
│        └──────────────┴──────────────┘      │
│                 MainActivity                │
│      ┌──────────────────────────────┐       │
│      │    ThemeManager (10 themes)  │       │
│      │    startMonitoring() 1s poll │       │
│      │    discoverESP() UDP :4444   │       │
│      │    Gemini Chat + Context     │       │
│      │    AnimatedLineGraphView     │       │
│      │    CircularRiskMeterView     │       │
│      │    WeatherBackgroundView     │       │
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
| 10 neon color dots | Styled field rows with accent borders | Risk meter + graph + typewriter |

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
GET http://<esp-ip>/reset  → resets fall latch on ESP
```

**Recommended sensors:**
- **MPU6050** — 6-axis accelerometer/gyroscope for fall detection
- Any ESP8266 board (NodeMCU, Wemos D1 Mini, etc.)

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer (JBR / Java 21+)
- Android device running API 26+ (Android 8.0+)
- ESP8266 with fall detection firmware (see hardware section)
- Google Gemini API key (free at [ai.google.dev](https://ai.google.dev))

### Installation

**1. Clone the repository**
```bash
git clone https://github.com/shubh26000/fall-detection-system-aegis-ai-.git
cd fall-detection-system-aegis-ai-
```

**2. Open in Android Studio**
```
File → Open → select the cloned folder
```

**3. Add your Gemini API key**

In `MainActivity.java`, find the Gemini API call and replace with your key:
```java
// Search for "YOUR_API_KEY" or the Gemini endpoint configuration
private static final String GEMINI_API_KEY = "your_key_here";
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
| Theme | Choose from 10 neon themes | SharedPreferences |
| Alarm Sound | Custom ringtone from device | SharedPreferences |
| Emergency Number | Default: 112 | SharedPreferences |
| Weather City | Default: New York | SharedPreferences |
| Member Profile | Name, age, condition, etc. | SharedPreferences |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Java (Android SDK) |
| Build System | Gradle |
| AI | Google Gemini API (REST/JSON) |
| Weather | Open-Meteo API (free, no key) |
| Sensor Protocol | HTTP (polling) + UDP (discovery) |
| UI | Custom Canvas views + GradientDrawable |
| Animations | ObjectAnimator, ValueAnimator, Handler |
| Storage | SharedPreferences |
| Version Control | Git + GitHub |

---

## 📁 Project Structure

```
FallDetectionApp/
├── app/src/main/
│   ├── java/com/example/falldetectionapp/
│   │   └── MainActivity.java          # Main app (3100+ lines)
│   │       ├── ThemeManager           # Theme engine (inner class)
│   │       ├── AnimatedLineGraphView  # Canvas activity graph
│   │       ├── CircularRiskMeterView  # Canvas risk meter
│   │       ├── WeatherBackgroundView  # Canvas weather particles
│   │       ├── LiquidSOSButton        # Canvas SOS button
│   │       ├── TypingDotsView         # AI typing indicator
│   │       └── MemberProfile          # Patient data model
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml      # Main layout (toolbar, nav, containers)
│   │   ├── drawable/                  # Glass card backgrounds, button styles
│   │   ├── values/
│   │   │   ├── colors.xml             # AMOLED neon color palette
│   │   │   ├── strings.xml            # App name and string resources
│   │   │   └── themes.xml             # App theme (AMOLED dark)
│   │   └── raw/
│   │       └── alert.mp3              # Default alarm sound
│   └── AndroidManifest.xml
├── README.md
└── build.gradle
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
