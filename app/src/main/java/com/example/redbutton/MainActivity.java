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
import java.util.List;
import java.util.Locale;

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
        "Op1", "Op2", "Op3", "Op4", "Op5", "LN1", "LN2", "LN3", "LN4"
    };

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
    private Button resetButton;
    private Button sendAllButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MulticastSocket receiveSocket;
    private volatile boolean listening = true;

    // Colors
    private static final int COLOR_BG = Color.parseColor("#0a1628");
    private static final int COLOR_STAFF = Color.parseColor("#1565C0");
    private static final int COLOR_ACTION = Color.parseColor("#1976D2");
    private static final int COLOR_LOCATION = Color.parseColor("#1E88E5");
    private static final int COLOR_SELECTED = Color.parseColor("#C6D64A");
    private static final int COLOR_SELECTED_TEXT = Color.parseColor("#1a1a1a");
    private static final int COLOR_SEND = Color.parseColor("#42A5F5");
    private static final int COLOR_SEND_ALL = Color.parseColor("#66BB6A");
    private static final int COLOR_RESET = Color.parseColor("#EF5350");
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

        // === Staff row ===
        TextView staffLabel = makeLabel("STAFF");
        root.addView(staffLabel);
        GridLayout staffGrid = makeGrid(STAFF, staffButtons, COLOR_STAFF, v -> {
            selectButton(staffButtons, (Button) v, b -> selectedStaff = b);
        });
        root.addView(staffGrid);

        // === Action row ===
        TextView actionLabel = makeLabel("ACTION");
        root.addView(actionLabel);
        GridLayout actionGrid = makeGrid(ACTIONS, actionButtons, COLOR_ACTION, v -> {
            selectButton(actionButtons, (Button) v, b -> selectedAction = b);
        });
        root.addView(actionGrid);

        // === Location row ===
        TextView locLabel = makeLabel("LOCATION");
        root.addView(locLabel);
        GridLayout locGrid = makeGrid(LOCATIONS, locationButtons, COLOR_LOCATION, v -> {
            selectButton(locationButtons, (Button) v, b -> selectedLocation = b);
        });
        root.addView(locGrid);

        // === Bottom bar: Reset | Send | Send All ===
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(12);
        bottomBar.setLayoutParams(barParams);

        resetButton = makeControlButton("Reset", COLOR_RESET, v -> resetSelection());
        sendButton = makeControlButton("Send", COLOR_SEND, v -> sendMessage(false));
        sendAllButton = makeControlButton("Send All", COLOR_SEND_ALL, v -> sendMessage(true));

        bottomBar.addView(resetButton);
        bottomBar.addView(sendButton);
        bottomBar.addView(sendAllButton);
        root.addView(bottomBar);

        setContentView(root);

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
        for (int i = 0; i < group.size(); i++) {
            Button btn = group.get(i);
            int origColor = (int) btn.getTag();
            boolean isSelected = (btn == selected);
            setButtonAppearance(btn, isSelected ? COLOR_SELECTED : origColor,
                                isSelected ? COLOR_SELECTED_TEXT : Color.WHITE);
        }
        setter.set(selectedText);
    }

    private void setButtonAppearance(Button btn, int bgColor, int textColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(bgColor);
        btn.setBackground(bg);
        btn.setTextColor(textColor);
    }

    private void resetSelection() {
        selectedStaff = null;
        selectedAction = null;
        selectedLocation = null;
        resetGroup(staffButtons);
        resetGroup(actionButtons);
        resetGroup(locationButtons);
    }

    private void resetGroup(List<Button> group) {
        for (Button btn : group) {
            int origColor = (int) btn.getTag();
            setButtonAppearance(btn, origColor, Color.WHITE);
        }
    }

    // === Messaging ===

    private void sendMessage(boolean sendToAll) {
        if (selectedStaff == null && selectedAction == null && selectedLocation == null) {
            Toast.makeText(this, "Select at least one option", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        if (selectedStaff != null) msg.append(selectedStaff);
        if (selectedAction != null) {
            if (msg.length() > 0) msg.append(" — ");
            msg.append(selectedAction);
        }
        if (selectedLocation != null) {
            if (msg.length() > 0) msg.append(" — ");
            msg.append(selectedLocation);
        }

        String fullMsg = msg.toString();
        String target = sendToAll ? "ALL" : "GROUP";

        // Don't show locally — the multicast loopback will deliver it back
        // This prevents duplicate messages in the log
        broadcastMessage(fullMsg + "|" + target);

        // Reset after send
        resetSelection();
    }

    private void addLogMessage(String message, String target, String extra) {
        String time = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date());

        TextView tv = new TextView(this);
        String display = time + "  " + message;
        if (target != null && !target.isEmpty() && !target.equals("")) {
            display += "  [" + target + "]";
        }
        tv.setText(display);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(2), 0, dp(2));

        handler.post(() -> {
            // Keep last 50 messages
            if (messageLog.getChildCount() > 50) {
                messageLog.removeViewAt(0);
            }
            messageLog.addView(tv, 0); // newest on top
            messageScroll.fullScroll(View.FOCUS_UP);
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

                    // Parse: "message|target"
                    String[] parts = received.split("\\|", 2);
                    String message = parts[0];
                    String target = parts.length > 1 ? parts[1] : "";

                    // Play buzzer
                    playBuzzer();

                    // Add to log on UI thread
                    handler.post(() -> addLogMessage(message, target, ""));
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
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 300);
            handler.postDelayed(tg::release, 500);
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
