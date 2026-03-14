package com.example.redbutton;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class MainActivity extends Activity {

    // === CONFIGURATION ===
    // Staff (who)
    private static final String[] STAFF = {
        "Dr. Smith", "Ast1", "Ast2", "Hyg1", "Hyg2", "Rcp1", "Mgr1"
    };

    // Actions (what)
    private static final String[] ACTIONS = {
        "Pt. Ready", "Pt. Waiting", "Exam", "Px. Chart", "Tx", "Phone", "Help", "Msg"
    };

    // Locations (where)
    private static final String[] LOCATIONS = {
        "Op1", "Op2", "Op3", "Op4", "Op5",
        "Op6", "Op7", "Op8", "Op9", "Op10"
    };

    // Room colors — inspired by real chair colors in the office
    private static final java.util.Map<String, Integer> ROOM_COLORS = new java.util.LinkedHashMap<String, Integer>() {{
        // Op1–Op4: orange chairs — warm amber/orange gradient
        put("Op1",  Color.parseColor("#FFB300")); // Amber
        put("Op2",  Color.parseColor("#FB8C00")); // Orange
        put("Op3",  Color.parseColor("#F4511E")); // Deep Orange
        put("Op4",  Color.parseColor("#BF360C")); // Burnt Orange
        // Op5–Op8: black chairs — dark charcoal/slate gradient
        put("Op5",  Color.parseColor("#455A64")); // Blue Grey
        put("Op6",  Color.parseColor("#37474F")); // Darker Blue Grey
        put("Op7",  Color.parseColor("#263238")); // Near Black
        put("Op8",  Color.parseColor("#1C2B30")); // Almost Black
        // Op9–Op10: Dr. Riad — cyan
        put("Op9",  Color.parseColor("#00ACC1")); // Cyan
        put("Op10", Color.parseColor("#00838F")); // Deep Cyan
    }};

    // Multicast config
    private static final String MULTICAST_GROUP = "239.255.42.1";
    private static final int PORT = 9876;

    // === STATE ===
    private String selectedStaff = null;
    private String selectedAction = null;
    private String selectedLocation = null;

    private final List<Button> staffButtons = new ArrayList<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private final List<Button> locationButtons = new ArrayList<>();

    private LinearLayout messageLog;
    private ScrollView messageScroll;
    private Button sendButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MulticastSocket receiveSocket;
    private volatile boolean listening = true;

    // Track active message cards by ID for network ACK
    private final Map<String, LinearLayout> activeCards = new HashMap<>();
    private final Map<String, TextView> activeCardMsgViews = new HashMap<>();

    // Current room color — drives all button highlights
    private int currentRoomColor = Color.parseColor("#1E88E5"); // default blue

    // Pending room colors for alternating flash (multiple unacked messages)
    private final List<Integer> pendingRoomColors = new ArrayList<>();
    private final Map<String, Integer> msgIdToRoomColor = new HashMap<>();
    private int flashIndex = 0;
    private Runnable flashRunnable;
    private static final int FLASH_INTERVAL_MS = 800;

    // Colors
    private static final String VERSION = "v0.5.1";

    private static final int COLOR_BG = Color.parseColor("#0a1628");
    private static final int COLOR_STAFF = Color.parseColor("#1565C0");
    private static final int COLOR_ACTION = Color.parseColor("#1976D2");
    private static final int COLOR_LOCATION = Color.parseColor("#1E88E5");
    private static final int COLOR_SELECTED = Color.parseColor("#C6D64A");
    private static final int COLOR_SELECTED_TEXT = Color.parseColor("#1a1a1a");
    private static final int COLOR_SEND = Color.parseColor("#42A5F5");
    private static final int COLOR_MSG_BG = Color.parseColor("#0d1f3c");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        // === Message log (top) ===
        messageScroll = new ScrollView(this);
        messageScroll.setBackgroundColor(COLOR_MSG_BG);
        messageScroll.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(100));
        scrollParams.bottomMargin = dp(8);
        messageScroll.setLayoutParams(scrollParams);

        messageLog = new LinearLayout(this);
        messageLog.setOrientation(LinearLayout.VERTICAL);
        messageScroll.addView(messageLog);
        root.addView(messageScroll);

        // === Op row (room — drives card color) ===
        TextView locLabel = makeLabel("OP");
        root.addView(locLabel);
        GridLayout locGrid = makeRoomGrid(LOCATIONS, locationButtons, v -> {
            selectButton(locationButtons, (Button) v, b -> selectedLocation = b);
        });
        root.addView(locGrid);

        // === Action row ===
        TextView actionLabel = makeLabel("ACTION");
        root.addView(actionLabel);
        GridLayout actionGrid = makeGrid(ACTIONS, actionButtons, COLOR_ACTION, v -> {
            selectButton(actionButtons, (Button) v, b -> selectedAction = b);
        });
        root.addView(actionGrid);

        // === Staff row ===
        TextView staffLabel = makeLabel("STAFF");
        root.addView(staffLabel);
        GridLayout staffGrid = makeGrid(STAFF, staffButtons, COLOR_STAFF, v -> {
            selectButton(staffButtons, (Button) v, b -> selectedStaff = b);
        });
        root.addView(staffGrid);

        // === Bottom bar: Send only ===
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(12);
        bottomBar.setLayoutParams(barParams);

        sendButton = makeControlButton("Send", COLOR_SEND, v -> sendMessage());
        bottomBar.addView(sendButton);
        root.addView(bottomBar);

        // === Version label (bottom-right) ===
        TextView versionLabel = new TextView(this);
        versionLabel.setText(VERSION);
        versionLabel.setTextColor(Color.parseColor("#37474F"));
        versionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        versionLabel.setGravity(Gravity.END);
        LinearLayout.LayoutParams vParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vParams.topMargin = dp(4);
        versionLabel.setLayoutParams(vParams);
        root.addView(versionLabel);

        // Wrap root in ScrollView so nothing gets clipped on small screens
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(COLOR_BG);
        rootScroll.addView(root);
        setContentView(rootScroll);

        // Start listening for multicast messages
        startListening();

        // Welcome message
        addLogMessage("System", "Ready", "");
    }

    // === UI Helpers ===

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#90CAF9"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(4), dp(6), 0, dp(2));
        return tv;
    }

    private GridLayout makeGrid(String[] labels, List<Button> buttonList,
                                 int color, View.OnClickListener listener) {
        GridLayout grid = new GridLayout(this);
        // Auto-size: up to 7 columns for landscape tablets
        int cols = Math.min(labels.length, 7);
        grid.setColumnCount(cols);
        grid.setUseDefaultMargins(false);

        for (String label : labels) {
            Button btn = new Button(this);
            btn.setText(label);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setAllCaps(false);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(color);
            btn.setBackground(bg);
            btn.setTag(color); // store original color
            btn.setPadding(dp(12), dp(10), dp(12), dp(10));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            btn.setLayoutParams(params);

            btn.setOnClickListener(listener);
            buttonList.add(btn);
            grid.addView(btn);
        }
        return grid;
    }

    private GridLayout makeRoomGrid(String[] labels, List<Button> buttonList,
                                     View.OnClickListener listener) {
        GridLayout grid = new GridLayout(this);
        int cols = Math.min(labels.length, 5); // 5 per row for 10 rooms
        grid.setColumnCount(cols);
        grid.setUseDefaultMargins(false);

        for (String label : labels) {
            int roomColor = ROOM_COLORS.containsKey(label)
                ? ROOM_COLORS.get(label)
                : Color.parseColor("#1E88E5");

            Button btn = new Button(this);
            btn.setText(label);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setAllCaps(false);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(roomColor);
            btn.setBackground(bg);
            btn.setTag(roomColor);
            btn.setPadding(dp(12), dp(10), dp(12), dp(10));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            btn.setLayoutParams(params);

            btn.setOnClickListener(listener);
            buttonList.add(btn);
            grid.addView(btn);
        }
        return grid;
    }

    private Button makeControlButton(String text, int color, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(color);
        btn.setBackground(bg);
        btn.setPadding(dp(24), dp(12), dp(24), dp(12));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        btn.setLayoutParams(params);

        btn.setOnClickListener(listener);
        return btn;
    }

    interface StringSetter { void set(String value); }

    private void selectButton(List<Button> group, Button selected, StringSetter setter) {
        String selectedText = selected.getText().toString();

        // If this is a room button, update the global room color
        if (ROOM_COLORS.containsKey(selectedText)) {
            currentRoomColor = ROOM_COLORS.get(selectedText);
            // Refresh all already-selected action/staff buttons to new room color
            refreshNonRoomHighlights();
        }

        for (int i = 0; i < group.size(); i++) {
            Button btn = group.get(i);
            int origColor = (int) btn.getTag();
            boolean isSelected = (btn == selected);
            if (isSelected) {
                setButtonAppearanceHighlighted(btn, currentRoomColor);
            } else {
                setButtonAppearance(btn, origColor, Color.WHITE);
            }
        }
        setter.set(selectedText);
    }

    private void refreshNonRoomHighlights() {
        // Re-highlight selected action and staff buttons in the new room color
        for (Button btn : actionButtons) {
            if (btn.getText().toString().equals(selectedAction)) {
                setButtonAppearanceHighlighted(btn, currentRoomColor);
            }
        }
        for (Button btn : staffButtons) {
            if (btn.getText().toString().equals(selectedStaff)) {
                setButtonAppearanceHighlighted(btn, currentRoomColor);
            }
        }
    }

    private void setButtonAppearance(Button btn, int bgColor, int textColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(bgColor);
        btn.setBackground(bg);
        btn.setTextColor(textColor);
    }

    private void setButtonAppearanceHighlighted(Button btn, int roomColor) {
        // Brighten the room color for selected state + white stroke border
        int r = Math.min(255, (int)(Color.red(roomColor) * 1.5f + 60));
        int g = Math.min(255, (int)(Color.green(roomColor) * 1.5f + 60));
        int b = Math.min(255, (int)(Color.blue(roomColor) * 1.5f + 60));
        int brightColor = Color.rgb(r, g, b);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(brightColor);
        bg.setStroke(dp(3), Color.WHITE);
        btn.setBackground(bg);
        btn.setTextColor(Color.WHITE);
    }

    private void resetSelection() {
        selectedStaff = null;
        selectedAction = null;
        selectedLocation = null;
        currentRoomColor = Color.parseColor("#1E88E5"); // reset to default
        resetGroup(staffButtons);
        resetGroup(actionButtons);
        resetGroup(locationButtons);
        // Clear message log
        runOnUiThread(() -> {
            messageLog.removeAllViews();
            addLogMessage("System", "Ready", "");
        });
    }

    private void resetGroup(List<Button> group) {
        for (Button btn : group) {
            int origColor = (int) btn.getTag();
            setButtonAppearance(btn, origColor, Color.WHITE);
        }
    }

    // === Pending color flash ===

    private void addPendingColor(String msgId, int roomColor) {
        if (!pendingRoomColors.contains(roomColor)) {
            pendingRoomColors.add(roomColor);
        }
        msgIdToRoomColor.put(msgId, roomColor);
        startFlashing();
    }

    private void removePendingColor(String msgId) {
        Integer color = msgIdToRoomColor.remove(msgId);
        if (color != null && !msgIdToRoomColor.containsValue(color)) {
            pendingRoomColors.remove(color);
        }
        if (pendingRoomColors.isEmpty()) {
            stopFlashing();
        }
    }

    private void startFlashing() {
        if (flashRunnable != null) return; // already running
        flashRunnable = new Runnable() {
            @Override public void run() {
                if (pendingRoomColors.isEmpty()) { stopFlashing(); return; }
                int color = pendingRoomColors.get(flashIndex % pendingRoomColors.size());
                flashIndex++;
                // Tint the message scroll background to the current pending room color
                int tinted = Color.argb(180,
                    (int)(Color.red(color) * 0.3f),
                    (int)(Color.green(color) * 0.3f),
                    (int)(Color.blue(color) * 0.3f));
                messageScroll.setBackgroundColor(tinted);
                handler.postDelayed(this, FLASH_INTERVAL_MS);
            }
        };
        handler.post(flashRunnable);
    }

    private void stopFlashing() {
        if (flashRunnable != null) {
            handler.removeCallbacks(flashRunnable);
            flashRunnable = null;
        }
        flashIndex = 0;
        messageScroll.setBackgroundColor(Color.parseColor("#0d1f3c")); // restore default
    }

    // === Messaging ===

    private void sendMessage() {
        if (selectedStaff == null && selectedAction == null && selectedLocation == null) {
            Toast.makeText(this, "Select at least one option", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        if (selectedLocation != null) msg.append(selectedLocation);
        if (selectedAction != null) {
            if (msg.length() > 0) msg.append(" — ");
            msg.append(selectedAction);
        }
        if (selectedStaff != null) {
            if (msg.length() > 0) msg.append(" — ");
            msg.append(selectedStaff);
        }

        // Use timestamp as message ID — same value seen by all tablets from the broadcast
        String msgId = String.valueOf(System.currentTimeMillis());
        // Don't show locally — the multicast loopback will deliver it back
        broadcastMessage("MSG|" + msgId + "|" + msg.toString());

        // Keep selection highlighted after send
    }

    private void acknowledgeCard(String msgId) {
        LinearLayout card = activeCards.get(msgId);
        if (card == null) return;
        messageLog.removeView(card);
        activeCards.remove(msgId);
        activeCardMsgViews.remove(msgId);
        removePendingColor(msgId);
    }

    private void addLogMessage(String message, String msgId, String extra) {
        String time = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date());
        boolean isSystem = message.equals("System");

        // Determine card color from room (Op1–Op10) in message
        int cardColor = Color.parseColor("#1A2A3A"); // default dark
        for (java.util.Map.Entry<String, Integer> e : ROOM_COLORS.entrySet()) {
            if (message.contains(e.getKey())) {
                cardColor = e.getValue();
                break;
            }
        }
        final int finalCardColor = cardColor;
        final String finalMessage = message;
        final String finalTime = time;
        final String finalMsgId = msgId;

        handler.post(() -> {
            // Keep last 30 messages
            if (messageLog.getChildCount() > 30) {
                messageLog.removeViewAt(messageLog.getChildCount() - 1);
            }

            // === Card container ===
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(cardParams);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(8));
            cardBg.setColor(isSystem ? Color.parseColor("#1A2334") : finalCardColor);
            card.setBackground(cardBg);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));

            // Register card for network ACK and start color flash
            if (!isSystem && !finalMsgId.isEmpty()) {
                activeCards.put(finalMsgId, card);
                addPendingColor(finalMsgId, finalCardColor);
            }

            // === Top row: time label ===
            TextView timeView = new TextView(this);
            timeView.setText(finalTime);
            timeView.setTextColor(Color.parseColor("#B0BEC5"));
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            card.addView(timeView);

            // === Middle row: message text (big) ===
            TextView msgView = new TextView(this);
            msgView.setText(finalMessage);
            msgView.setTextColor(Color.WHITE);
            msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isSystem ? 13 : 17);
            msgView.setTypeface(isSystem ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            msgView.setPadding(0, dp(4), 0, dp(8));
            card.addView(msgView);

            // Register msgView for network ACK
            if (!isSystem && !finalMsgId.isEmpty()) {
                activeCardMsgViews.put(finalMsgId, msgView);
            }

            // === ✓ OK button only (non-system only) ===
            if (!isSystem) {
                LinearLayout btnRow = new LinearLayout(this);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.END);
                btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                Button ackBtn = new Button(this);
                ackBtn.setText("✓ OK");
                ackBtn.setTextColor(Color.WHITE);
                ackBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                ackBtn.setAllCaps(false);
                GradientDrawable ackBg = new GradientDrawable();
                ackBg.setCornerRadius(dp(6));
                ackBg.setColor(Color.parseColor("#1B5E20")); // dark green
                ackBtn.setBackground(ackBg);
                ackBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
                ackBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                ackBtn.setOnClickListener(v -> {
                    acknowledgeCard(finalMsgId);
                    if (!finalMsgId.isEmpty()) {
                        broadcastMessage("ACK|" + finalMsgId);
                    }
                });

                btnRow.addView(ackBtn);
                card.addView(btnRow);
            }

            messageLog.addView(card, 0); // newest on top
            messageScroll.post(() -> messageScroll.scrollTo(0, 0));
        });
    }

    // === Network: UDP Multicast ===

    private void broadcastMessage(String msg) {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                byte[] data = msg.getBytes("UTF-8");
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                DatagramPacket packet = new DatagramPacket(data, data.length, group, PORT);
                socket.send(packet);
                socket.close();
            } catch (Exception e) {
                handler.post(() ->
                    Toast.makeText(this, "Send failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void startListening() {
        new Thread(() -> {
            try {
                receiveSocket = new MulticastSocket(PORT);
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                receiveSocket.joinGroup(group);

                byte[] buf = new byte[1024];
                while (listening) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    receiveSocket.receive(packet);
                    String received = new String(packet.getData(), 0, packet.getLength(), "UTF-8");

                    if (received.startsWith("ACK|")) {
                        // Another tablet acknowledged — dim that card here too
                        String ackId = received.substring(4);
                        handler.post(() -> acknowledgeCard(ackId));
                    } else {
                        // Parse: "MSG|id|message text"
                        String msgId = "";
                        String message = received;
                        if (received.startsWith("MSG|")) {
                            String[] parts = received.split("\\|", 3);
                            msgId = parts.length > 1 ? parts[1] : "";
                            message = parts.length > 2 ? parts[2] : received;
                        }
                        // Play buzzer
                        playBuzzer();
                        final String finalMsgId = msgId;
                        final String finalMsg = message;
                        handler.post(() -> addLogMessage(finalMsg, finalMsgId, ""));
                    }
                }
            } catch (Exception e) {
                if (listening) {
                    handler.post(() ->
                        Toast.makeText(this, "Listen error: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void playBuzzer() {
        // Max volume on ALARM stream (bypasses silent/notification volume)
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }
            // Three sharp beeps
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
            handler.postDelayed(() -> tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200), 300);
            handler.postDelayed(() -> tg.startTone(ToneGenerator.TONE_PROP_BEEP, 400), 600);
            handler.postDelayed(tg::release, 1200);
        } catch (Exception ignored) {}

        // Vibration pattern: short-short-long
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 150, 100, 150, 100, 400};
                v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}

        // Wake screen
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "OakBuzzer:alert");
                wl.acquire(5000);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        listening = false;
        if (receiveSocket != null) {
            try { receiveSocket.close(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
