package com.example.redbutton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java protocol and state logic for the Oak Tree Buzzer app.
 * Extracted from MainActivity so it can be unit-tested without Android framework.
 *
 * Covers:
 * - MSG/ACK packet parsing
 * - Duplicate-guard (ignore re-delivered messages)
 * - Pending highlight tracking
 * - ACK clearing (single + reset-all)
 * - Multi-op conflict detection (same action/staff needed by multiple ops)
 * - Message ID generation uniqueness
 * - Room color lookup
 */
public class BuzzerProtocol {

    // ── Wire protocol ────────────────────────────────────────────────────────

    public static final String MULTICAST_GROUP = "239.255.42.1";
    public static final int PORT = 9876;

    /** Packet kinds returned by {@link #parsePacket}. */
    public enum PacketKind { MSG, ACK, UNKNOWN }

    /** Parsed wire packet (immutable value object). */
    public static class Packet {
        public final PacketKind kind;
        public final String msgId;        // non-null for MSG and ACK
        public final String displayText;  // non-null for MSG
        public final String op;           // non-null for MSG
        public final String action;       // non-null for MSG
        public final String staff;        // non-null for MSG

        // MSG constructor
        public Packet(String msgId, String displayText, String op, String action, String staff) {
            this.kind = PacketKind.MSG;
            this.msgId = msgId;
            this.displayText = displayText;
            this.op = op;
            this.action = action;
            this.staff = staff;
        }

        // ACK constructor
        public Packet(String msgId) {
            this.kind = PacketKind.ACK;
            this.msgId = msgId;
            this.displayText = null;
            this.op = null;
            this.action = null;
            this.staff = null;
        }

        // UNKNOWN constructor
        public Packet() {
            this.kind = PacketKind.UNKNOWN;
            this.msgId = null;
            this.displayText = null;
            this.op = null;
            this.action = null;
            this.staff = null;
        }
    }

    /**
     * Parse a raw UDP payload string into a typed {@link Packet}.
     *
     * MSG format: {@code MSG|<id>|<displayText>|<op>|<action>|<staff>}
     * ACK format: {@code ACK|<id>}
     */
    public static Packet parsePacket(String raw) {
        if (raw == null || raw.isEmpty()) return new Packet();

        if (raw.startsWith("ACK|")) {
            String id = raw.substring(4).trim();
            if (id.isEmpty()) return new Packet();
            return new Packet(id);
        }

        if (raw.startsWith("MSG|")) {
            // Split with limit 6 to avoid splitting embedded pipes in displayText edge cases
            String[] parts = raw.split("\\|", 6);
            if (parts.length < 6) return new Packet();
            String id          = parts[1];
            String displayText = parts[2];
            String op          = parts[3];
            String action      = parts[4];
            String staff       = parts[5];
            if (id.isEmpty()) return new Packet();
            return new Packet(id, displayText, op, action, staff);
        }

        return new Packet();
    }

    /**
     * Build a MSG wire string from components.
     * Format: {@code MSG|<id>|<displayText>|<op>|<action>|<staff>}
     */
    public static String buildMsgPacket(String msgId, String displayText,
                                         String op, String action, String staff) {
        return "MSG|" + msgId + "|" + displayText + "|" + op + "|" + action + "|" + staff;
    }

    /** Build an ACK wire string: {@code ACK|<id>} */
    public static String buildAckPacket(String msgId) {
        return "ACK|" + msgId;
    }

    // ── Room colors ──────────────────────────────────────────────────────────

    /**
     * Canonical room → color mapping (ARGB int).
     * Colours match ROOM_COLORS in MainActivity.
     */
    public static final Map<String, Integer> ROOM_COLORS = new LinkedHashMap<>();

    static {
        ROOM_COLORS.put("Op1",  parseColor("#D7CCC8")); // Beige
        ROOM_COLORS.put("Op2",  parseColor("#2E7D32")); // Dark Green
        ROOM_COLORS.put("Op3",  parseColor("#F4511E")); // Orange
        ROOM_COLORS.put("Op4",  parseColor("#E91E63")); // Pink
        ROOM_COLORS.put("Op5",  parseColor("#A1672A")); // Honey Brown
        ROOM_COLORS.put("Op6",  parseColor("#4E342E")); // Dark Brown
        ROOM_COLORS.put("Op7",  parseColor("#8BC34A")); // Lime Green
        ROOM_COLORS.put("Op8",  parseColor("#212121")); // Black
        ROOM_COLORS.put("Op9",  parseColor("#E53935")); // Red
        ROOM_COLORS.put("Op10", parseColor("#7B1FA2")); // Purple
    }

    /** Minimal #RRGGBB parser (no android.graphics.Color dependency). */
    public static int parseColor(String hex) {
        if (hex.startsWith("#")) hex = hex.substring(1);
        long color = Long.parseLong(hex, 16);
        if (hex.length() == 6) color |= 0xFF000000L; // add alpha
        return (int) color;
    }

    public static int roomColor(String op) {
        Integer c = ROOM_COLORS.get(op);
        return c != null ? c : parseColor("#1E88E5"); // default blue
    }

    // ── State machine ────────────────────────────────────────────────────────

    /**
     * Represents the shared mutable state of a single tablet's buzzer session.
     * All methods are single-threaded (caller must dispatch to UI thread).
     */
    public static class State {

        /** msgId → [op, action, staff, roomColorInt(as string)] */
        public final Map<String, String[]> pendingHighlights = new HashMap<>();

