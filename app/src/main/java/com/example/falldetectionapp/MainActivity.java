package com.example.falldetectionapp;

import android.animation.ObjectAnimator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    TextView statusText;

    // ESP communication — IP populated at runtime via UDP discovery
    private String espIP = null;
    private boolean discovering = false;

    Handler handler = new Handler();
    boolean fallLatched = false;

    MediaPlayer mediaPlayer;
    Vibrator vibrator;

    private LinearLayout alertCard;
    private LinearLayout screenContainer;
    private LinearLayout bottomNav;
    private ScrollView contentScroll;
    private TextView assistantSummaryText;
    private TextView countdownText;
    private TextView[] navItems;
    private LinearLayout activeChatWindow;
    private LinearLayout activeChatMessagesContainer;
    private ScrollView activeChatMessagesScroll;
    private String selectedTab = "Home";
    private String emergencyNumber = "112";
    private String lastAlert = "No alerts yet";
    private String lastUiSensorState = "";
    private boolean keyboardVisible = false;
    private boolean keepKeyboardAfterChatRefresh = false;
    private boolean tabSwitchAnimating = false;
    private long safeCooldownUntil = 0; // ignore FALL signals until this timestamp
    private final MemberProfile memberProfile = new MemberProfile();
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private SharedPreferences analyticsPrefs;
    private androidx.activity.result.ActivityResultLauncher<Intent> ringtonePickerLauncher;
    private ThemeManager themeManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
        }

        statusText = findViewById(R.id.statusText);
        assistantSummaryText = findViewById(R.id.assistantSummaryText);
        alertCard = findViewById(R.id.alertCard);
        // Style alertCard with liquid-glass AMOLED look
        if (alertCard != null) {
            android.graphics.drawable.GradientDrawable alertBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.argb(120, 80, 0, 0), Color.argb(200, 10, 0, 0)});
            alertBg.setCornerRadius(dp(26));
            alertBg.setStroke(dp(1), Color.argb(160, 200, 0, 0));
            alertCard.setBackground(alertBg);
        }
        screenContainer = findViewById(R.id.screenContainer);
        bottomNav = findViewById(R.id.bottomNav);
        contentScroll = findViewById(R.id.contentScroll);
        countdownText = findViewById(R.id.countdownText);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        analyticsPrefs = getSharedPreferences("fall_analytics", MODE_PRIVATE);
        themeManager = new ThemeManager(analyticsPrefs);

        // Restore persisted member profile (survives app restarts)
        memberProfile.name             = analyticsPrefs.getString("member_name",      memberProfile.name);
        memberProfile.age              = analyticsPrefs.getString("member_age",       memberProfile.age);
        memberProfile.condition        = analyticsPrefs.getString("member_condition", memberProfile.condition);
        memberProfile.emergencyContact = analyticsPrefs.getString("member_emergency", memberProfile.emergencyContact);
        memberProfile.bloodGroup       = analyticsPrefs.getString("member_blood",     memberProfile.bloodGroup);
        memberProfile.address          = analyticsPrefs.getString("member_address",   memberProfile.address);
        memberProfile.notes            = analyticsPrefs.getString("member_notes",     memberProfile.notes);

        ringtonePickerLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                        if (uri != null) {
                            analyticsPrefs.edit().putString("custom_alarm_uri", uri.toString()).apply();
                        } else {
                            analyticsPrefs.edit().remove("custom_alarm_uri").apply();
                        }
                        initializeMediaPlayer();
                        if ("Settings".equals(selectedTab)) {
                            renderSettings();
                        }
                    }
                }
        );

        initializeMediaPlayer();

        chatMessages.add(new ChatMessage("Aegis AI", "How can I help?", false));

        setupNavigation();
        setupEmergencyButtons();
        setupKeyboardAwareScrolling();
        renderHome();
        applyThemeAtmosphere();
        // Show searching status then start UDP discovery
        statusText.setText("Searching for ESP device...");
        assistantSummaryText.setText("Looking for wearable sensor on the network...");
        discoverESP();
    }

    private void initializeMediaPlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
        }
        String customUri = analyticsPrefs.getString("custom_alarm_uri", null);
        if (customUri != null) {
            mediaPlayer = MediaPlayer.create(this, Uri.parse(customUri));
        } else {
            mediaPlayer = MediaPlayer.create(this, R.raw.alert);
        }
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(1f, 1f);
        }
    }

    private void setupKeyboardAwareScrolling() {
        View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visibleFrame = new Rect();
            root.getWindowVisibleDisplayFrame(visibleFrame);
            int screenHeight = root.getRootView().getHeight();
            int keyboardHeight = screenHeight - visibleFrame.bottom;
            boolean keyboardOpen = keyboardHeight > screenHeight * 0.15f;
            keyboardVisible = keyboardOpen;
            if (!keyboardOpen && !keepKeyboardAfterChatRefresh) {
                keepKeyboardAfterChatRefresh = false;
            }

            if (bottomNav != null) {
                bottomNav.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            }

            adjustChatWindow(keyboardOpen);

            if (contentScroll != null) {
                int bottomPadding = keyboardOpen ? keyboardHeight + dp(28) : dp(22);
                contentScroll.setPadding(0, 0, 0, bottomPadding);
                contentScroll.setClipToPadding(false);
            }
        });
    }

    private void adjustChatWindow(boolean expanded) {
        if (activeChatWindow == null) {
            return;
        }

        int targetHeight = expanded ? dp(430) : dp(245);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) activeChatWindow.getLayoutParams();
        if (params.height != targetHeight) {
            params.height = targetHeight;
            activeChatWindow.animate()
                    .alpha(1f)
                    .scaleX(expanded ? 1.01f : 1f)
                    .scaleY(expanded ? 1.01f : 1f)
                    .setInterpolator(new DecelerateInterpolator())
                    .setDuration(240)
                    .start();
            activeChatWindow.setLayoutParams(params);
        }

        if (activeChatMessagesScroll != null) {
            activeChatMessagesScroll.postDelayed(() -> {
                if (activeChatMessagesScroll != null && activeChatMessagesContainer != null) {
                    activeChatMessagesScroll.smoothScrollTo(0, activeChatMessagesContainer.getBottom());
                }
            }, 90);
        }
    }

    private void setupNavigation() {
        navItems = new TextView[]{
                findViewById(R.id.navHome),
                findViewById(R.id.navMonitoring),
                findViewById(R.id.navMembers),
                findViewById(R.id.navEmergency),
                findViewById(R.id.navSettings)
        };

        navItems[0].setOnClickListener(v -> {
            clearKeyboardRetention();
            animateTabSwitch(this::renderHome);
        });
        navItems[1].setOnClickListener(v -> {
            clearKeyboardRetention();
            animateTabSwitch(this::renderMonitoring);
        });
        navItems[2].setOnClickListener(v -> {
            clearKeyboardRetention();
            animateTabSwitch(this::renderMembers);
        });
        navItems[3].setOnClickListener(v -> {
            clearKeyboardRetention();
            animateTabSwitch(this::renderEmergency);
        });
        navItems[4].setOnClickListener(v -> {
            clearKeyboardRetention();
            animateTabSwitch(this::renderSettings);
        });
        updateNavSelection(0);
    }

    private void setupEmergencyButtons() {
        Button callEmergencyButton = findViewById(R.id.callEmergencyButton);
        Button markSafeButton = findViewById(R.id.markSafeButton);

        callEmergencyButton.setOnClickListener(v -> dialEmergencyNumber());
        markSafeButton.setOnClickListener(v -> markAsSafe());
    }

    private void startMonitoring() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                // Skip poll if ESP not yet discovered
                if (espIP == null) {
                    handler.postDelayed(this, 1000);
                    return;
                }

                new Thread(() -> {
                    String data = getESPData();

                    runOnUiThread(() -> {
                        // If we keep getting ERROR, the ESP may have moved — re-discover
                        if (data.equals("ERROR")) {
                            discoverESP();
                        }

                        if (data.equals("FALL") && !fallLatched && System.currentTimeMillis() > safeCooldownUntil) {
                            fallLatched = true;
                            lastUiSensorState = "FALL";
                            lastAlert = "Fall alert at " + new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
                            recordFallEvent(System.currentTimeMillis());

                            statusText.setText("FALL DETECTED");
                            statusText.setTextColor(getColor(R.color.alert_red));
                            assistantSummaryText.setText("Possible fall detected. Please confirm safety or call emergency services.");
                            alertCard.setVisibility(View.VISIBLE);
                            countdownText.setText("Recommendation: call " + emergencyNumber + " if there is no response.");

                            animateText();
                            animateAlertCard();
                            startAlert();
                            showActionPopup();
                            refreshSelectedTab();

                        } else if (!fallLatched && !lastUiSensorState.equals("NORMAL")) {
                            lastUiSensorState = "NORMAL";
                            statusText.setText("Normal");
                            statusText.setTextColor(themeManager.getAccent());
                            assistantSummaryText.setText("Monitoring is active. Your wearable is being checked every second.");
                            alertCard.setVisibility(View.GONE);
                            refreshSelectedTab();
                        }

                    });

                }).start();

                handler.postDelayed(this, 1000);
            }
        };

        handler.post(runnable);
    }

    /** Listens on UDP port 4444 for the ESP broadcast: "ESP_FALL_DETECTOR:192.168.x.x" */
    private void discoverESP() {
        if (discovering) return;
        discovering = true;

        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket(4444);
                socket.setBroadcast(true);
                socket.setSoTimeout(10000); // 10 s per receive attempt

                byte[] buffer = new byte[256];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                while (espIP == null) {
                    try {
                        socket.receive(packet);
                        String message = new String(packet.getData(), 0, packet.getLength());
                        if (message.startsWith("ESP_FALL_DETECTOR:")) {
                            espIP = message.replace("ESP_FALL_DETECTOR:", "").trim();
                            runOnUiThread(() -> {
                                statusText.setText("ESP found — monitoring active");
                                statusText.setTextColor(themeManager.getAccent());
                                assistantSummaryText.setText("Wearable sensor connected. Monitoring every second.");
                                startMonitoring();
                            });
                            break;
                        }
                    } catch (Exception e) {
                        // Receive timed out — loop and try again
                    }
                }
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            discovering = false;
        }).start();
    }

    private void startAlert() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }

        if (vibrator != null) {
            vibrator.vibrate(500);
        }
    }

    private void stopAlert() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
        }

        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    private void showActionPopup() {
        // Build a liquid-glass emergency panel anchored to the bottom of the screen
        android.view.ViewGroup rootView = (android.view.ViewGroup) findViewById(android.R.id.content);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(Color.argb(140, 0, 0, 0));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(24), dp(20), dp(32));

        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(230, 12, 4, 4), Color.argb(245, 5, 0, 0)});
        panelBg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        panelBg.setStroke(dp(1), Color.argb(180, 200, 20, 20));
        panel.setBackground(panelBg);
        panel.setElevation(dp(24));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(panelParams);

        // Drag handle
        android.view.View handle = new android.view.View(this);
        android.graphics.drawable.GradientDrawable handleBg = new android.graphics.drawable.GradientDrawable();
        handleBg.setColor(Color.argb(100, 200, 50, 50));
        handleBg.setCornerRadius(dp(4));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.setMargins(0, 0, 0, dp(20));
        handle.setLayoutParams(handleParams);
        panel.addView(handle);

        // Header
        TextView header = createText("🚨  Fall Detected", 22, R.color.text_primary, true);
        header.setTextColor(Color.parseColor("#FF4444"));
        header.setPadding(0, 0, 0, dp(6));
        panel.addView(header);

        TextView sub = createText("Aegis Care recommends confirming safety or calling emergency services.", 14, R.color.text_secondary, false);
        sub.setLineSpacing(4f, 1f);
        sub.setPadding(0, 0, 0, dp(24));
        panel.addView(sub);

        // Action: Call Emergency
        Button callBtn = new Button(this);
        callBtn.setText("📞  Call Emergency");
        callBtn.setAllCaps(false);
        callBtn.setTextSize(15);
        callBtn.setTypeface(callBtn.getTypeface(), android.graphics.Typeface.BOLD);
        callBtn.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable callBg = new android.graphics.drawable.GradientDrawable();
        callBg.setColor(Color.argb(200, 160, 0, 0));
        callBg.setCornerRadius(dp(16));
        callBg.setStroke(dp(1), Color.argb(180, 255, 60, 60));
        callBtn.setBackground(callBg);
        LinearLayout.LayoutParams callP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        callP.setMargins(0, 0, 0, dp(10));
        callBtn.setLayoutParams(callP);
        callBtn.setOnClickListener(v -> { overlay.setVisibility(View.GONE); rootView.removeView(overlay); dialEmergencyNumber(); });
        panel.addView(callBtn);

        // Action: View Profile
        Button profileBtn = new Button(this);
        profileBtn.setText("👤  View Member Profile");
        profileBtn.setAllCaps(false);
        profileBtn.setTextSize(15);
        profileBtn.setTextColor(getColor(R.color.text_primary));
        android.graphics.drawable.GradientDrawable profileBg = new android.graphics.drawable.GradientDrawable();
        profileBg.setColor(Color.argb(100, 30, 30, 50));
        profileBg.setCornerRadius(dp(16));
        profileBg.setStroke(dp(1), Color.argb(80, 200, 200, 255));
        profileBtn.setBackground(profileBg);
        LinearLayout.LayoutParams profileP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        profileP.setMargins(0, 0, 0, dp(10));
        profileBtn.setLayoutParams(profileP);
        profileBtn.setOnClickListener(v -> showMemberDetailsUI());
        panel.addView(profileBtn);

        // Action: Mark Safe
        Button safeBtn = new Button(this);
        safeBtn.setText("✅  Mark as Safe");
        safeBtn.setAllCaps(false);
        safeBtn.setTextSize(15);
        safeBtn.setTypeface(safeBtn.getTypeface(), android.graphics.Typeface.BOLD);
        safeBtn.setTextColor(Color.parseColor("#111111"));
        android.graphics.drawable.GradientDrawable safeBg = new android.graphics.drawable.GradientDrawable();
        safeBg.setColor(themeManager.getAccent());
        safeBg.setCornerRadius(dp(16));
        safeBtn.setBackground(safeBg);
        LinearLayout.LayoutParams safeP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        safeBtn.setLayoutParams(safeP);
        safeBtn.setOnClickListener(v -> { overlay.setVisibility(View.GONE); rootView.removeView(overlay); markAsSafe(); });
        panel.addView(safeBtn);

        overlay.addView(panel);
        rootView.addView(overlay);

        // Slide up animation
        panel.setTranslationY(dp(400));
        panel.animate().translationY(0).setDuration(320)
                .setInterpolator(new DecelerateInterpolator()).start();

        // Tapping outside dismisses
        overlay.setOnClickListener(v -> {
            panel.animate().translationY(dp(400)).setDuration(260).withEndAction(() -> {
                overlay.setVisibility(View.GONE);
                rootView.removeView(overlay);
            }).start();
        });
        panel.setOnClickListener(v -> { /* consume so taps don't dismiss */ });
    }

    // Legacy kept for menu fallback (not called anymore)
    private void showActionMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Emergency Actions")
                .setItems(new String[]{"Call Emergency", "View Member Profile", "Mark as Safe"},
                        (dialog, which) -> {
                            if (which == 0) dialEmergencyNumber();
                            if (which == 1) showMemberDetailsUI();
                            if (which == 2) markAsSafe();
                        })
                .show();
    }

    private void markAsSafe() {
        stopAlert();
        fallLatched = false;
        lastUiSensorState = "NORMAL";
        // 4-second cooldown: ignore any FALL signals right after reset
        safeCooldownUntil = System.currentTimeMillis() + 4000;
        // Send reset to ESP off the main thread
        new Thread(this::sendReset).start();

        statusText.setText("Safe - Monitoring");
        statusText.setTextColor(getColor(R.color.safe_green));
        assistantSummaryText.setText("Safety confirmed. I am back to continuous monitoring.");
        alertCard.setVisibility(View.GONE);
        refreshSelectedTab();
    }

    private void dialEmergencyNumber() {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:" + emergencyNumber));
        startActivity(intent);
    }

    private void animateText() {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(statusText, "scaleX", 1f, 1.12f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(statusText, "scaleY", 1f, 1.12f, 1f);

        scaleX.setDuration(620);
        scaleY.setDuration(620);

        scaleX.start();
        scaleY.start();
    }

    private void animateAlertCard() {
        ObjectAnimator pulse = ObjectAnimator.ofFloat(alertCard, "alpha", 0.62f, 1f);
        pulse.setDuration(520);
        pulse.setRepeatCount(3);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();
    }

    public String getESPData() {
        if (espIP == null) return "ERROR";
        try {
            URL url = new URL("http://" + espIP);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private void sendReset() {
        if (espIP == null) return;
        try {
            URL url = new URL("http://" + espIP + "/reset");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.getInputStream();
        } catch (Exception ignored) {}
    }

    private void renderHome() {
        selectedTab = "Home";
        updateNavSelection(0);
        resetScreen();
        updateGreetingUI();
        if (fallLatched) {
            addAssistantMessage("Recommendation", "Confirm safety, keep the phone nearby, and call " + emergencyNumber + " if needed.");
        } else {
            addRecommendationPanel();
        }
        addChatPanel();
        addUnifiedAIHealthReport();
        fadeScreenIn();
    }

    private void updateGreetingUI() {
        TextView greetingText = findViewById(R.id.greetingText);
        TextView weatherIcon = findViewById(R.id.weatherIcon);
        if (greetingText == null || weatherIcon == null) return;
        
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        
        if (hour >= 5 && hour < 12) {
            greetingText.setText("Good Morning");
            weatherIcon.setText("☀️");
        } else if (hour >= 12 && hour < 17) {
            greetingText.setText("Good Afternoon");
            weatherIcon.setText("🌤️");
        } else if (hour >= 17 && hour < 21) {
            greetingText.setText("Good Evening");
            weatherIcon.setText("🌥️");
        } else {
            greetingText.setText("Good Night");
            weatherIcon.setText("🌙");
        }
        
        weatherIcon.setOnClickListener(v -> showWeatherPanel());
        greetingText.setOnClickListener(v -> showWeatherPanel());
    }
    
    private void addRecommendationPanel() {
        LinearLayout card = createCard();

        TextView sectionTitle = createText("AI Recommendations", 14, R.color.neon_cyan, true);
        sectionTitle.setTextColor(themeManager.getAccent());
        sectionTitle.setPadding(0, 0, 0, dp(12));
        card.addView(sectionTitle);

        String[][] recommendations = {
            {"💧", "Hydration Reminder", "Stay hydrated today."},
            {"🌙", "Night Safety", "Keep pathways illuminated."},
            {"🔋", "Charging Reminder", "Wearable battery low."},
            {"🌧️", "Weather Alert", "Rain expected, avoid slippery surfaces."},
            {"🚶", "Walking Suggestion", "Light movement improves circulation."}
        };

        int idx = (int) (Math.random() * recommendations.length);
        String[] rec = recommendations[idx];

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView iconView = createText(rec[0], 28, R.color.text_primary, false);
        iconView.setPadding(0, 0, dp(16), 0);
        row.addView(iconView);

        ObjectAnimator pulse = ObjectAnimator.ofFloat(iconView, "scaleX", 1f, 1.15f, 1f);
        pulse.setDuration(1200);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(iconView, "scaleY", 1f, 1.15f, 1f);
        pulseY.setDuration(1200);
        pulseY.setRepeatCount(ObjectAnimator.INFINITE);
        pulseY.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();
        pulseY.start();

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        
        TextView title = createText(rec[1], 15, R.color.text_primary, true);
        TextView desc = createText(rec[2], 13, R.color.text_secondary, false);
        desc.setPadding(0, dp(4), 0, 0);

        textCol.addView(title);
        textCol.addView(desc);
        row.addView(textCol);

        card.addView(row);
        screenContainer.addView(card);
    }
    
    private void showWeatherPanel() {
        FrameLayout panel = new FrameLayout(this);
        panel.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setElevation(dp(20));

        WeatherBackgroundView bgView = new WeatherBackgroundView(this);
        panel.addView(bgView, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(40), dp(24), dp(24));
        
        android.graphics.drawable.GradientDrawable glassBg = new android.graphics.drawable.GradientDrawable();
        glassBg.setColor(Color.parseColor("#E60A0A0A")); // extremely dark translucent glass
        container.setBackground(glassBg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView title = createText("AI Weather Assistant", 18, R.color.neon_cyan, true);
        title.setTextColor(themeManager.getAccent());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleParams);

        TextView closeBtn = createText("✕", 24, R.color.text_primary, true);
        closeBtn.setPadding(dp(16), dp(16), dp(16), dp(16));
        closeBtn.setOnClickListener(v -> {
            panel.animate().translationY(getResources().getDisplayMetrics().heightPixels).setDuration(300).withEndAction(() -> {
                ((android.view.ViewGroup)findViewById(android.R.id.content)).removeView(panel);
            }).start();
        });
        header.addView(closeBtn);
        container.addView(header);

        String savedCity = analyticsPrefs.getString("weather_city", "New York");
        EditText cityInput = createStyledInput("Enter City", savedCity);
        cityInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        container.addView(cityInput);

        Button fetchBtn = createButton("Update Location", R.drawable.button_outline, R.color.text_primary);
        container.addView(fetchBtn);

        LinearLayout weatherDisplay = new LinearLayout(this);
        weatherDisplay.setOrientation(LinearLayout.VERTICAL);
        weatherDisplay.setGravity(Gravity.CENTER);
        weatherDisplay.setPadding(0, dp(40), 0, dp(40));

        TextView wIcon = createText("⏳", 72, R.color.text_primary, false);
        TextView wTemp = createText("--°C", 48, R.color.text_primary, true);
        TextView wDesc = createText("Loading...", 18, R.color.text_secondary, false);

        weatherDisplay.addView(wIcon);
        weatherDisplay.addView(wTemp);
        weatherDisplay.addView(wDesc);
        container.addView(weatherDisplay);

        LinearLayout aiCard = createCard();
        aiCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView aiTitle = createText("Aegis Weather Insights", 14, R.color.neon_cyan, true);
        aiTitle.setTextColor(themeManager.getAccent());
        aiTitle.setPadding(0, 0, 0, dp(8));
        aiCard.addView(aiTitle);
        TextView aiDesc = createText("Analyzing conditions...", 14, R.color.text_primary, false);
        aiDesc.setLineSpacing(4f, 1f);
        aiCard.addView(aiDesc);
        container.addView(aiCard);

        panel.addView(container, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        fetchBtn.setOnClickListener(v -> {
            String city = cityInput.getText().toString().trim();
            if (!city.isEmpty()) {
                analyticsPrefs.edit().putString("weather_city", city).apply();
                fetchWeatherAdvanced(city, wIcon, wTemp, wDesc, bgView, aiDesc);
            }
        });

        ((android.view.ViewGroup)findViewById(android.R.id.content)).addView(panel);
        panel.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        panel.animate().translationY(0f).setInterpolator(new DecelerateInterpolator()).setDuration(400).start();

        fetchWeatherAdvanced(savedCity, wIcon, wTemp, wDesc, bgView, aiDesc);
    }

    private void fetchWeatherAdvanced(String city, TextView wIcon, TextView wTemp, TextView wDesc, WeatherBackgroundView bgView, TextView aiDesc) {
        wIcon.setText("⏳");
        wIcon.clearAnimation();
        wTemp.setText("--°C");
        wDesc.setText("Fetching...");
        aiDesc.setText("Analyzing conditions...");

        new Thread(() -> {
            try {
                String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + Uri.encode(city) + "&count=1&language=en&format=json";
                HttpURLConnection geoConn = (HttpURLConnection) new URL(geoUrl).openConnection();
                geoConn.setConnectTimeout(5000);
                
                BufferedReader geoReader = new BufferedReader(new InputStreamReader(geoConn.getInputStream()));
                StringBuilder geoRes = new StringBuilder();
                String line;
                while ((line = geoReader.readLine()) != null) geoRes.append(line);
                geoReader.close();

                JSONObject geoJson = new JSONObject(geoRes.toString());
                if (!geoJson.has("results")) {
                    runOnUiThread(() -> {
                        wIcon.setText("❓");
                        wDesc.setText("City not found");
                        aiDesc.setText("Unable to fetch insights for this location.");
                    });
                    return;
                }
                JSONObject location = geoJson.getJSONArray("results").getJSONObject(0);
                double lat = location.getDouble("latitude");
                double lon = location.getDouble("longitude");
                String resolvedName = location.getString("name");

                String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true";
                HttpURLConnection wConn = (HttpURLConnection) new URL(weatherUrl).openConnection();
                wConn.setConnectTimeout(5000);
                
                BufferedReader wReader = new BufferedReader(new InputStreamReader(wConn.getInputStream()));
                StringBuilder wRes = new StringBuilder();
                while ((line = wReader.readLine()) != null) wRes.append(line);
                wReader.close();

                JSONObject wJson = new JSONObject(wRes.toString());
                JSONObject current = wJson.getJSONObject("current_weather");
                double temp = current.getDouble("temperature");
                int code = current.getInt("weathercode");
                int isDay = current.getInt("is_day");

                String icon = "☁️";
                String desc = "Cloudy";
                String aiText = "Conditions are mild. Stay active.";
                
                if (code == 0) {
                    icon = isDay == 1 ? "☀️" : "🌙";
                    desc = "Clear sky";
                    aiText = isDay == 1 ? "Clear weather detected. Great day for a light walk outdoors. Stay hydrated!" : "Cool night conditions detected. Keep pathways well illuminated.";
                } else if (code == 1 || code == 2 || code == 3) {
                    icon = isDay == 1 ? "🌤️" : "☁️";
                    desc = "Partly cloudy";
                    aiText = "Mild overcast. Perfect weather for light exercise.";
                } else if (code >= 51 && code <= 67) {
                    icon = "🌧️";
                    desc = "Rain";
                    aiText = "Rain expected. Avoid slippery pathways and wear shoes with good grip if stepping out.";
                } else if (code >= 71 && code <= 77) {
                    icon = "❄️";
                    desc = "Snow";
                    aiText = "Snow detected. High fall risk outdoors. Please stay indoors and keep warm.";
                } else if (code >= 95) {
                    icon = "⛈️";
                    desc = "Thunderstorm";
                    aiText = "Thunderstorms active. Unsafe conditions. Remain indoors.";
                }

                String finalIcon = icon;
                String finalDesc = desc;
                String finalAiText = aiText;

                runOnUiThread(() -> {
                    wIcon.setText(finalIcon);
                    wTemp.setText(temp + "°C");
                    wDesc.setText(finalDesc + " in " + resolvedName);
                    aiDesc.setText(finalAiText);
                    
                    animateWeatherIcon(wIcon, finalIcon);
                    bgView.setWeather(code, isDay == 1);
                    
                    TextView mainIcon = findViewById(R.id.weatherIcon);
                    if (mainIcon != null) {
                        mainIcon.setText(finalIcon);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    wIcon.setText("⚠️");
                    wDesc.setText("Network error");
                    aiDesc.setText("Cannot reach Aegis weather servers.");
                });
            }
        }).start();
    }
    
    private void animateWeatherIcon(TextView iconView, String iconType) {
        iconView.clearAnimation();
        if (iconType.equals("☀️") || iconType.equals("🌙")) {
            ObjectAnimator pulseX = ObjectAnimator.ofFloat(iconView, "scaleX", 1f, 1.2f, 1f);
            ObjectAnimator pulseY = ObjectAnimator.ofFloat(iconView, "scaleY", 1f, 1.2f, 1f);
            pulseX.setDuration(3000); pulseY.setDuration(3000);
            pulseX.setRepeatCount(ObjectAnimator.INFINITE); pulseY.setRepeatCount(ObjectAnimator.INFINITE);
            pulseX.start(); pulseY.start();
        } else if (iconType.equals("🌧️") || iconType.equals("❄️") || iconType.equals("⛈️")) {
            ObjectAnimator slide = ObjectAnimator.ofFloat(iconView, "translationY", -10f, 10f);
            slide.setDuration(1500);
            slide.setRepeatCount(ObjectAnimator.INFINITE);
            slide.setRepeatMode(ObjectAnimator.REVERSE);
            slide.start();
        } else {
            ObjectAnimator drift = ObjectAnimator.ofFloat(iconView, "translationX", -10f, 10f);
            drift.setDuration(4000);
            drift.setRepeatCount(ObjectAnimator.INFINITE);
            drift.setRepeatMode(ObjectAnimator.REVERSE);
            drift.start();
        }
    }

    private void renderMonitoring() {
        selectedTab = "Monitoring";
        updateNavSelection(1);
        resetScreen();
        addSectionTitle("Smart Fall Assistant");
        
        addLiveCareStatusPanel();
        addCareGuidancePanel();
        addUnifiedAIHealthReport();
        fadeScreenIn();
    }

    private void addLiveCareStatusPanel() {
        LinearLayout card = createCard();
        
        TextView title = createText("Live Care Status", 14, R.color.neon_cyan, true);
        title.setTextColor(themeManager.getAccent());
        title.setPadding(0, 0, 0, dp(12));
        card.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = createText(fallLatched ? "⚠️" : "❤️", 36, fallLatched ? R.color.alert_red : R.color.safe_green, false);
        icon.setPadding(0, 0, dp(16), 0);
        row.addView(icon);

        ObjectAnimator pulse = ObjectAnimator.ofFloat(icon, "scaleX", 1f, fallLatched ? 1.3f : 1.15f, 1f);
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(icon, "scaleY", 1f, fallLatched ? 1.3f : 1.15f, 1f);
        pulse.setDuration(fallLatched ? 400 : 900);
        pulseY.setDuration(fallLatched ? 400 : 900);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulseY.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulseY.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();
        pulseY.start();

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        String status = fallLatched ? "Emergency Triggered" : "Monitoring Active";
        String desc = fallLatched ? "Alert systems activated. Awaiting confirmation." : "Stable telemetry. All sensors nominal.";
        
        TextView statusView = createText(status, 16, fallLatched ? R.color.alert_red : R.color.safe_green, true);
        TextView descView = createText(desc, 13, R.color.text_secondary, false);
        descView.setPadding(0, dp(4), 0, dp(8));

        textCol.addView(statusView);
        textCol.addView(descView);

        TextView waveform = createText(fallLatched ? "ılı|ı|ıl|ı|ılı" : "ılıılıılıılıılı", 14, fallLatched ? R.color.alert_red : R.color.soft_cyan, false);
        if (!fallLatched) waveform.setTextColor(themeManager.getAccent());
        waveform.setLetterSpacing(0.2f);
        textCol.addView(waveform);
        
        ObjectAnimator waveAnim = ObjectAnimator.ofFloat(waveform, "alpha", 0.4f, 1f, 0.4f);
        waveAnim.setDuration(1200);
        waveAnim.setRepeatCount(ObjectAnimator.INFINITE);
        waveAnim.start();

        row.addView(textCol);
        card.addView(row);
        screenContainer.addView(card);
    }

    private void addCareGuidancePanel() {
        TextView title = createText("Care Guidance", 14, R.color.soft_cyan, true);
        title.setTextColor(themeManager.getAccent());
        title.setPadding(0, dp(8), 0, dp(12));
        screenContainer.addView(title);

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout carousel = new LinearLayout(this);
        carousel.setOrientation(LinearLayout.HORIZONTAL);

        String[][] tips = {
            {"💧", "Hydration", "Keep water nearby."},
            {"🚶", "Movement", "Walk carefully."},
            {"🔋", "Battery", "Sufficient for night."},
            {"💡", "Lighting", "Keep paths lit."}
        };

        if (fallLatched) {
            tips = new String[][]{
                {"📞", "Action", "Speak calmly."},
                {"🛑", "Do Not Move", "Check for pain."},
                {"🚑", "Help", "Call emergency if no reply."}
            };
        }

        for (int i = 0; i < tips.length; i++) {
            LinearLayout miniCard = new LinearLayout(this);
            miniCard.setOrientation(LinearLayout.VERTICAL);
            
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(16));
            gd.setColor(Color.parseColor("#151515"));
            gd.setStroke(dp(1), fallLatched ? Color.parseColor("#44AA0000") : themeManager.getAccentGlow());
            miniCard.setBackground(gd);
            
            miniCard.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(140), LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMargins(0, 0, dp(12), dp(16));
            miniCard.setLayoutParams(p);

            TextView icon = createText(tips[i][0], 24, R.color.text_primary, false);
            icon.setPadding(0, 0, 0, dp(8));
            ObjectAnimator floatAnim = ObjectAnimator.ofFloat(icon, "translationY", -4f, 4f);
            floatAnim.setDuration(1500 + (i * 200));
            floatAnim.setRepeatCount(ObjectAnimator.INFINITE);
            floatAnim.setRepeatMode(ObjectAnimator.REVERSE);
            floatAnim.start();

            TextView tTitle = createText(tips[i][1], 14, R.color.text_primary, true);
            TextView tDesc = createText(tips[i][2], 12, R.color.text_secondary, false);
            tDesc.setPadding(0, dp(4), 0, 0);

            miniCard.addView(icon);
            miniCard.addView(tTitle);
            miniCard.addView(tDesc);
            carousel.addView(miniCard);
        }

        hScroll.addView(carousel);
        screenContainer.addView(hScroll);
    }

    private void renderMembers() {
        selectedTab = "Members";
        updateNavSelection(2);
        resetScreen();
        addSectionTitle("Member Profile");
        addProfileCard();
        Button addMember = createThemedButton("Edit Member");
        addMember.setOnClickListener(v -> showMemberEditor());
        screenContainer.addView(addMember);
        fadeScreenIn();
    }

    private void renderEmergency() {
        selectedTab = "Emergency";
        updateNavSelection(3);
        resetScreen();
        
        if (fallLatched) {
            screenContainer.setBackgroundColor(Color.parseColor("#1A330000"));
        }
        
        addSectionTitle("Emergency");
        
        addEmergencyRecommendations();
        addEmergencyActionButton();
        
        if (fallLatched) {
            Button safeButton = createThemedButton("Mark Safe");
            safeButton.setOnClickListener(v -> markAsSafe());
            screenContainer.addView(safeButton);
        }
        fadeScreenIn();
    }

    private void addEmergencyRecommendations() {
        LinearLayout card = createCard();
        
        TextView title = createText("AI Analysis", 14, R.color.neon_cyan, true);
        title.setTextColor(themeManager.getAccent());
        title.setPadding(0, 0, 0, dp(12));
        card.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = createText(fallLatched ? "🚨" : "🛡️", 36, R.color.text_primary, false);
        icon.setPadding(0, 0, dp(16), 0);
        row.addView(icon);

        ObjectAnimator floatAnim = ObjectAnimator.ofFloat(icon, "translationY", -5f, 5f);
        floatAnim.setDuration(1200);
        floatAnim.setRepeatCount(ObjectAnimator.INFINITE);
        floatAnim.setRepeatMode(ObjectAnimator.REVERSE);
        floatAnim.start();

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        String mainTxt = fallLatched ? "Fall Alert Verified" : "No Active Emergency";
        String subTxt = fallLatched ? "Call emergency services if the member does not respond." : "Everything is normal. Fast actions are ready if needed.";
        
        TextView mainView = createText(mainTxt, 16, fallLatched ? R.color.alert_red : R.color.safe_green, true);
        TextView subView = createText(subTxt, 13, R.color.text_secondary, false);
        subView.setPadding(0, dp(4), 0, 0);

        textCol.addView(mainView);
        textCol.addView(subView);
        row.addView(textCol);
        
        card.addView(row);
        screenContainer.addView(card);
    }

    private void addEmergencyActionButton() {
        LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        btnContainer.setPadding(0, dp(40), 0, dp(40));

        LiquidSOSButton sosButton = new LiquidSOSButton(this);
        int btnSize = dp(240);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(btnSize, btnSize);
        sosButton.setLayoutParams(params);

        sosButton.setOnTriggerListener(() -> {
            if (!fallLatched) {
                fallLatched = true;
                renderEmergency();
            } else {
                screenContainer.setBackgroundColor(Color.parseColor("#44AA0000"));
            }
            dialEmergencyNumber();
        });

        btnContainer.addView(sosButton);
        screenContainer.addView(btnContainer);
    }

    private void renderSettings() {
        selectedTab = "Settings";
        updateNavSelection(4);
        resetScreen();
        addSectionTitle("Settings");
        addInfoCard("Emergency number", emergencyNumber, R.color.soft_cyan);
        addInfoCard("Sensitivity mode", "Medium\nBalanced for daily movement.", R.color.warning_gold);

        // ── Appearance & Theme card ──────────────────────────────────────────────
        LinearLayout themeCard = createCard();

        LinearLayout themeHeader = new LinearLayout(this);
        themeHeader.setOrientation(LinearLayout.HORIZONTAL);
        themeHeader.setGravity(Gravity.CENTER_VERTICAL);
        themeHeader.setPadding(0, 0, 0, dp(12));

        TextView themeTitle = createText("Appearance & Theme", 16, R.color.neon_cyan, true);
        themeTitle.setTextColor(themeManager.getAccent());
        LinearLayout.LayoutParams ttParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        themeHeader.addView(themeTitle, ttParams);

        ThemeManager.AppTheme active = themeManager.getActive();
        TextView activeLabel = new TextView(this);
        activeLabel.setText(active.emoji + " " + active.name);
        activeLabel.setTextSize(13);
        activeLabel.setTextColor(themeManager.getAccent());
        themeHeader.addView(activeLabel);

        themeCard.addView(themeHeader);

        TextView themeDesc = createText("Personalize your neon glow. Tap to preview and switch between 10 premium liquid-glass themes.", 13, R.color.text_secondary, false);
        themeDesc.setLineSpacing(4f, 1f);
        themeDesc.setPadding(0, 0, 0, dp(16));
        themeCard.addView(themeDesc);

        // Small color preview row
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setPadding(0, 0, 0, dp(16));
        for (ThemeManager.AppTheme t : ThemeManager.THEMES) {
            View dot = new View(this) {
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                { p.setColor(t.accentColor); setLayerType(LAYER_TYPE_SOFTWARE, null); }
                @Override protected void onDraw(Canvas canvas) {
                    float c = Math.min(getWidth(), getHeight()) / 2f;
                    p.setAlpha(t.accentColor == themeManager.getAccent() ? 255 : 120);
                    canvas.drawCircle(getWidth()/2f, getHeight()/2f, c - dp(2), p);
                }
            };
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(this.dp(22), this.dp(22));
            dp.setMargins(0, 0, this.dp(8), 0);
            previewRow.addView(dot, dp);
        }
        themeCard.addView(previewRow);

        Button chooseThemeBtn = new Button(this);
        chooseThemeBtn.setText("✦ Choose Theme");
        chooseThemeBtn.setAllCaps(false);
        chooseThemeBtn.setTextSize(15);
        chooseThemeBtn.setTextColor(Color.parseColor("#111111"));
        chooseThemeBtn.setTypeface(chooseThemeBtn.getTypeface(), android.graphics.Typeface.BOLD);

        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(themeManager.getAccent());
        btnBg.setCornerRadius(dp(18));
        chooseThemeBtn.setBackground(btnBg);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        chooseThemeBtn.setLayoutParams(btnParams);
        chooseThemeBtn.setOnClickListener(v -> showThemePickerPanel());
        themeCard.addView(chooseThemeBtn);
        screenContainer.addView(themeCard);
        // ────────────────────────────────────────────────────────────────────────

        addAlarmSoundCard();

        Button editEmergency = createButton("Change Emergency Number", R.drawable.button_outline, R.color.text_primary);
        editEmergency.setOnClickListener(v -> showEmergencyNumberDialog());
        screenContainer.addView(editEmergency);
        fadeScreenIn();
    }


    private void addAlarmSoundCard() {
        LinearLayout card = createCard();

        TextView title = createText("Alarm Sound", 16, R.color.neon_cyan, true);
        title.setTextColor(themeManager.getAccent());
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title);

        String customUri = analyticsPrefs.getString("custom_alarm_uri", null);
        String soundStatus = customUri != null ? "Custom Tone Selected" : "Default Continuous Alarm";
        TextView status = createText("Current: " + soundStatus, 14, R.color.text_primary, false);
        status.setPadding(0, 0, 0, dp(16));
        card.addView(status);

        LinearLayout buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);

        Button changeBtn = createThemedButton("Change Sound");
        changeBtn.setOnClickListener(v -> {
            Intent intent = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
            intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM | android.media.RingtoneManager.TYPE_RINGTONE | android.media.RingtoneManager.TYPE_NOTIFICATION);
            intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
            if (customUri != null) {
                intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(customUri));
            }
            ringtonePickerLauncher.launch(intent);
        });

        Button previewBtn = createButton("Preview", R.drawable.button_outline, R.color.text_primary);
        previewBtn.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    mediaPlayer.seekTo(0);
                    previewBtn.setText("Preview");
                } else {
                    mediaPlayer.start();
                    previewBtn.setText("Stop");
                }
            }
        });

        LinearLayout.LayoutParams btnParams1 = new LinearLayout.LayoutParams(0, dp(54), 1f);
        btnParams1.setMargins(0, 0, dp(8), dp(12));
        changeBtn.setLayoutParams(btnParams1);
        buttonsRow.addView(changeBtn);

        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, dp(54), 1f);
        btnParams2.setMargins(0, 0, 0, dp(12));
        previewBtn.setLayoutParams(btnParams2);
        buttonsRow.addView(previewBtn);

        card.addView(buttonsRow);

        if (customUri != null) {
            Button resetBtn = createButton("Reset to Default", R.drawable.button_outline, R.color.alert_red);
            resetBtn.setOnClickListener(v -> {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
                analyticsPrefs.edit().remove("custom_alarm_uri").apply();
                initializeMediaPlayer();
                renderSettings();
            });
            card.addView(resetBtn);
        }

        screenContainer.addView(card);
    }

    private void renderChat() {
        selectedTab = "Chat";
        resetScreen();
        for (TextView navItem : navItems) {
            navItem.setSelected(false);
            navItem.setTextColor(getColor(R.color.text_secondary));
        }

        addInfoCard("Aegis AI is online", "Ask about fall prevention, safe movement, emergency steps, medicine reminders, or daily care.", R.color.neon_cyan);
        addChatPanel();
        fadeScreenIn();
    }

    private void addChatPanel() {
        activeChatWindow = new LinearLayout(this);
        activeChatWindow.setOrientation(LinearLayout.VERTICAL);
        activeChatWindow.setBackgroundResource(R.drawable.chat_window_glass);
        activeChatWindow.setElevation(dp(10));
        activeChatWindow.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout.LayoutParams windowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keyboardVisible ? dp(430) : dp(245)
        );
        windowParams.setMargins(0, dp(8), 0, dp(14));
        activeChatWindow.setLayoutParams(windowParams);

        TextView title = createText("Aegis", 17, R.color.text_primary, true);
        TextView subtitle = createText("How can I help?", 12, R.color.text_secondary, false);
        subtitle.setPadding(0, dp(2), 0, dp(10));
        activeChatWindow.addView(title);
        activeChatWindow.addView(subtitle);

        activeChatMessagesContainer = new LinearLayout(this);
        activeChatMessagesContainer.setOrientation(LinearLayout.VERTICAL);

        for (ChatMessage chatMessage : chatMessages) {
            addChatBubbleTo(activeChatMessagesContainer, chatMessage);
        }

        activeChatMessagesScroll = new ScrollView(this);
        activeChatMessagesScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        activeChatMessagesScroll.setNestedScrollingEnabled(true);
        activeChatMessagesScroll.addView(activeChatMessagesContainer);
        LinearLayout.LayoutParams messagesParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        activeChatWindow.addView(activeChatMessagesScroll, messagesParams);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(10), 0, 0);

        EditText input = createInput("Ask Aegis AI", "");
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_muted));
        input.setBackgroundResource(R.drawable.card_surface);
        input.setPadding(dp(14), 0, dp(14), 0);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(58), 1f);
        inputParams.setMargins(0, 0, dp(10), 0);
        inputRow.addView(input, inputParams);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                bottomNav.setVisibility(View.GONE);
                adjustChatWindow(true);
                scrollChatToBottom();
            }
        });

        Button send = createThemedButton("Send");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(96), dp(58));
        send.setLayoutParams(sendParams);
        inputRow.addView(send);

        send.setOnClickListener(v -> {
            String question = input.getText().toString().trim();
            if (question.isEmpty()) {
                return;
            }

            chatMessages.add(new ChatMessage("You", question, true));
            chatMessages.add(new ChatMessage("Aegis AI", "", false, true));
            input.setText("");
            updateActiveChatMessages();
            scrollChatToBottom();
            askAegisAI(question);
        });

        activeChatWindow.addView(inputRow);
        screenContainer.addView(activeChatWindow);
        activeChatWindow.setAlpha(0f);
        activeChatWindow.setScaleX(0.97f);
        activeChatWindow.setScaleY(0.97f);
        activeChatWindow.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(260)
                .start();

        if (keepKeyboardAfterChatRefresh) {
            input.postDelayed(() -> focusChatInput(input), 120);
            input.postDelayed(() -> focusChatInput(input), 360);
            input.postDelayed(() -> keepKeyboardAfterChatRefresh = false, 520);
        }
    }

    private void updateActiveChatMessages() {
        if (activeChatMessagesContainer == null) {
            refreshSelectedTab();
            return;
        }

        activeChatMessagesContainer.removeAllViews();
        for (ChatMessage chatMessage : chatMessages) {
            addChatBubbleTo(activeChatMessagesContainer, chatMessage);
        }
    }

    private void scrollChatToBottom() {
        if (activeChatMessagesScroll != null && activeChatMessagesContainer != null) {
            activeChatMessagesScroll.postDelayed(() -> activeChatMessagesScroll.smoothScrollTo(0, activeChatMessagesContainer.getBottom()), 70);
        }
    }

    // Removed addDashboardGrid()

    private void addUnifiedAIHealthReport() {
        LinearLayout reportCard = createCard();
        reportCard.setPadding(0, 0, 0, dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(16), dp(16), dp(8));

        TextView title = createText("AI Health Report", 16, R.color.neon_cyan, true);
        title.setTextColor(themeManager.getAccent());

        TextView icon = createText(" ⚡", 18, R.color.text_primary, false);
        ObjectAnimator rotate = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f);
        rotate.setDuration(4000);
        rotate.setRepeatCount(ObjectAnimator.INFINITE);
        rotate.setInterpolator(new android.view.animation.LinearInterpolator());
        rotate.start();

        header.addView(title);
        header.addView(icon);

        TextView timestamp = createText("Updated: Just now", 11, R.color.text_secondary, false);
        LinearLayout.LayoutParams tsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        timestamp.setLayoutParams(tsParams);
        timestamp.setGravity(Gravity.END);
        header.addView(timestamp);

        reportCard.addView(header);

        LinearLayout middleRow = new LinearLayout(this);
        middleRow.setOrientation(LinearLayout.HORIZONTAL);
        middleRow.setGravity(Gravity.CENTER_VERTICAL);
        middleRow.setPadding(dp(16), dp(8), dp(16), dp(16));

        LinearLayout meterCol = new LinearLayout(this);
        meterCol.setOrientation(LinearLayout.VERTICAL);
        meterCol.setGravity(Gravity.CENTER);
        
        CircularRiskMeterView riskMeter = new CircularRiskMeterView(this);
        int score = fallLatched ? 95 : (getFallsThisMonth() * 15 + 10);
        if (score > 100) score = 100;
        riskMeter.setAccentColor(themeManager.getAccent());
        riskMeter.setScore(score);
        meterCol.addView(riskMeter, new LinearLayout.LayoutParams(dp(70), dp(70)));
        
        TextView meterLabel = createText("Stability", 11, R.color.text_secondary, false);
        meterLabel.setPadding(0, dp(4), 0, 0);
        meterCol.addView(meterLabel);
        
        middleRow.addView(meterCol);

        LinearLayout graphCol = new LinearLayout(this);
        graphCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams graphParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        graphParams.setMargins(dp(16), 0, 0, 0);
        graphCol.setLayoutParams(graphParams);

        TextView graphTitle = createText("Daily Activity Trend", 13, R.color.text_primary, true);
        graphCol.addView(graphTitle);

        AnimatedLineGraphView lineGraph = new AnimatedLineGraphView(this);
        final android.view.GestureDetector gestureDetector = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            private int currentView = 0;
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float velocityX, float velocityY) {
                if (Math.abs(velocityX) > Math.abs(velocityY)) {
                    if (velocityX < 0) {
                        currentView = (currentView + 1) % 3;
                    } else {
                        currentView = (currentView - 1 + 3) % 3;
                    }
                    if (currentView == 0) {
                        graphTitle.setText("Daily Activity Trend");
                        lineGraph.setData(new float[]{20f, 50f, 40f, 80f, 30f, 60f});
                    } else if (currentView == 1) {
                        graphTitle.setText("Weekly Activity Trend");
                        lineGraph.setData(new float[]{60f, 70f, 50f, 90f, 85f, 65f, 40f});
                    } else {
                        graphTitle.setText("Monthly Activity Trend");
                        lineGraph.setData(new float[]{40f, 30f, 55f, 80f});
                    }
                    return true;
                }
                return false;
            }
        });

        lineGraph.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        lineGraph.setAccentColor(themeManager.getAccent());
        lineGraph.setData(new float[]{20f, 50f, 40f, 80f, 30f, 60f});
        graphCol.addView(lineGraph, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));

        TextView swipeHint = createText("Swipe graph for Weekly/Monthly", 10, R.color.text_muted, false);
        swipeHint.setPadding(0, dp(4), 0, 0);
        graphCol.addView(swipeHint);

        middleRow.addView(graphCol);
        reportCard.addView(middleRow);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divParams.setMargins(dp(16), 0, dp(16), dp(12));
        reportCard.addView(divider, divParams);

        LinearLayout insightRow = new LinearLayout(this);
        insightRow.setOrientation(LinearLayout.HORIZONTAL);
        insightRow.setPadding(dp(16), 0, dp(16), dp(16));
        // Allow insightRow itself to grow vertically with content
        insightRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView insightIcon = createText("💬", 24, R.color.text_primary, false);
        insightIcon.setPadding(0, dp(2), dp(12), 0);
        // Pin icon to top so it doesn't stretch
        LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        iconP.gravity = Gravity.TOP;
        insightRow.addView(insightIcon, iconP);

        TextView insightText = createText("", 13, R.color.text_primary, false);
        insightText.setLineSpacing(5f, 1f);
        // WRAP_CONTENT height so the card grows with the text; weight=1 fills width
        insightRow.addView(insightText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        reportCard.addView(insightRow);
        screenContainer.addView(reportCard);

        // Handler-based typewriter — lets the layout system measure after each character
        String fullText = buildLocalAnalyticsSummary();
        Handler typingHandler = new Handler(android.os.Looper.getMainLooper());
        final int[] index = {0};
        Runnable typeNext = new Runnable() {
            @Override
            public void run() {
                if (index[0] <= fullText.length()) {
                    insightText.setText(fullText.substring(0, index[0]));
                    index[0]++;
                    typingHandler.postDelayed(this, 14);
                }
            }
        };
        typingHandler.post(typeNext);
    }

    // Removed old unused analytic visualization methods

    private void addChatBubble(ChatMessage chatMessage) {
        addChatBubbleTo(screenContainer, chatMessage);
    }

    private void addChatBubbleTo(LinearLayout parent, ChatMessage chatMessage) {
        LinearLayout card = createCard();
        card.setGravity(chatMessage.fromUser ? Gravity.END : Gravity.START);

        TextView label = createText(chatMessage.sender, 12, chatMessage.fromUser ? R.color.warning_gold : R.color.neon_cyan, true);
        if (!chatMessage.fromUser) label.setTextColor(themeManager.getAccent());

        card.addView(label);
        if (chatMessage.pending) {
            TypingDotsView typingDotsView = new TypingDotsView(this);
            LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34)
            );
            dotsParams.setMargins(0, dp(6), 0, 0);
            card.addView(typingDotsView, dotsParams);
        } else {
            TextView message = createText(chatMessage.message, 15, R.color.text_primary, false);
            message.setLineSpacing(4f, 1f);
            message.setPadding(0, dp(6), 0, 0);
            card.addView(message);
        }
        parent.addView(card);
    }

    private void askAegisAI(String question) {
        new Thread(() -> {
            String answer = callGemini(buildPrompt(question));
            runOnUiThread(() -> {
                for (int i = chatMessages.size() - 1; i >= 0; i--) {
                    ChatMessage message = chatMessages.get(i);
                    if (!message.fromUser && message.pending) {
                        message.message = answer;
                        message.pending = false;
                        break;
                    }
                }
                updateActiveChatMessages();
                scrollChatToBottom();
            });
        }).start();
    }

    private void focusChatInput(EditText input) {
        input.requestFocus();
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void clearKeyboardRetention() {
        keepKeyboardAfterChatRefresh = false;
    }

    private String buildPrompt(String question) {
        return "You are Aegis AI, a friendly and human-like companion inside an elderly care app. " +
                "You can chat naturally about ANY topic the user brings up. " +
                "If they ask about health or safety, provide helpful, practical advice. " +
                "Current app context: fallLatched=" + fallLatched +
                ", fallsThisMonth=" + getFallsThisMonth() +
                ", likelyFallTime='" + getMostLikelyFallTime() +
                "', memberAge=" + memberProfile.age +
                ", condition='" + memberProfile.condition +
                "'. Be conversational, brief, and friendly. User asks: " + question;
    }

    private String callGemini(String prompt) {
        if (BuildConfig.GEMINI_API_KEY == null || BuildConfig.GEMINI_API_KEY.trim().isEmpty()) {
            return "AI chat is not set up yet. Please check the app configuration and try again.";
        }

        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            JSONObject textPart = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(textPart));
            JSONObject generationConfig = new JSONObject()
                    .put("temperature", 0.5)
                    .put("maxOutputTokens", 450);
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", generationConfig);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()
            ));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            if (conn.getResponseCode() >= 400) {
                return "AI request failed. Check the API key, billing/access, and internet connection.";
            }

            JSONObject json = new JSONObject(response.toString());
            return json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim();

        } catch (Exception e) {
            return "I could not reach the AI service. Please check internet access, then try again.";
        }
    }

    private void addProfileCard() {
        LinearLayout card = createCard();

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView label = createText(memberProfile.name, 18, R.color.neon_cyan, true);
        label.setTextColor(themeManager.getAccent());
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(label, labelParams);

        TextView dots = createText("⋮", 24, R.color.text_secondary, true);
        dots.setPadding(dp(10), dp(5), dp(10), dp(5));
        dots.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, dots);
            popup.getMenu().add("Edit");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getTitle().equals("Edit")) {
                    showMemberEditor();
                    return true;
                }
                return false;
            });
            popup.show();
        });
        headerRow.addView(dots);
        
        card.addView(headerRow);

        // Field rows — same style as the edit-member dialog inputs
        String[][] fields = {
            {"Age",           memberProfile.age},
            {"Condition",     memberProfile.condition},
            {"Blood Group",   memberProfile.bloodGroup},
            {"Emergency",     memberProfile.emergencyContact},
            {"Address",       memberProfile.address},
            {"Notes",         memberProfile.notes}
        };

        for (String[] field : fields) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
            rowBg.setColor(Color.parseColor("#1A1A2A"));
            rowBg.setCornerRadius(dp(14));
            rowBg.setStroke(dp(1), themeManager.getAccentGlow());
            row.setBackground(rowBg);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rowParams);

            TextView fieldLabel = createText(field[0], 12, R.color.text_secondary, true);
            fieldLabel.setTextColor(themeManager.getAccent());
            LinearLayout.LayoutParams labelP = new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT);
            row.addView(fieldLabel, labelP);

            TextView fieldValue = createText(field[1], 14, R.color.text_primary, false);
            row.addView(fieldValue, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            card.addView(row);
        }

        screenContainer.addView(card);
    }

    private void addAssistantMessage(String title, String body) {
        LinearLayout card = createCard();

        TextView label = createText(title, 13, R.color.neon_cyan, true);
        label.setTextColor(themeManager.getAccent());
        TextView message = createText(body, 16, R.color.text_primary, false);
        message.setLineSpacing(3f, 1f);
        message.setPadding(0, dp(6), 0, 0);

        card.addView(label);
        card.addView(message);
        screenContainer.addView(card);
    }

    private void addInfoCard(String title, String body, int accentColor) {
        LinearLayout card = createCard();

        TextView label = createText(title, 13, accentColor, true);
        TextView message = createText(body, 15, R.color.text_primary, false);
        message.setLineSpacing(4f, 1f);
        message.setPadding(0, dp(7), 0, 0);

        card.addView(label);
        card.addView(message);
        screenContainer.addView(card);
    }

    private void addSectionTitle(String title) {
        TextView section = createText(title, 20, R.color.text_primary, true);
        section.setTextColor(themeManager.getAccent());
        section.setPadding(0, 0, 0, dp(10));
        screenContainer.addView(section);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dp(26));
        cardBg.setColor(Color.parseColor("#EE081015"));
        cardBg.setStroke(dp(1), themeManager.getAccentGlow());
        card.setBackground(cardBg);

        card.setElevation(dp(6));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(params);
        return card;
    }

    private Button createButton(String text, int background, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(getColor(textColor));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(background);
        button.setPadding(dp(12), 0, dp(12), 0);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        params.setMargins(0, 0, 0, dp(12));
        button.setLayoutParams(params);
        return button;
    }

    /** Full-width button styled with the active theme accent color. */
    private Button createThemedButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.parseColor("#111111"));
        button.setTextSize(14);
        button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(themeManager.getAccent());
        bg.setCornerRadius(dp(18));
        button.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        params.setMargins(0, 0, 0, dp(12));
        button.setLayoutParams(params);
        return button;
    }

    /**
     * Applies the active theme color to persistent (non-scroll) surfaces:
     * hero card background, toolbar tint, and statusText shadow.
     * Call this whenever the theme changes or the activity starts.
     */
    private void applyThemeAtmosphere() {
        int accent = themeManager.getAccent();
        int r = Color.red(accent), g = Color.green(accent), b = Color.blue(accent);

        // --- Hero card: rebuild liquid-glass tinted to theme ---
        android.view.View heroCard = findViewById(R.id.heroCard);
        if (heroCard != null) {
            android.graphics.drawable.GradientDrawable outer = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.argb(85, r, g, b), Color.argb(26, r, g, b), Color.TRANSPARENT});
            outer.setCornerRadius(dp(30));

            android.graphics.drawable.GradientDrawable inner = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BR_TL,
                    new int[]{Color.parseColor("#CC0A1014"), Color.parseColor("#EE04080C")});
            inner.setCornerRadius(dp(29));
            inner.setStroke(dp(1), Color.argb(100, r, g, b));

            android.graphics.drawable.GradientDrawable shine = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.argb(38, 255, 255, 255), Color.TRANSPARENT});
            shine.setCornerRadius(dp(27));

            android.graphics.drawable.LayerDrawable layered = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{outer, inner, shine});
            layered.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
            layered.setLayerInset(2, dp(3), dp(3), dp(3), dp(3));
            heroCard.setBackground(layered);
        }

        // --- Toolbar: subtle accent tint ---
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            android.graphics.drawable.GradientDrawable toolbarBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.argb(30, r, g, b), Color.parseColor("#FF05070A")});
            toolbar.setBackground(toolbarBg);
        }

        // --- Status text: update shadow glow to match theme ---
        if (statusText != null) {
            statusText.setShadowLayer(16, 0, 0, Color.argb(170, r, g, b));
        }

        // --- Bottom nav: tint the top border ---
        android.view.View nav = findViewById(R.id.bottomNav);
        if (nav != null) {
            android.graphics.drawable.GradientDrawable navBg = new android.graphics.drawable.GradientDrawable();
            navBg.setColor(Color.parseColor("#FF030609"));
            navBg.setStroke(dp(1), Color.argb(60, r, g, b));
            nav.setBackground(navBg);
        }
    }

    private TextView createText(String text, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(color));
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private void recordFallEvent(long timestamp) {
        String existing = analyticsPrefs.getString("fall_events", "");
        String updated = existing == null || existing.isEmpty() ? String.valueOf(timestamp) : existing + "," + timestamp;
        analyticsPrefs.edit().putString("fall_events", updated).apply();
    }

    private List<Long> getFallEvents() {
        List<Long> events = new ArrayList<>();
        String stored = analyticsPrefs.getString("fall_events", "");
        if (stored == null || stored.isEmpty()) {
            return events;
        }

        String[] parts = stored.split(",");
        for (String part : parts) {
            try {
                events.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return events;
    }

    private int getFallsThisMonth() {
        Calendar now = Calendar.getInstance();
        int currentMonth = now.get(Calendar.MONTH);
        int currentYear = now.get(Calendar.YEAR);
        int count = 0;

        for (Long event : getFallEvents()) {
            Calendar eventTime = Calendar.getInstance();
            eventTime.setTimeInMillis(event);
            if (eventTime.get(Calendar.MONTH) == currentMonth && eventTime.get(Calendar.YEAR) == currentYear) {
                count++;
            }
        }
        return count;
    }

    private String getMostLikelyFallTime() {
        int[] buckets = new int[4];
        for (Long event : getFallEvents()) {
            Calendar eventTime = Calendar.getInstance();
            eventTime.setTimeInMillis(event);
            int hour = eventTime.get(Calendar.HOUR_OF_DAY);
            if (hour >= 5 && hour < 12) {
                buckets[0]++;
            } else if (hour >= 12 && hour < 17) {
                buckets[1]++;
            } else if (hour >= 17 && hour < 21) {
                buckets[2]++;
            } else {
                buckets[3]++;
            }
        }

        int total = buckets[0] + buckets[1] + buckets[2] + buckets[3];
        if (total == 0) {
            return "Not enough fall history yet. I will learn as alerts are recorded.";
        }

        int maxIndex = 0;
        for (int i = 1; i < buckets.length; i++) {
            if (buckets[i] > buckets[maxIndex]) {
                maxIndex = i;
            }
        }

        String[] labels = new String[]{"Morning, 5 AM - 12 PM", "Afternoon, 12 PM - 5 PM", "Evening, 5 PM - 9 PM", "Night, 9 PM - 5 AM"};
        return labels[maxIndex] + " (" + buckets[maxIndex] + " of " + total + " alerts)";
    }

    private int[] getFallTimeBuckets() {
        int[] buckets = new int[4];
        for (Long event : getFallEvents()) {
            Calendar eventTime = Calendar.getInstance();
            eventTime.setTimeInMillis(event);
            int hour = eventTime.get(Calendar.HOUR_OF_DAY);
            if (hour >= 5 && hour < 12) {
                buckets[0]++;
            } else if (hour >= 12 && hour < 17) {
                buckets[1]++;
            } else if (hour >= 17 && hour < 21) {
                buckets[2]++;
            } else {
                buckets[3]++;
            }
        }
        return buckets;
    }

    private int[] getWeeklyFallBuckets() {
        int[] weeks = new int[5];
        Calendar now = Calendar.getInstance();
        int currentMonth = now.get(Calendar.MONTH);
        int currentYear = now.get(Calendar.YEAR);

        for (Long event : getFallEvents()) {
            Calendar eventTime = Calendar.getInstance();
            eventTime.setTimeInMillis(event);
            if (eventTime.get(Calendar.MONTH) == currentMonth && eventTime.get(Calendar.YEAR) == currentYear) {
                int weekIndex = Math.min((eventTime.get(Calendar.DAY_OF_MONTH) - 1) / 7, 4);
                weeks[weekIndex]++;
            }
        }
        return weeks;
    }

    private String buildLocalAnalyticsSummary() {
        int monthlyFalls = getFallsThisMonth();
        if (monthlyFalls == 0) {
            return "No falls recorded this month. Keep the wearable charged, attached securely, and keep walking areas clear.";
        }

        return "This month has " + monthlyFalls + " fall event(s). Highest observed risk window: " + getMostLikelyFallTime() + ". Review movement patterns, lighting, footwear, and nearby support during that time.";
    }

    private void showMemberDetailsUI() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundResource(R.drawable.dialog_bg_bw);
        form.setPadding(dp(20), dp(20), dp(20), dp(20));
        
        TextView title = createText("Member Profile", 20, R.color.text_primary, true);
        title.setPadding(0, 0, 0, dp(16));
        form.addView(title);
        
        String details = "Name: " + memberProfile.name + "\n\n" +
                         "Age: " + memberProfile.age + "\n\n" +
                         "Condition: " + memberProfile.condition + "\n\n" +
                         "Emergency Contact: " + memberProfile.emergencyContact + "\n\n" +
                         "Blood Group: " + memberProfile.bloodGroup + "\n\n" +
                         "Address: " + memberProfile.address + "\n\n" +
                         "Notes: " + memberProfile.notes;
                         
        TextView body = createText(details, 16, R.color.text_secondary, false);
        form.addView(body);

        Button okBtn = createThemedButton("OK");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        btnParams.setMargins(0, dp(20), 0, 0);
        form.addView(okBtn, btnParams);
        okBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(form);
        dialog.show();
        dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private EditText createStyledInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(android.graphics.Color.GRAY);
        input.setBackgroundResource(R.drawable.input_bg_bw);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        input.setLayoutParams(params);
        return input;
    }

    private void showMemberEditor() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundResource(R.drawable.dialog_bg_bw);
        form.setPadding(dp(20), dp(20), dp(20), dp(20));
        
        TextView title = createText("Edit Member Details", 20, R.color.text_primary, true);
        title.setPadding(0, 0, 0, dp(16));
        form.addView(title);

        EditText nameInput = createStyledInput("Name", memberProfile.name);
        EditText ageInput = createStyledInput("Age", memberProfile.age);
        ageInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ageInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        EditText conditionInput = createStyledInput("Medical condition", memberProfile.condition);
        
        LinearLayout contactContainer = new LinearLayout(this);
        contactContainer.setOrientation(LinearLayout.HORIZONTAL);
        contactContainer.setBackgroundResource(R.drawable.input_bg_bw);
        contactContainer.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout.LayoutParams contactParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        contactParams.setMargins(0, 0, 0, dp(12));
        contactContainer.setLayoutParams(contactParams);

        TextView contactPrefix = createText("+91 ", 16, R.color.text_primary, true);
        contactPrefix.setPadding(dp(12), dp(12), 0, dp(12));
        contactContainer.addView(contactPrefix);

        EditText contactInput = new EditText(this);
        contactInput.setHint("10-digit contact");
        String currentContactNum = memberProfile.emergencyContact != null ? memberProfile.emergencyContact : "";
        if (currentContactNum.startsWith("+91 ")) currentContactNum = currentContactNum.substring(4);
        else if (currentContactNum.startsWith("+91")) currentContactNum = currentContactNum.substring(3);
        contactInput.setText(currentContactNum.trim());
        contactInput.setTextColor(getColor(R.color.text_primary));
        contactInput.setHintTextColor(android.graphics.Color.GRAY);
        contactInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        contactInput.setPadding(0, dp(12), dp(12), dp(12));
        contactInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        contactInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        contactContainer.addView(contactInput, inputParams);

        EditText bloodInput = createStyledInput("Blood group", memberProfile.bloodGroup);
        bloodInput.setFocusable(false);
        bloodInput.setClickable(true);
        bloodInput.setOnClickListener(v -> {
            String[] bloodGroups = {"A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"};
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Select Blood Group")
                    .setItems(bloodGroups, (dialogInterface, i) -> {
                        bloodInput.setText(bloodGroups[i]);
                    })
                    .show();
        });
        EditText addressInput = createStyledInput("Address", memberProfile.address);
        EditText notesInput = createStyledInput("Notes", memberProfile.notes);

        form.addView(nameInput);
        form.addView(ageInput);
        form.addView(conditionInput);
        form.addView(contactContainer);
        form.addView(bloodInput);
        form.addView(addressInput);
        form.addView(notesInput);

        LinearLayout buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.END);
        buttonsRow.setPadding(0, dp(16), 0, 0);

        Button cancelBtn = createButton("Cancel", R.drawable.button_outline, R.color.text_primary);
        Button saveBtn = createThemedButton("Save");

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        btnParams.setMargins(0, 0, dp(10), 0);
        buttonsRow.addView(cancelBtn, btnParams);
        
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        buttonsRow.addView(saveBtn, btnParams2);

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        saveBtn.setOnClickListener(v -> {
            memberProfile.name = nameInput.getText().toString();
            memberProfile.age = ageInput.getText().toString();
            memberProfile.condition = conditionInput.getText().toString();
            memberProfile.emergencyContact = "+91 " + contactInput.getText().toString().trim();
            memberProfile.bloodGroup = bloodInput.getText().toString();
            memberProfile.address = addressInput.getText().toString();
            memberProfile.notes = notesInput.getText().toString();
            // Persist so changes survive app restarts
            analyticsPrefs.edit()
                    .putString("member_name", memberProfile.name)
                    .putString("member_age", memberProfile.age)
                    .putString("member_condition", memberProfile.condition)
                    .putString("member_emergency", memberProfile.emergencyContact)
                    .putString("member_blood", memberProfile.bloodGroup)
                    .putString("member_address", memberProfile.address)
                    .putString("member_notes", memberProfile.notes)
                    .apply();
            renderMembers();
            dialog.dismiss();
        });

        form.addView(buttonsRow);
        scroll.addView(form);
        dialog.setContentView(scroll);
        dialog.show();
        dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void showEmergencyNumberDialog() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundResource(R.drawable.dialog_bg_bw);
        form.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView title = createText("Emergency Number", 20, R.color.text_primary, true);
        title.setPadding(0, 0, 0, dp(16));
        form.addView(title);

        LinearLayout phoneContainer = new LinearLayout(this);
        phoneContainer.setOrientation(LinearLayout.HORIZONTAL);
        phoneContainer.setBackgroundResource(R.drawable.input_bg_bw);
        phoneContainer.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        phoneParams.setMargins(0, 0, 0, dp(12));
        phoneContainer.setLayoutParams(phoneParams);

        TextView prefix = createText("+91 ", 16, R.color.text_primary, true);
        prefix.setPadding(dp(12), dp(12), 0, dp(12));
        phoneContainer.addView(prefix);

        EditText input = new EditText(this);
        input.setHint("10-digit number");
        String currentNum = emergencyNumber.startsWith("+91 ") ? emergencyNumber.substring(4) : emergencyNumber;
        if (currentNum.startsWith("+91")) currentNum = currentNum.substring(3);
        input.setText(currentNum.trim());
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(android.graphics.Color.GRAY);
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        input.setPadding(0, dp(12), dp(12), dp(12));
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        phoneContainer.addView(input, inputParams);
        form.addView(phoneContainer);

        LinearLayout buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.END);
        buttonsRow.setPadding(0, dp(16), 0, 0);

        Button cancelBtn = createButton("Cancel", R.drawable.button_outline, R.color.text_primary);
        Button saveBtn = createThemedButton("Save");

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        btnParams.setMargins(0, 0, dp(10), 0);
        buttonsRow.addView(cancelBtn, btnParams);
        
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        buttonsRow.addView(saveBtn, btnParams2);

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        saveBtn.setOnClickListener(v -> {
            emergencyNumber = "+91 " + input.getText().toString().trim();
            renderSettings();
            dialog.dismiss();
        });

        form.addView(buttonsRow);
        dialog.setContentView(form);
        dialog.show();
        dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private EditText createInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.DKGRAY);
        return input;
    }

    private void resetScreen() {
        activeChatWindow = null;
        activeChatMessagesContainer = null;
        activeChatMessagesScroll = null;
        screenContainer.removeAllViews();
    }

    private void refreshSelectedTab() {
        applyThemeAtmosphere();
        if (selectedTab.equals("Home")) {
            renderHome();
        } else if (selectedTab.equals("Monitoring")) {
            renderMonitoring();
        } else if (selectedTab.equals("Members")) {
            renderMembers();
        } else if (selectedTab.equals("Emergency")) {
            renderEmergency();
        } else if (selectedTab.equals("Settings")) {
            renderSettings();
        } else if (selectedTab.equals("Chat")) {
            renderChat();
        }
    }

    private void updateNavSelection(int selectedIndex) {
        for (int i = 0; i < navItems.length; i++) {
            boolean selected = i == selectedIndex;
            navItems[i].setSelected(i == selectedIndex);
            navItems[i].setTextColor(selected ? themeManager.getAccent() : getColor(R.color.text_secondary));
            navItems[i].animate()
                    .scaleX(selected ? 1.06f : 1f)
                    .scaleY(selected ? 1.06f : 1f)
                    .alpha(selected ? 1f : 0.72f)
                    .setInterpolator(new DecelerateInterpolator())
                    .setDuration(180)
                    .start();
        }
    }

    private void fadeScreenIn() {
        screenContainer.setAlpha(0f);
        screenContainer.setTranslationY(dp(18));
        screenContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(260)
                .start();
    }

    private void animateTabSwitch(Runnable renderAction) {
        if (tabSwitchAnimating) {
            return;
        }

        tabSwitchAnimating = true;
        screenContainer.animate()
                .alpha(0f)
                .translationY(dp(14))
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(120)
                .withEndAction(() -> {
                    renderAction.run();
                    tabSwitchAnimating = false;
                })
                .start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.memberDetails) {
            showMemberDetailsUI();
        }
        return true;
    }

    private static class MemberProfile {
        String name = "John Doe";
        String age = "72";
        String condition = "High fall risk";
        String emergencyContact = "+91 XXXXXXXX";
        String bloodGroup = "O+";
        String address = "Hostel Block A";
        String notes = "Under continuous monitoring";
    }

    private static class ChatMessage {
        String sender;
        String message;
        boolean fromUser;
        boolean pending;

        ChatMessage(String sender, String message, boolean fromUser) {
            this(sender, message, fromUser, false);
        }

        ChatMessage(String sender, String message, boolean fromUser, boolean pending) {
            this.sender = sender;
            this.message = message;
            this.fromUser = fromUser;
            this.pending = pending;
        }
    }

    public class TypingDotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long startTime = System.currentTimeMillis();

        public TypingDotsView(android.content.Context context) {
            super(context);
        }

        public TypingDotsView(android.content.Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerY = getHeight() / 2f;
            float startX = 18f;
            int accent = themeManager.getAccent();
            int r = Color.red(accent), g = Color.green(accent), b = Color.blue(accent);

            for (int i = 0; i < 3; i++) {
                double phase = ((System.currentTimeMillis() - startTime) / 260.0) + i * 0.75;
                float radius = (float) (5.5 + Math.sin(phase) * 2.5);
                int alpha = (int) (150 + Math.sin(phase) * 80);
                paint.setColor(Color.argb(Math.max(70, Math.min(255, alpha)), r, g, b));
                canvas.drawCircle(startX + i * 24f, centerY, radius, paint);
            }

            postInvalidateDelayed(45);
        }
    }

    public static class AnimatedLineGraphView extends View {
        private Paint linePaint;
        private Paint glowPaint;
        private android.graphics.Path path;
        private android.graphics.PathMeasure pathMeasure;
        private float[] data = new float[0];
        private float drawProgress = 0f;
        private android.animation.ValueAnimator animator;

        public AnimatedLineGraphView(Context context) {
            super(context);
            init();
        }

        public AnimatedLineGraphView(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        private void init() {
            linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(3));
            linePaint.setColor(Color.parseColor("#00F5D4"));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);

            glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(dp(8));
            glowPaint.setColor(Color.parseColor("#4400F5D4"));
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(dp(4), android.graphics.BlurMaskFilter.Blur.NORMAL));

            path = new android.graphics.Path();
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        public void setAccentColor(int accentColor) {
            int r = Color.red(accentColor), g = Color.green(accentColor), b = Color.blue(accentColor);
            linePaint.setColor(accentColor);
            glowPaint.setColor(Color.argb(68, r, g, b));
            invalidate();
        }

        private int dp(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        public void setData(float[] newData) {
            this.data = newData;
            calculatePath();
            
            if (animator != null) animator.cancel();
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1200);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                drawProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        private void calculatePath() {
            path.reset();
            if (data == null || data.length == 0) return;

            int width = getWidth();
            int height = getHeight();
            if (width == 0 || height == 0) {
                post(() -> calculatePath());
                return;
            }

            float maxData = 100f;
            float dx = (float) width / (data.length - 1);

            path.moveTo(0, height - (data[0] / maxData) * height);
            for (int i = 1; i < data.length; i++) {
                float x = i * dx;
                float y = height - (data[i] / maxData) * height;
                path.lineTo(x, y);
            }
            
            pathMeasure = new android.graphics.PathMeasure(path, false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            calculatePath();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (pathMeasure == null || data.length == 0) return;

            android.graphics.Path drawPath = new android.graphics.Path();
            float length = pathMeasure.getLength();
            pathMeasure.getSegment(0, length * drawProgress, drawPath, true);

            canvas.drawPath(drawPath, glowPaint);
            canvas.drawPath(drawPath, linePaint);
        }
    }

    public static class CircularRiskMeterView extends View {
        private Paint bgPaint;
        private Paint progressPaint;
        private Paint textPaint;
        private float progress = 0;
        private float targetProgress = 0;
        private android.animation.ValueAnimator animator;

        public CircularRiskMeterView(Context context) {
            super(context);
            init();
        }

        public CircularRiskMeterView(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        private void init() {
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setStyle(Paint.Style.STROKE);
            bgPaint.setStrokeWidth(dp(8));
            bgPaint.setColor(Color.parseColor("#222233"));

            progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeWidth(dp(8));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setColor(Color.parseColor("#00F5D4")); // neon_cyan fallback

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(22));
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        private int dp(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        private int accentColor = Color.parseColor("#00F5D4");

        public void setAccentColor(int color) {
            this.accentColor = color;
            progressPaint.setColor(color);
            invalidate();
        }

        public void setScore(int score) {
            this.targetProgress = score;
            if (score > 50) {
                progressPaint.setColor(Color.parseColor("#FF4D5E"));
            } else {
                progressPaint.setColor(accentColor);
            }
            if (animator != null) animator.cancel();
            animator = android.animation.ValueAnimator.ofFloat(0, targetProgress);
            animator.setDuration(1500);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int size = Math.min(width, height);
            int padding = dp(8);
            
            android.graphics.RectF rect = new android.graphics.RectF(padding, padding, size - padding, size - padding);
            
            canvas.drawArc(rect, 135, 270, false, bgPaint);
            canvas.drawArc(rect, 135, 270 * (progress / 100f), false, progressPaint);
            
            canvas.drawText(String.valueOf((int)progress), width / 2f, (height / 2f) + (textPaint.getTextSize() / 3), textPaint);
        }
    }

    public static class LiquidSOSButton extends View {
        private Paint bgPaint;
        private Paint arcPaint;
        private Paint textPaint;
        private Paint subTextPaint;
        private Paint glowPaint;
        private Paint ripplePaint;

        private float progress = 0f;
        private boolean isHolding = false;
        private android.animation.ValueAnimator progressAnim;
        private android.animation.ValueAnimator rippleAnim;
        
        private float rippleRadius = 0f;
        private float rippleAlpha = 0f;

        private Runnable onTrigger;
        private Vibrator vibrator;
        private long lastVibrateTime = 0;

        public LiquidSOSButton(Context context) {
            super(context);
            init(context);
        }

        public LiquidSOSButton(Context context, AttributeSet attrs) {
            super(context, attrs);
            init(context);
        }

        private void init(Context context) {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(Color.parseColor("#1A0000"));
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setShadowLayer(dp(20), 0, 0, Color.parseColor("#44FF0000"));

            glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setColor(Color.parseColor("#44FF0000"));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(dp(4));
            glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(dp(10), android.graphics.BlurMaskFilter.Blur.NORMAL));

            arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            arcPaint.setColor(Color.parseColor("#FF0044"));
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeWidth(dp(8));
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setShadowLayer(dp(10), 0, 0, Color.parseColor("#FF0044"));

            ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ripplePaint.setColor(Color.parseColor("#FF0044"));
            ripplePaint.setStyle(Paint.Style.STROKE);
            ripplePaint.setStrokeWidth(dp(2));

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(32));
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);

            subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subTextPaint.setColor(Color.parseColor("#AAAAAA"));
            subTextPaint.setTextSize(dp(14));
            subTextPaint.setTextAlign(Paint.Align.CENTER);

            startRipple();
        }

        public void setOnTriggerListener(Runnable listener) {
            this.onTrigger = listener;
        }

        private void startRipple() {
            rippleAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            rippleAnim.setDuration(2000);
            rippleAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            rippleAnim.addUpdateListener(a -> {
                float val = (float) a.getAnimatedValue();
                rippleRadius = dp(80) + (val * dp(60));
                rippleAlpha = 150f * (1f - val);
                invalidate();
            });
            rippleAnim.start();
        }

        private int dp(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    isHolding = true;
                    if (progressAnim != null) progressAnim.cancel();
                    progressAnim = android.animation.ValueAnimator.ofFloat(progress, 1f);
                    progressAnim.setDuration((long) ((1f - progress) * 2000));
                    progressAnim.addUpdateListener(a -> {
                        progress = (float) a.getAnimatedValue();
                        if (System.currentTimeMillis() - lastVibrateTime > 150) {
                            if (vibrator != null) vibrator.vibrate(20);
                            lastVibrateTime = System.currentTimeMillis();
                        }
                        if (progress >= 1f && isHolding) {
                            isHolding = false;
                            progress = 1f;
                            if (vibrator != null) vibrator.vibrate(500);
                            if (onTrigger != null) onTrigger.run();
                        }
                        invalidate();
                    });
                    progressAnim.start();
                    animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start();
                    bgPaint.setColor(Color.parseColor("#330000"));
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    isHolding = false;
                    if (progressAnim != null) progressAnim.cancel();
                    progressAnim = android.animation.ValueAnimator.ofFloat(progress, 0f);
                    progressAnim.setDuration((long) (progress * 1000));
                    progressAnim.addUpdateListener(a -> {
                        progress = (float) a.getAnimatedValue();
                        invalidate();
                    });
                    progressAnim.start();
                    animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                    bgPaint.setColor(Color.parseColor("#1A0000"));
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float cx = width / 2f;
            float cy = height / 2f;
            float radius = dp(80);

            ripplePaint.setAlpha((int) rippleAlpha);
            canvas.drawCircle(cx, cy, rippleRadius, ripplePaint);

            canvas.drawCircle(cx, cy, radius, bgPaint);
            canvas.drawCircle(cx, cy, radius, glowPaint);

            android.graphics.RectF arcRect = new android.graphics.RectF(cx - radius + dp(4), cy - radius + dp(4), cx + radius - dp(4), cy + radius - dp(4));
            canvas.drawArc(arcRect, -90, progress * 360f, false, arcPaint);

            String mainText = progress >= 1f ? "CALLING" : "SOS";
            String subText = progress >= 1f ? "Emergency active" : (isHolding ? "Hold to Call" : "Press & Hold");
            if (isHolding && progress < 1f) {
                subTextPaint.setColor(Color.parseColor("#FF4444"));
            } else {
                subTextPaint.setColor(Color.parseColor("#AAAAAA"));
            }

            canvas.drawText(mainText, cx, cy + dp(8), textPaint);
            canvas.drawText(subText, cx, cy + dp(32), subTextPaint);
        }
    }

    public static class WeatherBackgroundView extends View {
        private Paint paint;
        private int weatherCode = 0;
        private boolean isDay = true;
        private java.util.List<Particle> particles = new java.util.ArrayList<>();
        private long lastTime = System.currentTimeMillis();

        private class Particle {
            float x, y, speedY, speedX, size, alpha;
        }

        public WeatherBackgroundView(Context context) {
            super(context);
            init();
        }

        public WeatherBackgroundView(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        private void init() {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        public void setWeather(int code, boolean isDay) {
            this.weatherCode = code;
            this.isDay = isDay;
            particles.clear();
            for (int i = 0; i < 50; i++) {
                Particle p = new Particle();
                p.x = (float) (Math.random() * 2000); 
                p.y = (float) (Math.random() * 3000);
                if (code >= 51 && code <= 67) { 
                    p.speedY = 15f + (float) Math.random() * 10f;
                    p.speedX = 2f;
                    p.size = 3f + (float) Math.random() * 2f;
                    p.alpha = 100f + (float) Math.random() * 100f;
                } else if (code >= 71 && code <= 77) { 
                    p.speedY = 3f + (float) Math.random() * 4f;
                    p.speedX = (float) Math.random() * 2f - 1f;
                    p.size = 5f + (float) Math.random() * 6f;
                    p.alpha = 150f + (float) Math.random() * 100f;
                } else if (code == 0 && isDay) { 
                    p.speedY = -0.5f - (float) Math.random() * 1f;
                    p.speedX = (float) Math.random() * 1f - 0.5f;
                    p.size = 8f + (float) Math.random() * 10f;
                    p.alpha = 20f + (float) Math.random() * 30f;
                } else { 
                    p.speedY = -0.2f - (float) Math.random() * 0.5f;
                    p.speedX = (float) Math.random() * 0.5f;
                    p.size = 4f + (float) Math.random() * 6f;
                    p.alpha = 30f + (float) Math.random() * 40f;
                }
                particles.add(p);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = System.currentTimeMillis();
            float dt = (now - lastTime) / 16f; 
            lastTime = now;

            int width = getWidth();
            int height = getHeight();

            if (weatherCode == 0 && isDay) {
                paint.setColor(Color.WHITE);
            } else if (weatherCode >= 51 && weatherCode <= 67) {
                paint.setColor(Color.parseColor("#AACCFF")); 
            } else {
                paint.setColor(Color.WHITE);
            }

            for (Particle p : particles) {
                p.x += p.speedX * dt;
                p.y += p.speedY * dt;
                if (p.y > height && p.speedY > 0) p.y = -20f;
                if (p.y < -20f && p.speedY < 0) p.y = height + 20f;
                if (p.x > width) p.x = -20f;
                if (p.x < -20f) p.x = width + 20f;

                paint.setAlpha((int) p.alpha);
                if (weatherCode >= 51 && weatherCode <= 67) {
                    canvas.drawLine(p.x, p.y, p.x - p.speedX * 3, p.y - p.speedY * 3, paint);
                } else {
                    canvas.drawCircle(p.x, p.y, p.size, paint);
                }
            }
            postInvalidateDelayed(16);
        }
    }

    // ─── ThemeManager ────────────────────────────────────────────────────────────
    public static class ThemeManager {
        public static class AppTheme {
            public final String name;
            public final String emoji;
            public final int accentColor;   // full opacity
            public final int accentGlow;    // ~30% opacity glow
            public final boolean isAuto;

            AppTheme(String name, String emoji, String hex, boolean isAuto) {
                this.name = name;
                this.emoji = emoji;
                this.isAuto = isAuto;
                int base = Color.parseColor(hex);
                this.accentColor = base;
                int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
                this.accentGlow = Color.argb(80, r, g, b);
            }
        }

        public static final AppTheme[] THEMES = {
            new AppTheme("Cyan AI",       "🩵", "#00F5D4", false),
            new AppTheme("Emerald",       "💚", "#00E676", false),
            new AppTheme("Royal Purple",  "💜", "#BB86FC", false),
            new AppTheme("Crimson Red",   "❤️", "#FF4D5E", false),
            new AppTheme("Ocean Blue",    "🔵", "#2196F3", false),
            new AppTheme("Sunset Orange", "🧡", "#FF9800", false),
            new AppTheme("Rose Pink",     "🌸", "#FF80AB", false),
            new AppTheme("White Frost",   "🤍", "#E0E0E0", false),
            new AppTheme("Midnight Gold", "✨", "#FFD700", false),
            new AppTheme("Auto AI",       "🤖", "#00F5D4", true),
        };

        private int activeIndex = 0;
        private final SharedPreferences prefs;

        public ThemeManager(SharedPreferences prefs) {
            this.prefs = prefs;
            activeIndex = prefs.getInt("theme_index", 0);
            if (activeIndex < 0 || activeIndex >= THEMES.length) activeIndex = 0;
        }

        public void setTheme(int index) {
            if (index < 0 || index >= THEMES.length) return;
            activeIndex = index;
            prefs.edit().putInt("theme_index", index).apply();
        }

        public int getActiveIndex() { return activeIndex; }
        public AppTheme getActive() { return THEMES[activeIndex]; }

        public int getAccent() {
            AppTheme t = THEMES[activeIndex];
            if (t.isAuto) return resolveAutoAccent();
            return t.accentColor;
        }

        public int getAccentGlow() {
            int base = getAccent();
            return Color.argb(80, Color.red(base), Color.green(base), Color.blue(base));
        }

        private int resolveAutoAccent() {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            if (hour >= 5 && hour < 12)       return Color.parseColor("#00F5D4"); // morning cyan
            else if (hour >= 12 && hour < 17) return Color.parseColor("#FF9800"); // afternoon orange
            else if (hour >= 17 && hour < 21) return Color.parseColor("#BB86FC"); // evening purple
            else                               return Color.parseColor("#2196F3"); // night blue
        }
    }

    // ─── Theme Picker Panel ──────────────────────────────────────────────────────
    private void showThemePickerPanel() {
        FrameLayout panel = new FrameLayout(this);
        panel.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setElevation(dp(32));

        // Dimmed scrim background
        View scrim = new View(this);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(Color.parseColor("#CC000000"));
        scrim.setOnClickListener(v -> dismissPanel(panel));
        panel.addView(scrim);

        // Content sheet (bottom-anchored)
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(28), dp(20), dp(36));

        android.graphics.drawable.GradientDrawable sheetBg = new android.graphics.drawable.GradientDrawable();
        sheetBg.setColor(Color.parseColor("#F0050C12"));
        sheetBg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        sheetBg.setStroke(dp(1), themeManager.getAccentGlow());
        sheet.setBackground(sheetBg);

        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetParams.gravity = Gravity.BOTTOM;
        sheet.setLayoutParams(sheetParams);

        // Header
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(20));

        TextView headerTitle = new TextView(this);
        headerTitle.setText("Choose Theme");
        headerTitle.setTextSize(20);
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTypeface(headerTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams htParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(headerTitle, htParams);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(20);
        closeBtn.setTextColor(Color.parseColor("#AAAAAA"));
        closeBtn.setPadding(dp(16), dp(8), 0, dp(8));
        closeBtn.setOnClickListener(v -> dismissPanel(panel));
        headerRow.addView(closeBtn);
        sheet.addView(headerRow);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText("Tap a theme to apply it instantly");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setPadding(0, 0, 0, dp(20));
        sheet.addView(subtitle);

        // Theme grid (2 columns)
        ThemeManager.AppTheme[] themes = ThemeManager.THEMES;
        LinearLayout row = null;
        for (int i = 0; i < themes.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 0, 0, dp(12));
                row.setLayoutParams(rowParams);
                sheet.addView(row);
            }

            final int idx = i;
            ThemeManager.AppTheme theme = themes[i];
            boolean isActive = (i == themeManager.getActiveIndex());

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(12), dp(16), dp(12), dp(16));

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setCornerRadius(dp(20));
            cardBg.setColor(isActive ? Color.argb(60, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor)) : Color.parseColor("#1A1A2A"));
            cardBg.setStroke(dp(isActive ? 2 : 1), isActive ? theme.accentColor : Color.parseColor("#333355"));
            card.setBackground(cardBg);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cardParams.setMargins(i % 2 == 0 ? 0 : dp(10), 0, 0, 0);
            card.setLayoutParams(cardParams);

            // Glowing circle preview
            View colorDot = new View(this) {
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
                {
                    p.setColor(theme.accentColor);
                    glow.setColor(theme.accentGlow);
                    glow.setMaskFilter(new android.graphics.BlurMaskFilter(dp(8), android.graphics.BlurMaskFilter.Blur.NORMAL));
                    setLayerType(LAYER_TYPE_SOFTWARE, null);
                }
                @Override protected void onDraw(Canvas canvas) {
                    float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(6);
                    canvas.drawCircle(cx, cy, r + dp(4), glow);
                    canvas.drawCircle(cx, cy, r, p);
                }
            };
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            dotParams.setMargins(0, 0, 0, dp(8));
            card.addView(colorDot, dotParams);

            // Theme emoji + name
            TextView emojiView = new TextView(this);
            emojiView.setText(theme.emoji);
            emojiView.setTextSize(18);
            emojiView.setGravity(Gravity.CENTER);
            card.addView(emojiView);

            TextView nameView = new TextView(this);
            nameView.setText(theme.name);
            nameView.setTextSize(12);
            nameView.setTextColor(isActive ? theme.accentColor : Color.parseColor("#CCCCCC"));
            nameView.setGravity(Gravity.CENTER);
            nameView.setPadding(0, dp(4), 0, 0);
            if (isActive) nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(nameView);

            card.setOnClickListener(v -> {
                themeManager.setTheme(idx);
                dismissPanel(panel);
                // Apply atmosphere to persistent surfaces immediately
                applyThemeAtmosphere();
                // Then fade-transition the scrollable content
                android.animation.ValueAnimator fadeOut = android.animation.ValueAnimator.ofFloat(1f, 0f);
                fadeOut.setDuration(150);
                fadeOut.addUpdateListener(a -> screenContainer.setAlpha((float) a.getAnimatedValue()));
                fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        refreshSelectedTab();
                        android.animation.ValueAnimator fadeIn = android.animation.ValueAnimator.ofFloat(0f, 1f);
                        fadeIn.setDuration(250);
                        fadeIn.addUpdateListener(a2 -> screenContainer.setAlpha((float) a2.getAnimatedValue()));
                        fadeIn.start();
                    }
                });
                fadeOut.start();
            });


            row.addView(card);
        }

        panel.addView(sheet);
        ((android.view.ViewGroup) findViewById(android.R.id.content)).addView(panel);

        // Slide up animation
        int screenH = getResources().getDisplayMetrics().heightPixels;
        sheet.setTranslationY(screenH);
        sheet.animate().translationY(0f).setInterpolator(new DecelerateInterpolator()).setDuration(380).start();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(300).start();
    }

    private void dismissPanel(FrameLayout panel) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        LinearLayout sheet = (LinearLayout) panel.getChildAt(1);
        sheet.animate().translationY(screenH).setInterpolator(new DecelerateInterpolator()).setDuration(280)
                .withEndAction(() -> ((android.view.ViewGroup) findViewById(android.R.id.content)).removeView(panel))
                .start();
        panel.getChildAt(0).animate().alpha(0f).setDuration(250).start();
    }
}

