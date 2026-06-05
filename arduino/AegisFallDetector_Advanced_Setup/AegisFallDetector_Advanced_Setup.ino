#include <Wire.h>
#include <MPU6050.h>
#include <ESP8266WiFi.h>
#include <WiFiUdp.h>
#include <EEPROM.h>
#include <math.h>

// =============================================
//   DEFAULT / SETUP WIFI
// =============================================
const char* DEFAULT_SSID = "ESPTEST";
const char* DEFAULT_PASS = "12345678";

const char* SETUP_AP_SSID = "AegisSetup";
const char* SETUP_AP_PASS = "12345678";

// =============================================
//   CONFIG AUTH TOKEN
//   The app must send this token with /config
//   requests: /config?token=XXXX&ssid=...&pass=...
//   Change this to a unique value for your device.
// =============================================
const char* CONFIG_AUTH_TOKEN = "aegis2026";

// =============================================
//   STATUS LED (built-in LED on most ESP boards)
//   GPIO2 = D4 on NodeMCU/Wemos D1 Mini
//   Built-in LED is active LOW on most boards
// =============================================
const int LED_PIN = LED_BUILTIN;
const bool LED_ACTIVE_LOW = true;

// =============================================
//   EEPROM LAYOUT
//   [0]       = WIFI_MAGIC marker
//   [1..32]   = SSID (32 bytes)
//   [33..96]  = Password (64 bytes)
//   [97]      = FALL_MAGIC marker
//   [98]      = Fall detected flag (0 or 1)
// =============================================
const int EEPROM_SIZE = 160;
const int MAGIC_ADDR = 0;
const byte WIFI_MAGIC = 0xA7;
const int SSID_ADDR = 1;
const int SSID_MAX = 32;
const int PASS_ADDR = SSID_ADDR + SSID_MAX;
const int PASS_MAX = 64;

// Fall persistence in EEPROM
const int FALL_MAGIC_ADDR = PASS_ADDR + PASS_MAX; // 97
const byte FALL_MAGIC = 0xF1;
const int FALL_FLAG_ADDR = FALL_MAGIC_ADDR + 1;   // 98

// =============================================
//   MPU6050 SCALE SETTINGS
// =============================================
const float ACCEL_DIVISOR = 4096.0; // MPU6050 +/-8G
const float GYRO_DIVISOR  = 65.5;   // MPU6050 +/-500 deg/s

// =============================================
//   ADVANCED FALL DETECTION SETTINGS
// =============================================
const float LOW_G_THRESHOLD          = 0.55;
const float IMPACT_THRESHOLD         = 2.7;
const float HARD_IMPACT_THRESHOLD    = 3.4;
const float STILLNESS_VARIATION_MAX  = 0.22;
const float STILLNESS_GYRO_MAX       = 38.0;
const float ORIENTATION_CHANGE_MIN   = 35.0;

const unsigned long SAMPLE_INTERVAL_MS       = 20;
const unsigned long LOW_G_TO_IMPACT_WINDOW   = 900;
const unsigned long POST_IMPACT_IGNORE_MS    = 250;
const unsigned long STILLNESS_WINDOW_MS      = 1400;
const unsigned long COOLDOWN_MS              = 5000;
const unsigned long IP_BROADCAST_INTERVAL_MS = 3000;
const unsigned long WIFI_CONNECT_TIMEOUT_MS  = 15000;

// Non-blocking WiFi reconnect settings
const unsigned long WIFI_RECONNECT_INTERVAL_MS = 30000; // retry every 30s
const unsigned long WIFI_CHECK_INTERVAL_MS     = 5000;  // check connection every 5s

// =============================================
//   LED BLINK PATTERNS (intervals in ms)
// =============================================
const unsigned long LED_BLINK_SEARCHING   = 500;  // slow blink: searching for WiFi
const unsigned long LED_BLINK_CONNECTED   = 0;    // solid ON: connected
const unsigned long LED_BLINK_FALL        = 100;  // fast blink: fall detected
const unsigned long LED_BLINK_SETUP_AP    = 250;  // medium blink: setup AP mode

// =============================================
//   GLOBALS
// =============================================
WiFiServer server(80);
WiFiUDP udp;
MPU6050 mpu;

bool fallDetected = false;
bool setupApActive = false;
bool wifiConnecting = false;

