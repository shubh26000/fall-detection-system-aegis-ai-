package com.example.falldetectionapp;

import android.animation.ObjectAnimator;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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

    String espUrl = "http://192.168.137.34";

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
    private final MemberProfile memberProfile = new MemberProfile();
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private SharedPreferences analyticsPrefs;
    private androidx.activity.result.ActivityResultLauncher<Intent> ringtonePickerLauncher;

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
        screenContainer = findViewById(R.id.screenContainer);
        bottomNav = findViewById(R.id.bottomNav);
        contentScroll = findViewById(R.id.contentScroll);
        countdownText = findViewById(R.id.countdownText);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        analyticsPrefs = getSharedPreferences("fall_analytics", MODE_PRIVATE);

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
        startMonitoring();
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
            activeChatMessagesScroll.postDelayed(() -> activeChatMessagesScroll.smoothScrollTo(0, activeChatMessagesContainer.getBottom()), 90);
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

                new Thread(() -> {
                    String data = getESPData();

                    runOnUiThread(() -> {

                        if (data.equals("FALL") && !fallLatched) {
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
                            statusText.setTextColor(getColor(R.color.neon_cyan));
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
        new AlertDialog.Builder(this)
                .setTitle("Possible fall detected")
                .setMessage("Aegis AI recommends confirming safety or calling emergency services.")
                .setCancelable(false)
                .setPositiveButton("Take Action", (d, w) -> showActionMenu())
                .show();
    }

    private void showActionMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Emergency Actions")
                .setItems(new String[]{
                        "Call Emergency",
                        "View Member Profile",
                        "Mark as Safe"
                }, (dialog, which) -> {

                    if (which == 0) {
                        dialEmergencyNumber();
                    }

                    if (which == 1) {
                        showMemberDetailsUI();
                    }

                    if (which == 2) {
                        markAsSafe();
                    }
                })
                .show();
    }

    private void markAsSafe() {
        stopAlert();
        fallLatched = false;
        lastUiSensorState = "";
        sendReset();

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
        try {
            URL url = new URL(espUrl);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
            );

            String line = reader.readLine();
            reader.close();

            return line;

        } catch (Exception e) {
            return "ERROR";
        }
    }

    private void sendReset() {
        try {
            URL url = new URL(espUrl + "/reset");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.getInputStream();
        } catch (Exception ignored) {}
    }

    private void renderHome() {
        selectedTab = "Home";
        updateNavSelection(0);
        resetScreen();
        updateGreetingUI();
        addAssistantMessage("Recommendation", fallLatched ? "Confirm safety, keep the phone nearby, and call " + emergencyNumber + " if needed." : "How are you feeling today? Monitoring is active. Keep pathways clear, use good lighting, and keep the wearable charged.");
        addChatPanel();
        addDashboardGrid();
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
    }

    private void renderMonitoring() {
        selectedTab = "Monitoring";
        updateNavSelection(1);
        resetScreen();
        addSectionTitle("Smart Fall Assistant");
        addInfoCard("Live care status", fallLatched ? "Possible fall detected. Alert systems are active." : "Normal movement pattern. Monitoring is active.", fallLatched ? R.color.alert_red : R.color.neon_cyan);
        addInfoCard("Care guidance", fallLatched ? "Stay nearby, speak calmly, and confirm whether help is needed." : "Everything looks calm. Keep the phone nearby and the wearable comfortably attached.", R.color.soft_cyan);
        addAnalyticsCards();
        fadeScreenIn();
    }

    private void renderMembers() {
        selectedTab = "Members";
        updateNavSelection(2);
        resetScreen();
        addSectionTitle("Member Profile");
        addProfileCard();
        Button addMember = createButton("Edit Member", R.drawable.button_cyan, R.color.amoled_black);
        addMember.setOnClickListener(v -> showMemberEditor());
        screenContainer.addView(addMember);
        fadeScreenIn();
    }

    private void renderEmergency() {
        selectedTab = "Emergency";
        updateNavSelection(3);
        resetScreen();
        addSectionTitle("Emergency");
        addInfoCard("Recommendation", fallLatched ? "Call emergency services if the member does not respond." : "No active emergency. Fast actions are ready.", fallLatched ? R.color.alert_red : R.color.safe_green);
        Button callButton = createButton("Call Emergency " + emergencyNumber, R.drawable.button_alert, R.color.text_primary);
        callButton.setOnClickListener(v -> dialEmergencyNumber());
        screenContainer.addView(callButton);
        if (fallLatched) {
            Button safeButton = createButton("Mark Safe", R.drawable.button_cyan, R.color.amoled_black);
            safeButton.setOnClickListener(v -> markAsSafe());
            screenContainer.addView(safeButton);
        }
        fadeScreenIn();
    }

    private void renderSettings() {
        selectedTab = "Settings";
        updateNavSelection(4);
        resetScreen();
        addSectionTitle("Settings");
        addInfoCard("Emergency number", emergencyNumber, R.color.soft_cyan);
        addInfoCard("Sensitivity mode", "Medium\nBalanced for daily movement.", R.color.warning_gold);
        
        addAlarmSoundCard();

        Button editEmergency = createButton("Change Emergency Number", R.drawable.button_outline, R.color.text_primary);
        editEmergency.setOnClickListener(v -> showEmergencyNumberDialog());
        screenContainer.addView(editEmergency);
        fadeScreenIn();
    }

    private void addAlarmSoundCard() {
        LinearLayout card = createCard();

        TextView title = createText("Alarm Sound", 16, R.color.neon_cyan, true);
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title);

        String customUri = analyticsPrefs.getString("custom_alarm_uri", null);
        String soundStatus = customUri != null ? "Custom Tone Selected" : "Default Continuous Alarm";
        TextView status = createText("Current: " + soundStatus, 14, R.color.text_primary, false);
        status.setPadding(0, 0, 0, dp(16));
        card.addView(status);

        LinearLayout buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);

        Button changeBtn = createButton("Change Sound", R.drawable.button_cyan, R.color.amoled_black);
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

        Button send = createButton("Send", R.drawable.button_cyan, R.color.amoled_black);
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

    private void addDashboardGrid() {
        addIconMetricRow();
        addAnalyticsVisuals();
        addInfoCard("Emergency contact", memberProfile.emergencyContact, R.color.alert_red);
    }

    private void addAnalyticsCards() {
        addSectionTitle("AI Fall Analytics");
        addAnalyticsVisuals();
        addInfoCard("AI analysis", buildLocalAnalyticsSummary(), R.color.neon_cyan);
    }

    private void addIconMetricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(createMetricTile("●", "Status", fallLatched ? "Alert" : "Safe", fallLatched ? R.color.alert_red : R.color.safe_green), weightedTileParams(0, dp(6)));
        row.addView(createMetricTile("▰", "Month", String.valueOf(getFallsThisMonth()), R.color.warning_gold), weightedTileParams(dp(6), dp(6)));
        row.addView(createMetricTile("⌁", "Care", "Active", R.color.soft_cyan), weightedTileParams(dp(6), 0));
        screenContainer.addView(row);
    }

    private LinearLayout createMetricTile(String icon, String label, String value, int accentColor) {
        LinearLayout tile = createCard();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(14), dp(10), dp(14));

        TextView iconView = createText(icon, 24, accentColor, true);
        iconView.setGravity(Gravity.CENTER);
        TextView valueView = createText(value, 18, R.color.text_primary, true);
        valueView.setGravity(Gravity.CENTER);
        TextView labelView = createText(label, 11, R.color.text_secondary, false);
        labelView.setGravity(Gravity.CENTER);

        tile.addView(iconView);
        tile.addView(valueView);
        tile.addView(labelView);
        return tile;
    }

    private LinearLayout.LayoutParams weightedTileParams(int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(leftMargin, 0, rightMargin, dp(12));
        return params;
    }

    private void addAnalyticsVisuals() {
        LinearLayout riskCard = createCard();
        riskCard.addView(createText("Risk window", 13, R.color.warning_gold, true));
        riskCard.addView(createText(getMostLikelyFallTime(), 15, R.color.text_primary, false));
        FallTimeChartView chart = new FallTimeChartView(this);
        chart.setData(getFallTimeBuckets());
        riskCard.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        screenContainer.addView(riskCard);

        LinearLayout monthCard = createCard();
        monthCard.addView(createText("Monthly fall trend", 13, R.color.alert_red, true));
        monthCard.addView(createText(getFallsThisMonth() + " fall event(s) this month", 15, R.color.text_primary, false));
        MonthlyFallsChartView monthChart = new MonthlyFallsChartView(this);
        monthChart.setData(getWeeklyFallBuckets());
        monthCard.addView(monthChart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(142)));
        screenContainer.addView(monthCard);
    }

    private void addChatBubble(ChatMessage chatMessage) {
        addChatBubbleTo(screenContainer, chatMessage);
    }

    private void addChatBubbleTo(LinearLayout parent, ChatMessage chatMessage) {
        LinearLayout card = createCard();
        card.setGravity(chatMessage.fromUser ? Gravity.END : Gravity.START);

        TextView label = createText(chatMessage.sender, 12, chatMessage.fromUser ? R.color.warning_gold : R.color.neon_cyan, true);

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

        String body = "Age: " + memberProfile.age +
                "\nCondition: " + memberProfile.condition +
                "\nBlood group: " + memberProfile.bloodGroup +
                "\nEmergency: " + memberProfile.emergencyContact +
                "\nAddress: " + memberProfile.address +
                "\nNotes: " + memberProfile.notes;

        TextView message = createText(body, 15, R.color.text_primary, false);
        message.setLineSpacing(4f, 1f);
        message.setPadding(0, dp(7), 0, 0);

        card.addView(message);
        screenContainer.addView(card);
    }

    private void addAssistantMessage(String title, String body) {
        LinearLayout card = createCard();

        TextView label = createText(title, 13, R.color.neon_cyan, true);
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
        section.setPadding(0, 0, 0, dp(10));
        screenContainer.addView(section);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_surface);
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

        Button okBtn = createButton("OK", R.drawable.button_cyan, R.color.amoled_black);
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
        Button saveBtn = createButton("Save", R.drawable.button_cyan, R.color.amoled_black);

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
        Button saveBtn = createButton("Save", R.drawable.button_cyan, R.color.amoled_black);

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
            navItems[i].setTextColor(getColor(selected ? R.color.neon_cyan : R.color.text_secondary));
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

    public static class TypingDotsView extends View {
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

            for (int i = 0; i < 3; i++) {
                double phase = ((System.currentTimeMillis() - startTime) / 260.0) + i * 0.75;
                float radius = (float) (5.5 + Math.sin(phase) * 2.5);
                int alpha = (int) (150 + Math.sin(phase) * 80);
                paint.setColor(Color.argb(Math.max(70, Math.min(255, alpha)), 0, 245, 212));
                canvas.drawCircle(startX + i * 24f, centerY, radius, paint);
            }

            postInvalidateDelayed(45);
        }
    }

    public static class FallTimeChartView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] data = new int[]{0, 0, 0, 0};
        private final String[] labels = new String[]{"AM", "PM", "EVE", "NGT"};

        public FallTimeChartView(android.content.Context context) {
            super(context);
        }

        public FallTimeChartView(android.content.Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void setData(int[] data) {
            this.data = data;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int max = 1;
            for (int value : data) {
                max = Math.max(max, value);
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(Color.argb(70, 124, 247, 234));
            for (int i = 1; i <= 3; i++) {
                float y = height - 34 - (height - 52) * i / 3f;
                canvas.drawLine(8, y, width - 8, y, paint);
            }

            int barArea = width - 32;
            int barWidth = Math.max(18, barArea / 7);
            for (int i = 0; i < data.length; i++) {
                float center = 20 + (barArea / 4f) * i + barArea / 8f;
                float barHeight = (height - 64) * data[i] / (float) max;
                float top = height - 34 - Math.max(10, barHeight);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(0, 245, 212));
                canvas.drawRoundRect(center - barWidth, top, center + barWidth, height - 34, 14, 14, paint);
                paint.setColor(Color.rgb(244, 251, 255));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(22f);
                canvas.drawText(String.valueOf(data[i]), center, Math.max(24, top - 8), paint);
                paint.setColor(Color.rgb(159, 179, 189));
                paint.setTextSize(20f);
                canvas.drawText(labels[i], center, height - 8, paint);
            }
        }
    }

    public static class MonthlyFallsChartView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] data = new int[]{0, 0, 0, 0, 0};

        public MonthlyFallsChartView(android.content.Context context) {
            super(context);
        }

        public MonthlyFallsChartView(android.content.Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void setData(int[] data) {
            this.data = data;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int max = 1;
            for (int value : data) {
                max = Math.max(max, value);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(36, 255, 77, 94));
            canvas.drawRoundRect(0, 12, width, height - 8, 24, 24, paint);

            int usableWidth = width - 34;
            int barWidth = Math.max(14, usableWidth / 12);
            for (int i = 0; i < data.length; i++) {
                float center = 17 + (usableWidth / 5f) * i + usableWidth / 10f;
                float barHeight = (height - 62) * data[i] / (float) max;
                float top = height - 32 - Math.max(8, barHeight);

                paint.setColor(Color.rgb(255, 77, 94));
                canvas.drawRoundRect(center - barWidth, top, center + barWidth, height - 32, 12, 12, paint);
                paint.setColor(Color.rgb(244, 251, 255));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(20f);
                canvas.drawText(String.valueOf(data[i]), center, Math.max(24, top - 7), paint);
                paint.setColor(Color.rgb(159, 179, 189));
                paint.setTextSize(18f);
                canvas.drawText("W" + (i + 1), center, height - 8, paint);
            }
        }
    }
}
