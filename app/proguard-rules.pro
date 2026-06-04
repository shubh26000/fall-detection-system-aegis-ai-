# ── Aegis Care ProGuard / R8 Rules ──────────────────────────────────────────

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep BuildConfig (contains API key reference at compile time)
-keep class com.example.falldetectionapp.BuildConfig { *; }

# Keep custom View subclasses (used by the UI via reflection/inflation)
-keep class com.example.falldetectionapp.MainActivity$AnimatedLineGraphView { *; }
-keep class com.example.falldetectionapp.MainActivity$CircularRiskMeterView { *; }
-keep class com.example.falldetectionapp.MainActivity$LiquidSOSButton { *; }
-keep class com.example.falldetectionapp.MainActivity$WeatherBackgroundView { *; }
-keep class com.example.falldetectionapp.MainActivity$TypingDotsView { *; }
-keep class com.example.falldetectionapp.MainActivity$ThemeManager { *; }

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep JSON parsing (org.json is part of Android SDK, but keep for safety)
-keep class org.json.** { *; }