unsigned long lastFallTime = 0;
unsigned long lastBroadcastTime = 0;
unsigned long lastSampleTime = 0;
unsigned long lastLowGTime = 0;
unsigned long lastWifiCheckTime = 0;
unsigned long lastWifiReconnectAttempt = 0;
unsigned long lastLedToggleTime = 0;
bool ledState = false;

enum FallState {
  MONITORING,
  CONFIRMING
};

FallState fallState = MONITORING;

struct MotionSample {
  float ax, ay, az;
  float accelMag;
  float gx, gy, gz;
  float gyroMag;
};

struct FallCandidate {
  unsigned long startTime;
  bool hadLowG;
  float impactG;
  float beforeX, beforeY, beforeZ;
  float afterX, afterY, afterZ;
  float minG;
  float maxG;
  float gyroSum;
  int samples;
};

FallCandidate candidate;

float stableX = 0.0;
float stableY = 0.0;
float stableZ = 1.0;

// =============================================
//   LED CONTROL
// =============================================
void ledOn() {
  digitalWrite(LED_PIN, LED_ACTIVE_LOW ? LOW : HIGH);
  ledState = true;
}

void ledOff() {
  digitalWrite(LED_PIN, LED_ACTIVE_LOW ? HIGH : LOW);
  ledState = false;
}

void updateLED() {
  unsigned long now = millis();
  unsigned long interval;

  if (fallDetected) {
    interval = LED_BLINK_FALL;
  } else if (setupApActive) {
    interval = LED_BLINK_SETUP_AP;
  } else if (WiFi.status() == WL_CONNECTED) {
    // Solid ON when connected
    ledOn();
    return;
  } else {
    interval = LED_BLINK_SEARCHING;
  }

  if (now - lastLedToggleTime >= interval) {
    lastLedToggleTime = now;
    if (ledState) {
      ledOff();
    } else {
      ledOn();
    }
  }
}

// =============================================
//   EEPROM HELPERS
// =============================================
String readEEPROMString(int start, int maxLen) {
  char buffer[maxLen + 1];
  int i;
  for (i = 0; i < maxLen; i++) {
    byte value = EEPROM.read(start + i);
    if (value == 0 || value == 255) break;
    buffer[i] = (char)value;
  }
  buffer[i] = '\0';
  return String(buffer);
}

void writeEEPROMString(int start, int maxLen, const String& value) {
  for (int i = 0; i < maxLen; i++) {
    byte ch = i < value.length() ? value[i] : 0;
    EEPROM.write(start + i, ch);
  }
}

bool loadWifiCredentials(String& ssid, String& pass) {
  if (EEPROM.read(MAGIC_ADDR) != WIFI_MAGIC) return false;
  ssid = readEEPROMString(SSID_ADDR, SSID_MAX);
  pass = readEEPROMString(PASS_ADDR, PASS_MAX);
  return ssid.length() > 0;
}

void saveWifiCredentials(const String& ssid, const String& pass) {
  EEPROM.write(MAGIC_ADDR, WIFI_MAGIC);
  writeEEPROMString(SSID_ADDR, SSID_MAX, ssid);
  writeEEPROMString(PASS_ADDR, PASS_MAX, pass);
  EEPROM.commit();
}

// =============================================
//   FALL STATE PERSISTENCE (EEPROM)
// =============================================
void saveFallState(bool detected) {
  EEPROM.write(FALL_MAGIC_ADDR, FALL_MAGIC);
  EEPROM.write(FALL_FLAG_ADDR, detected ? 1 : 0);
  EEPROM.commit();
}

bool loadFallState() {
  if (EEPROM.read(FALL_MAGIC_ADDR) != FALL_MAGIC) return false;
  return EEPROM.read(FALL_FLAG_ADDR) == 1;
}

void clearFallState() {
  EEPROM.write(FALL_FLAG_ADDR, 0);
  EEPROM.commit();
}

// =============================================
//   URL HELPERS
// =============================================
String urlDecode(String input) {
  String decoded = "";
  char temp[] = "0x00";

  for (unsigned int i = 0; i < input.length(); i++) {
    char c = input.charAt(i);
    if (c == '+') {
      decoded += ' ';
    } else if (c == '%' && i + 2 < input.length()) {
      temp[2] = input.charAt(i + 1);
      temp[3] = input.charAt(i + 2);
      decoded += (char)strtol(temp, NULL, 16);
      i += 2;
    } else {
      decoded += c;
    }
  }

  return decoded;
}