        /**
         * Set of message IDs currently shown as active cards in the log.
         * Used for duplicate-guard.
         */
        public final java.util.Set<String> activeCardIds = new java.util.LinkedHashSet<>();

        /**
         * Log of display-text strings added (most-recent first), capped at 30.
         * Simulates the message log visible to the user.
         */
        public final List<String> logMessages = new ArrayList<>();

        // ── Composing selection ──────────────────────────────────────────────
        public String selectedOp     = null;
        public String selectedAction = null;
        public String selectedStaff  = null;
        public int    currentRoomColor = parseColor("#1E88E5");

        // ── Outbound queue (messages the state machine wants to send) ────────
        public final List<String> outboundPackets = new ArrayList<>();

        // ────────────────────────────────────────────────────────────────────

        /**
         * Receive and process an inbound wire packet.
         * Returns true if the packet caused a visible state change.
         */
        public boolean receive(String raw) {
            Packet p = parsePacket(raw);
            switch (p.kind) {
                case MSG: return handleMsg(p);
                case ACK: return handleAck(p.msgId);
                default:  return false;
            }
        }

        private boolean handleMsg(Packet p) {
            if (activeCardIds.contains(p.msgId)) return false; // duplicate guard
            activeCardIds.add(p.msgId);
            addLog(p.displayText);
            applyPendingHighlight(p.msgId, p.op, p.action, p.staff);
            return true;
        }

        private boolean handleAck(String msgId) {
            if (!activeCardIds.contains(msgId) && !pendingHighlights.containsKey(msgId)) return false;
            activeCardIds.remove(msgId);
            pendingHighlights.remove(msgId);
            return true;
        }

        /**
         * Send the currently composed message.
         * Adds to local state immediately (sender doesn't receive own multicast).
         * Enqueues MSG packet in {@link #outboundPackets}.
         * @param msgId caller-provided ID (use {@link #generateMsgId()} in production)
         */
        public boolean send(String msgId) {
            if (selectedOp == null && selectedAction == null && selectedStaff == null) return false;

            String op     = selectedOp     != null ? selectedOp     : "";
            String action = selectedAction != null ? selectedAction : "";
            String staff  = selectedStaff  != null ? selectedStaff  : "";
            String display = buildDisplayText(op, action, staff);

            // Apply locally immediately
            activeCardIds.add(msgId);
            addLog(display);
            applyPendingHighlight(msgId, op, action, staff);

            // Enqueue wire packet
            outboundPackets.add(buildMsgPacket(msgId, display, op, action, staff));

            // Clear composing state
            selectedOp     = null;
            selectedAction = null;
            selectedStaff  = null;
            currentRoomColor = parseColor("#1E88E5");
            return true;
        }

        /**
         * Acknowledge a single message: remove from state, enqueue ACK broadcast.
         */
        public void acknowledge(String msgId) {
            activeCardIds.remove(msgId);
            pendingHighlights.remove(msgId);
            outboundPackets.add(buildAckPacket(msgId));
        }

        /**
         * Reset: acknowledge ALL pending messages (broadcasts ACK for each), clear all state.
         */
        public void resetAll() {
            for (String id : new ArrayList<>(activeCardIds)) {
                outboundPackets.add(buildAckPacket(id));
            }
            activeCardIds.clear();
            pendingHighlights.clear();
            selectedOp     = null;
            selectedAction = null;
            selectedStaff  = null;
            currentRoomColor = parseColor("#1E88E5");
        }

        /**
         * Returns a map of action/staff button labels to the list of room colors
         * that are pending for that button. Size > 1 means multi-op conflict (flash).
         */
        public Map<String, List<Integer>> getActionConflicts() {
            return collectConflicts(1); // index 1 = action field
        }

        public Map<String, List<Integer>> getStaffConflicts() {
            return collectConflicts(2); // index 2 = staff field
        }

        private Map<String, List<Integer>> collectConflicts(int fieldIdx) {
            Map<String, List<Integer>> result = new HashMap<>();
            for (String[] h : pendingHighlights.values()) {
                String label = h[fieldIdx];
                if (label == null || label.isEmpty()) continue;
                int color;
                try { color = Integer.parseInt(h[3]); }
                catch (NumberFormatException e) { color = parseColor(h[3]); }
                result.computeIfAbsent(label, k -> new ArrayList<>());
                if (!result.get(label).contains(color)) result.get(label).add(color);
            }
            return result;
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private void applyPendingHighlight(String msgId, String op, String action, String staff) {
            int color = roomColor(op);
            pendingHighlights.put(msgId, new String[]{op, action, staff, String.valueOf(color)});
        }

        private void addLog(String text) {
            logMessages.add(0, text);
            if (logMessages.size() > 30) logMessages.remove(logMessages.size() - 1);
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /**
     * Build a human-readable display string from the three selection components.
     * Format: {@code Op3 — Tx — Hyg2}  (only non-empty parts joined with " — ")
     */
    public static String buildDisplayText(String op, String action, String staff) {
        StringBuilder sb = new StringBuilder();
        append(sb, op);
        append(sb, action);
        append(sb, staff);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(" — ");
        sb.append(part);
    }

    /**
     * Generate a unique message ID based on current wall-clock time.
     * Two calls separated by at least 1 ms are guaranteed unique.
     */
    public static String generateMsgId() {
        return String.valueOf(System.currentTimeMillis());
    }
}
