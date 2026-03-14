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

    // Room colors — actual room identity colors per Dr. Riad
    private static final java.util.Map<String, Integer> ROOM_COLORS = new java.util.LinkedHashMap<String, Integer>() {{
        put("Op1",  Color.parseColor("#D7CCC8")); // Beige
        put("Op2",  Color.parseColor("#2E7D32")); // Dark Green
        put("Op3",  Color.parseColor("#F4511E")); // Orange
        put("Op4",  Color.parseColor("#E91E63")); // Pink
        put("Op5",  Color.parseColor("#A1672A")); // Honey Brown
        put("Op6",  Color.parseColor("#4E342E")); // Dark Brown/Grey
        put("Op7",  Color.parseColor("#8BC34A")); // Lime Green
        put("Op8",  Color.parseColor("#212121")); // Black
        put("Op9",  Color.parseColor("#E53935")); // Red
        put("Op10", Color.parseColor("#7B1FA2")); // Purple
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
    // Parallel list: base (idle) color for each location button — always COLOR_LOCATION (blue)
    // Room color is stored in btn.getTag() for pending-highlight use only
    private final List<Integer> locationBaseColors = new ArrayList<>();

    private LinearLayout messageLog;
    private ScrollView messageScroll;
    private Button sendButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MulticastSocket receiveSocket;
    private volatile boolean listening = true;

    // Track active message cards by ID for network ACK
    private final Map<String, LinearLayout> activeCards = new HashMap<>();
    private final Map<String, TextView> activeCardMsgViews = new HashMap<>();

    // Current room color — drives all button highlights when composing
    private int currentRoomColor = Color.parseColor("#1E88E5"); // default blue

    // Pending button highlights: msgId -> [op, action, staff, roomColor]
    private final Map<String, String[]> pendingHighlights = new HashMap<>();

    // msgId -> op button that shows ✓ OK overlay
    private final Map<String, Button> pendingOpButtons = new HashMap<>();

    // Action conflict flash: per-button runnables
    private final Map<Button, Runnable> flashRunnables = new HashMap<>();
    private final Map<Button, Integer> flashIndices = new HashMap<>();

    // Colors
    private static final String VERSION = "v1.0.3";

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

        // === Message log (top) — compact strip ===
        messageScroll = new ScrollView(this);
        messageScroll.setBackgroundColor(COLOR_MSG_BG);
        messageScroll.setPadding(dp(4), dp(2), dp(4), dp(2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(90)); // compact but readable
        scrollParams.bottomMargin = dp(6);
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

        // Small reset button
        Button resetBtn = new Button(this);
        resetBtn.setText("Reset");
        resetBtn.setTextColor(Color.parseColor("#EF9A9A"));
        resetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        resetBtn.setAllCaps(false);
        GradientDrawable resetBg = new GradientDrawable();
        resetBg.setCornerRadius(dp(6));
        resetBg.setColor(Color.parseColor("#1A1A2E"));
        resetBg.setStroke(dp(1), Color.parseColor("#B71C1C"));
        resetBtn.setBackground(resetBg);
        resetBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetParams.setMargins(0, 0, dp(8), 0);
        resetBtn.setLayoutParams(resetParams);
        resetBtn.setOnClickListener(v -> resetAllCommands());

        sendButton = makeControlButton("Send", COLOR_SEND, v -> sendMessage());
        bottomBar.addView(resetBtn);
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
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setAllCaps(false);

            // Start blue like all other buttons; room color activates on pending request
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(COLOR_LOCATION); // same blue as action/staff buttons
            btn.setBackground(bg);
            btn.setTag(roomColor); // store room color for later activation
            btn.setPadding(dp(12), dp(10), dp(12), dp(10));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            btn.setLayoutParams(params);

            btn.setOnClickListener(listener);
            buttonList.add(btn);
            locationBaseColors.add(COLOR_LOCATION); // base color is always blue
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
        boolean isOpGroup = (group == locationButtons);

        // If Op group, update room color from the button's tag (room color stored there)
        if (isOpGroup) {
            currentRoomColor = (int) selected.getTag();
        }

        for (int i = 0; i < group.size(); i++) {
            Button btn = group.get(i);
            boolean isSelected = (btn == selected);

            if (isOpGroup) {
                // Location buttons: base color is always blue (never read from tag here)
                if (isSelected) {
                    // Highlight selected op with its own room color
                    setButtonAppearanceHighlighted(btn, currentRoomColor);
                } else {
                    // All other op buttons: plain blue
                    setButtonAppearance(btn, COLOR_LOCATION, Color.WHITE);
                }
            } else {
                // Action / Staff buttons: tag holds their original color
                int origColor = (int) btn.getTag();
                if (isSelected) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(6));
                    bg.setColor(origColor);
                    bg.setStroke(dp(3), Color.argb(180, 255, 255, 255));
                    btn.setBackground(bg);
                    btn.setTextColor(Color.WHITE);
                } else {
                    setButtonAppearance(btn, origColor, Color.WHITE);
                }
            }
        }
        setter.set(selected.getText().toString());
    }

    private void setButtonAppearance(Button btn, int bgColor, int textColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(bgColor);
        btn.setBackground(bg);
        btn.setTextColor(textColor);
    }

    private void setButtonAppearanceHighlighted(Button btn, int roomColor) {
        // Fill with room color + faint white outline
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(roomColor);
        bg.setStroke(dp(1), Color.argb(80, 255, 255, 255)); // faint white outline
        btn.setBackground(bg);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void resetAllCommands() {
        // Broadcast ACK for every pending message so all tablets clear
        for (String msgId : new ArrayList<>(activeCards.keySet())) {
            broadcastMessage("ACK|" + msgId);
        }
        // Clear all state
        activeCards.clear();
        activeCardMsgViews.clear();
        pendingHighlights.clear();
        pendingOpButtons.clear();
        selectedStaff = null;
        selectedAction = null;
        selectedLocation = null;
        currentRoomColor = Color.parseColor("#1E88E5");
        for (Runnable r : flashRunnables.values()) handler.removeCallbacks(r);
        flashRunnables.clear();
        flashIndices.clear();
        // Reset all buttons back to blue, restore Op labels and listeners
        resetGroup(staffButtons);
        resetGroup(actionButtons);
        resetLocationButtons();
    }

    private void resetSelection() {
        selectedStaff = null;
        selectedAction = null;
        selectedLocation = null;
        currentRoomColor = Color.parseColor("#1E88E5"); // reset to default
        resetGroup(staffButtons);
        resetGroup(actionButtons);
        resetLocationButtons();
        // Re-apply any still-pending network highlights after reset
        redrawButtonHighlights();
        // Clear message log
        runOnUiThread(() -> {
            messageLog.removeAllViews();
            addLogMessage("System", "Ready", "");
        });
    }

    /** Reset Action or Staff button group to their original tag colors. */
    private void resetGroup(List<Button> group) {
        for (Button btn : group) {
            setButtonAppearance(btn, (int) btn.getTag(), Color.WHITE);
        }
    }

    /** Reset Op/location buttons to plain blue — never reads from tag. */
    private void resetLocationButtons() {
        for (int i = 0; i < locationButtons.size() && i < LOCATIONS.length; i++) {
            Button btn = locationButtons.get(i);
            btn.setText(LOCATIONS[i]);
            setButtonAppearance(btn, COLOR_LOCATION, Color.WHITE);
            final int idx = i;
            btn.setOnClickListener(v ->
                selectButton(locationButtons, locationButtons.get(idx), b -> selectedLocation = b));
        }
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
        String opPart = selectedLocation != null ? selectedLocation : "";
        String actionPart = selectedAction != null ? selectedAction : "";
        String staffPart = selectedStaff != null ? selectedStaff : "";
        String fullMsg = msg.toString();

        // Apply highlight locally immediately (sender may not receive own multicast)
        addLogMessage(fullMsg, msgId, "");
        applyPendingHighlight(msgId, opPart, actionPart, staffPart);

        // Broadcast to all other tablets
        // Format: MSG|id|displayText|op|action|staff
        broadcastMessage("MSG|" + msgId + "|" + fullMsg
            + "|" + opPart + "|" + actionPart + "|" + staffPart);

        // Clear local composing selection then redraw (handles pending highlights)
        selectedStaff = null;
        selectedAction = null;
        selectedLocation = null;
        currentRoomColor = Color.parseColor("#1E88E5");
        redrawButtonHighlights();
    }

    private void acknowledgeCard(String msgId) {
        // Remove log card
        LinearLayout card = activeCards.get(msgId);
        if (card != null) messageLog.removeView(card);
        activeCards.remove(msgId);
        activeCardMsgViews.remove(msgId);
        // Restore Op button to blue
        pendingOpButtons.remove(msgId);
        // Clear highlight — redrawButtonHighlights will restore remaining pending state
        clearPendingHighlight(msgId);
    }

    private void applyPendingHighlight(String msgId, String op, String action, String staff) {
        int roomColor = ROOM_COLORS.containsKey(op) ? ROOM_COLORS.get(op) : Color.parseColor("#1E88E5");
        pendingHighlights.put(msgId, new String[]{op, action, staff, String.valueOf(roomColor)});
        // Find and store the Op button for ✓ OK overlay
        for (Button btn : locationButtons) {
            if (btn.getText().toString().equals(op) || btn.getText().toString().equals("✓ " + op)) {
                pendingOpButtons.put(msgId, btn);
                break;
            }
        }
        redrawButtonHighlights();
    }

    private void clearPendingHighlight(String msgId) {
        pendingHighlights.remove(msgId);
        redrawButtonHighlights();
    }

    private void redrawButtonHighlights() {
        // Stop all existing flashes
        for (Runnable r : flashRunnables.values()) handler.removeCallbacks(r);
        flashRunnables.clear();
        flashIndices.clear();

        // Reset all buttons to base color and original labels
        resetGroup(staffButtons);
        resetGroup(actionButtons);
        resetLocationButtons();

        if (pendingHighlights.isEmpty()) return;

        // Build maps: action -> list of room colors, staff -> list of room colors
        Map<String, List<Integer>> actionConflicts = new HashMap<>();
        Map<String, List<Integer>> staffConflicts = new HashMap<>();

        for (Map.Entry<String, String[]> entry : pendingHighlights.entrySet()) {
            String pendingMsgId = entry.getKey();
            String[] h = entry.getValue();
            String op = h[0], action = h[1], staff = h[2];
            int roomColor;
            try { roomColor = Integer.parseInt(h[3]); }
            catch (NumberFormatException e) { roomColor = Color.parseColor(h[3]); }

            // Op button: highlight + show "✓ Op#" — tap to acknowledge
            for (Button btn : locationButtons) {
                if (btn.getText().toString().equals(op) || btn.getText().toString().equals(op + " ✓")) {
                    // Fill with room color, faint white outline, small OK label below op name
                    GradientDrawable opBg = new GradientDrawable();
                    opBg.setCornerRadius(dp(6));
                    opBg.setColor(roomColor);
                    opBg.setStroke(dp(1), Color.argb(80, 255, 255, 255)); // faint white outline
                    btn.setBackground(opBg);
                    btn.setText(op + " ✓");
                    btn.setTextColor(Color.WHITE);
                    btn.setTypeface(Typeface.DEFAULT_BOLD);
                    final String ackId = pendingMsgId;
                    btn.setOnClickListener(v -> {
                        acknowledgeCard(ackId);
                        broadcastMessage("ACK|" + ackId);
                    });
                }
            }

            // Collect action conflicts
            if (!action.isEmpty()) {
                if (!actionConflicts.containsKey(action)) actionConflicts.put(action, new ArrayList<>());
                Integer rc = roomColor;
                if (!actionConflicts.get(action).contains(rc)) actionConflicts.get(action).add(rc);
            }
            // Collect staff conflicts
            if (!staff.isEmpty()) {
                if (!staffConflicts.containsKey(staff)) staffConflicts.put(staff, new ArrayList<>());
                Integer rc = roomColor;
                if (!staffConflicts.get(staff).contains(rc)) staffConflicts.get(staff).add(rc);
            }
        }

        // Apply action highlights: single color = solid border; multiple = start flash
        for (Map.Entry<String, List<Integer>> e : actionConflicts.entrySet()) {
            String action = e.getKey();
            List<Integer> colors = e.getValue();
            for (Button btn : actionButtons) {
                if (btn.getText().toString().equals(action)) {
                    if (colors.size() == 1) {
                        setButtonAppearanceHighlighted(btn, colors.get(0));
                    } else {
                        // Multiple rooms need same action — flash between their colors
                        startActionFlash(btn, colors);
                    }
                }
            }
        }

        // Apply staff highlights: same logic
        for (Map.Entry<String, List<Integer>> e : staffConflicts.entrySet()) {
            String staff = e.getKey();
            List<Integer> colors = e.getValue();
            for (Button btn : staffButtons) {
                if (btn.getText().toString().equals(staff)) {
                    if (colors.size() == 1) {
                        setButtonAppearanceHighlighted(btn, colors.get(0));
                    } else {
                        startActionFlash(btn, colors);
                    }
                }
            }
        }
    }

    private void startActionFlash(Button btn, List<Integer> colors) {
        flashIndices.put(btn, 0);
        Runnable r = new Runnable() {
            @Override public void run() {
                if (pendingHighlights.isEmpty()) return;
                int idx = flashIndices.containsKey(btn) ? flashIndices.get(btn) : 0;
                int color = colors.get(idx % colors.size());
                flashIndices.put(btn, idx + 1);
                setButtonAppearanceHighlighted(btn, color);
                handler.postDelayed(this, 600);
            }
        };
        flashRunnables.put(btn, r);
        handler.post(r);
    }

    private void addLogMessage(String message, String msgId, String extra) {
        String time = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date());
        boolean isSystem = message.equals("System");

        // Determine row color from room
        int rowColor = Color.parseColor("#1A2A3A");
        for (Map.Entry<String, Integer> e : ROOM_COLORS.entrySet()) {
            if (message.contains(e.getKey())) { rowColor = e.getValue(); break; }
        }
        final int finalRowColor = rowColor;
        final String finalMessage = message;
        final String finalTime = time;
        final String finalMsgId = msgId;

        handler.post(() -> {
            if (messageLog.getChildCount() > 30)
                messageLog.removeViewAt(messageLog.getChildCount() - 1);

            // Full-width colored row, same compact size
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, dp(1), 0, dp(1));
            row.setLayoutParams(rowParams);
            // Full-width room color background (or dark for system)
            row.setBackgroundColor(isSystem ? Color.parseColor("#0d1f3c") : finalRowColor);
            row.setPadding(dp(8), dp(3), dp(4), dp(3));

            TextView tv = new TextView(this);
            tv.setText(finalTime + "  " + finalMessage);
            tv.setTextColor(isSystem ? Color.parseColor("#607D8B") : Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(isSystem ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(tvParams);
            row.addView(tv);

            // Register for ACK (row removal)
            if (!isSystem && !finalMsgId.isEmpty()) {
                activeCards.put(finalMsgId, row);
            }

            messageLog.addView(row, 0);
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
                        String ackId = received.substring(4);
                        handler.post(() -> acknowledgeCard(ackId));
                    } else if (received.startsWith("MSG|")) {
                        // Format: MSG|id|displayText|op|action|staff
                        String[] parts = received.split("\\|", 6);
                        String msgId  = parts.length > 1 ? parts[1] : "";
                        String message = parts.length > 2 ? parts[2] : "";
                        String op     = parts.length > 3 ? parts[3] : "";
                        String action = parts.length > 4 ? parts[4] : "";
                        String staff  = parts.length > 5 ? parts[5] : "";
                        final String fId = msgId, fMsg = message, fOp = op, fAction = action, fStaff = staff;
                        handler.post(() -> {
                            if (activeCards.containsKey(fId)) return; // already added locally
                            addLogMessage(fMsg, fId, "");
                            applyPendingHighlight(fId, fOp, fAction, fStaff);
                            playBuzzer();
                        });
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