String getQueryParam(String request, String key) {
  int start = request.indexOf(key + "=");
  if (start < 0) return "";

  start += key.length() + 1;
  int end = request.indexOf('&', start);
  int space = request.indexOf(' ', start);

  if (end < 0 || (space >= 0 && space < end)) end = space;
  if (end < 0) end = request.length();

  return urlDecode(request.substring(start, end));
}

// =============================================
//   WIFI + DISCOVERY
// =============================================
void startSetupAP() {
  if (setupApActive) return;

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASS);
  setupApActive = true;

  Serial.print("Setup hotspot active: ");
  Serial.println(SETUP_AP_SSID);
  Serial.print("Setup IP: ");
  Serial.println(WiFi.softAPIP());
}

bool connectToWiFi(const String& ssid, const String& pass) {
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), pass.c_str());

  Serial.print("Connecting to WiFi: ");
  Serial.println(ssid);

  unsigned long started = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - started < WIFI_CONNECT_TIMEOUT_MS) {
    delay(400);
    Serial.print(".");
    // Feed the watchdog during blocking WiFi connect at boot
    ESP.wdtFeed();
  }

  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    setupApActive = false;
    Serial.print("Connected. IP: ");
    Serial.println(WiFi.localIP());
    return true;
  }

  Serial.println("WiFi connect failed");
  return false;
}

void connectWiFiOrSetupAP() {
  String ssid;
  String pass;

  bool hasSaved = loadWifiCredentials(ssid, pass);
  if (!hasSaved) {
    ssid = DEFAULT_SSID;
    pass = DEFAULT_PASS;
  }

  if (!connectToWiFi(ssid, pass)) {
    startSetupAP();
  }
}

/**
 * Non-blocking WiFi reconnect — called from loop().
 * Only initiates a new connection attempt every WIFI_RECONNECT_INTERVAL_MS.
 * Uses WiFi.begin() which is non-blocking on ESP8266;
 * connection status is checked on subsequent loop() iterations.
 */
void handleWiFiReconnect() {
  unsigned long now = millis();

  // Only check periodically
  if (now - lastWifiCheckTime < WIFI_CHECK_INTERVAL_MS) return;
  lastWifiCheckTime = now;

  // Already connected or in setup AP mode — nothing to do
  if (WiFi.status() == WL_CONNECTED || setupApActive) return;

  // Rate-limit reconnection attempts
  if (now - lastWifiReconnectAttempt < WIFI_RECONNECT_INTERVAL_MS) return;
  lastWifiReconnectAttempt = now;

  Serial.println("WiFi disconnected. Attempting non-blocking reconnect...");

  String ssid;
  String pass;
  bool hasSaved = loadWifiCredentials(ssid, pass);
  if (!hasSaved) {
    ssid = DEFAULT_SSID;
    pass = DEFAULT_PASS;
  }

  // WiFi.begin() on ESP8266 is non-blocking — it starts the connection
  // process and returns immediately. We check WiFi.status() on future loops.
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), pass.c_str());
}

void broadcastOneIP(IPAddress ip) {
  if (ip[0] == 0) return;

  // Use limited broadcast (255.255.255.255) which works on all subnet sizes.
  // Previously used subnet-specific broadcast (x.x.x.255) which only works on /24.
  udp.beginPacket(IPAddress(255, 255, 255, 255), 4444);
  udp.print("ESP_FALL_DETECTOR:");
  udp.print(ip.toString());
  udp.endPacket();
}

void broadcastIP() {
  if (WiFi.status() == WL_CONNECTED) {
    broadcastOneIP(WiFi.localIP());
    Serial.print("Broadcast STA IP: ");
    Serial.println(WiFi.localIP());
  }

  if (setupApActive) {
    broadcastOneIP(WiFi.softAPIP());
    Serial.print("Broadcast setup IP: ");
    Serial.println(WiFi.softAPIP());
  }
}

// =============================================
//   MOTION READ
// =============================================
MotionSample readMotion() {
  int16_t axRaw, ayRaw, azRaw;
  int16_t gxRaw, gyRaw, gzRaw;

  mpu.getMotion6(&axRaw, &ayRaw, &azRaw, &gxRaw, &gyRaw, &gzRaw);

  MotionSample s;
  s.ax = axRaw / ACCEL_DIVISOR;
  s.ay = ayRaw / ACCEL_DIVISOR;
  s.az = azRaw / ACCEL_DIVISOR;

  s.gx = gxRaw / GYRO_DIVISOR;
  s.gy = gyRaw / GYRO_DIVISOR;
  s.gz = gzRaw / GYRO_DIVISOR;

  s.accelMag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az);
  s.gyroMag  = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz);

  return s;
}

// =============================================
//   ORIENTATION HELPERS
// =============================================
void normalizeVector(float &x, float &y, float &z) {
  float mag = sqrt(x * x + y * y + z * z);
  if (mag < 0.001) return;
  x /= mag;
  y /= mag;
  z /= mag;
}

float angleBetweenVectors(float ax, float ay, float az, float bx, float by, float bz) {
  normalizeVector(ax, ay, az);
  normalizeVector(bx, by, bz);
  float dot = constrain(ax * bx + ay * by + az * bz, -1.0, 1.0);
  return acos(dot) * 180.0 / PI;
}

void updateStableOrientation(MotionSample s) {
  if (s.accelMag > 0.75 && s.accelMag < 1.35 && s.gyroMag < 80.0) {
    const float alpha = 0.04;
    stableX = stableX * (1.0 - alpha) + s.ax * alpha;
    stableY = stableY * (1.0 - alpha) + s.ay * alpha;
    stableZ = stableZ * (1.0 - alpha) + s.az * alpha;
    normalizeVector(stableX, stableY, stableZ);
  }
}

// =============================================
//   HTTP CLIENT
// =============================================
void sendPlainResponse(WiFiClient &client, String body) {
  client.println("HTTP/1.1 200 OK");
  client.println("Content-Type: text/plain");
  client.println("Connection: close");
  client.println();
  client.println(body);
}

void handleClient(WiFiClient client) {
  if (!client.connected()) return;

  String request = "";
  unsigned long timeout = millis();

  while (client.connected() && millis() - timeout < 1000) {
    if (client.available()) {
      char c = client.read();
      request += c;
      if (c == '\n') break;
    }
  }

  // Drain any remaining data from the request
  while (client.connected() && client.available()) {
    client.read();
  }

  Serial.print("Request: ");
  Serial.println(request);

  // ── Reset fall state ──────────────────────────
  if (request.indexOf("GET /reset") >= 0) {
    fallDetected = false;
    fallState = MONITORING;
    clearFallState(); // clear persisted fall flag
    sendPlainResponse(client, "RESET_OK");
    client.stop();
    return;
  }

  // ── WiFi config (authenticated) ───────────────
  if (request.indexOf("GET /config") >= 0) {
    // Verify auth token
    String token = getQueryParam(request, "token");
    if (token != CONFIG_AUTH_TOKEN) {
      Serial.println("Config rejected: invalid token");
      sendPlainResponse(client, "AUTH_FAILED");
      client.stop();
      return;
    }

    String newSsid = getQueryParam(request, "ssid");
    String newPass = getQueryParam(request, "pass");

    if (newSsid.length() == 0) {
      sendPlainResponse(client, "WIFI_ERROR");
      client.stop();
      return;
    }

    saveWifiCredentials(newSsid, newPass);
    sendPlainResponse(client, "WIFI_SAVED");
    client.stop();

    Serial.println("New WiFi saved. Restarting...");
    delay(600);
    ESP.restart();
    return;
  }

  // ── Device info ───────────────────────────────
  if (request.indexOf("GET /info") >= 0) {
    String mode = setupApActive ? "SETUP" : "CONNECTED";
    sendPlainResponse(client, "AEGIS:" + mode);
    client.stop();
    return;
  }

  // ── Default: fall status poll ─────────────────
  sendPlainResponse(client, fallDetected ? "FALL" : "NORMAL");
  delay(5);
  client.stop();
}

// =============================================
//   FALL DETECTION STATE MACHINE
// =============================================
void startFallCandidate(MotionSample s) {
  candidate.startTime = millis();
  candidate.hadLowG = (millis() - lastLowGTime) < LOW_G_TO_IMPACT_WINDOW;
  candidate.impactG = s.accelMag;

  candidate.beforeX = stableX;
  candidate.beforeY = stableY;
  candidate.beforeZ = stableZ;

  candidate.afterX = s.ax;
  candidate.afterY = s.ay;
  candidate.afterZ = s.az;

  candidate.minG = 99.0;
  candidate.maxG = 0.0;
  candidate.gyroSum = 0.0;
  candidate.samples = 0;

  fallState = CONFIRMING;
  Serial.println("Impact detected. Confirming fall...");
}

void updateFallCandidate(MotionSample s) {
  unsigned long elapsed = millis() - candidate.startTime;
  if (elapsed < POST_IMPACT_IGNORE_MS) return;

  candidate.minG = min(candidate.minG, s.accelMag);
  candidate.maxG = max(candidate.maxG, s.accelMag);
  candidate.gyroSum += s.gyroMag;
  candidate.samples++;

  candidate.afterX = s.ax;
  candidate.afterY = s.ay;
  candidate.afterZ = s.az;

  if (elapsed >= POST_IMPACT_IGNORE_MS + STILLNESS_WINDOW_MS) {
    float accelVariation = candidate.maxG - candidate.minG;
    float avgGyro = candidate.samples > 0 ? candidate.gyroSum / candidate.samples : 999.0;
    float orientationChange = angleBetweenVectors(
      candidate.beforeX, candidate.beforeY, candidate.beforeZ,
      candidate.afterX, candidate.afterY, candidate.afterZ
    );

    bool stillAfterImpact = accelVariation < STILLNESS_VARIATION_MAX && avgGyro < STILLNESS_GYRO_MAX;
    bool fallEvidence = candidate.hadLowG || orientationChange > ORIENTATION_CHANGE_MIN || candidate.impactG > HARD_IMPACT_THRESHOLD;

    if (stillAfterImpact && fallEvidence) {
      fallDetected = true;
      lastFallTime = millis();
      saveFallState(true); // persist to EEPROM
      Serial.println("*** FALL DETECTED ***");
    } else {
      Serial.println("Rejected: not enough fall evidence");
    }

    fallState = MONITORING;
  }
}

void updateFallDetection() {
  if (fallDetected) return;
  if (millis() - lastFallTime < COOLDOWN_MS) return;
  if (millis() - lastSampleTime < SAMPLE_INTERVAL_MS) return;

  lastSampleTime = millis();
  MotionSample s = readMotion();

  if (s.accelMag < LOW_G_THRESHOLD) {
    lastLowGTime = millis();
  }

  if (fallState == MONITORING) {
    updateStableOrientation(s);
    if (s.accelMag > IMPACT_THRESHOLD) {
      startFallCandidate(s);
    }
  } else {
    updateFallCandidate(s);
  }
}

// =============================================
//   SETUP
// =============================================
void setup() {
  Serial.begin(115200);
  EEPROM.begin(EEPROM_SIZE);

  // Initialize LED
  pinMode(LED_PIN, OUTPUT);
  ledOff();

  // Enable hardware watchdog (8 seconds)
  ESP.wdtEnable(8000);

  Wire.begin(D2, D1);

  mpu.initialize();
  mpu.setFullScaleAccelRange(MPU6050_ACCEL_FS_8);
  mpu.setFullScaleGyroRange(MPU6050_GYRO_FS_500);

  if (mpu.testConnection()) {
    Serial.println("MPU6050 connected");
  } else {
    Serial.println("MPU6050 NOT connected - check wiring");
  }

  // Restore persisted fall state (survives ESP.restart)
  if (loadFallState()) {
    fallDetected = true;
    Serial.println("Restored fall state from EEPROM — fall still active");
  }

  // Initial WiFi connect (blocking at boot — acceptable since no fall monitoring yet)
  connectWiFiOrSetupAP();

  server.begin();
  udp.begin(4444);
  Serial.println("HTTP server started");

  broadcastIP();
}

// =============================================
//   LOOP
// =============================================
void loop() {
  // Feed the hardware watchdog
  ESP.wdtFeed();

  // Non-blocking WiFi reconnect — does NOT block fall detection
  handleWiFiReconnect();

  // Periodic IP broadcast
  if (millis() - lastBroadcastTime > IP_BROADCAST_INTERVAL_MS) {
    broadcastIP();
    lastBroadcastTime = millis();
  }

  // Handle HTTP clients
  WiFiClient client = server.available();
  if (client) handleClient(client);

  // Core fall detection (runs every ~20ms)
  updateFallDetection();

  // Update LED status indicator
  updateLED();

  // Minimal yield to prevent WDT reset
  yield();
}
