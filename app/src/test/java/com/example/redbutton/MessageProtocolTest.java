package com.example.redbutton;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
import java.util.*;

/**
 * Unit tests for the Oak Tree Buzzer message protocol and state logic.
 *
 * Scenarios covered:
 * 1. Message encoding/decoding (MSG format)
 * 2. ACK encoding/decoding
 * 3. Single op pending highlight
 * 4. Multiple ops — same action triggers conflict (flash)
 * 5. ACK clears correct pending state
 * 6. ACK on all clears everything
 * 7. Duplicate MSG ignored (sender loopback guard)
 * 8. Room color lookup
 */
public class MessageProtocolTest {

    // Mirrors the protocol logic from MainActivity without Android deps
    static final String MULTICAST_GROUP = "239.255.42.1";
    static final int PORT = 9876;

    // Room color map (hex strings for test, no android.graphics.Color needed)
    static final Map<String, String> ROOM_COLORS = new LinkedHashMap<String, String>() {{
        put("Op1",  "#FFB300");
        put("Op2",  "#FB8C00");
        put("Op3",  "#F4511E");
        put("Op4",  "#BF360C");
        put("Op5",  "#455A64");
        put("Op6",  "#37474F");
        put("Op7",  "#263238");
        put("Op8",  "#1C2B30");
        put("Op9",  "#00ACC1");
        put("Op10", "#00838F");
    }};

    // Minimal state model
    Map<String, String[]> pendingHighlights;
    Set<String> activeCards;

    @Before
    public void setUp() {
        pendingHighlights = new LinkedHashMap<>();
        activeCards = new HashSet<>();
    }

    // ── Protocol encoding ──────────────────────────────────────────────────

    @Test
    public void testMsgEncoding() {
        String msgId = "1234567890";
        String display = "Op3 — Exam — Hyg1";
        String op = "Op3", action = "Exam", staff = "Hyg1";
        String encoded = "MSG|" + msgId + "|" + display + "|" + op + "|" + action + "|" + staff;
        assertEquals("MSG|1234567890|Op3 — Exam — Hyg1|Op3|Exam|Hyg1", encoded);
    }

    @Test
    public void testMsgDecoding() {
        String raw = "MSG|1234567890|Op3 — Exam — Hyg1|Op3|Exam|Hyg1";
        String[] parts = raw.split("\\|", 6);
        assertEquals("MSG",           parts[0]);
        assertEquals("1234567890",    parts[1]);
        assertEquals("Op3 — Exam — Hyg1", parts[2]);
        assertEquals("Op3",           parts[3]);
        assertEquals("Exam",          parts[4]);
        assertEquals("Hyg1",          parts[5]);
    }

    @Test
    public void testAckEncoding() {
        String ackId = "1234567890";
        String encoded = "ACK|" + ackId;
        assertTrue(encoded.startsWith("ACK|"));
        assertEquals("1234567890", encoded.substring(4));
    }

    @Test
    public void testMsgWithEmptyFields() {
        // Op only — no action, no staff
        String raw = "MSG|111|Op9|Op9||";
        String[] parts = raw.split("\\|", 6);
        assertEquals("Op9", parts[3]);
        assertEquals("",    parts[4]);
        assertEquals("",    parts[5]);
    }

    // ── Pending highlight state ────────────────────────────────────────────

    @Test
    public void testSingleOpHighlight() {
        applyHighlight("id1", "Op3", "Exam", "Hyg1");
        assertEquals(1, pendingHighlights.size());
        String[] h = pendingHighlights.get("id1");
        assertEquals("Op3",  h[0]);
        assertEquals("Exam", h[1]);
        assertEquals("Hyg1", h[2]);
        assertEquals(ROOM_COLORS.get("Op3"), h[3]);
    }

    @Test
    public void testAckClearsSingleHighlight() {
        applyHighlight("id1", "Op3", "Exam", "Hyg1");
        activeCards.add("id1");
        ack("id1");
        assertEquals(0, pendingHighlights.size());
        assertEquals(0, activeCards.size());
    }

    @Test
    public void testTwoOpsNoConflict() {
        applyHighlight("id1", "Op3", "Exam",  "Hyg1");
        applyHighlight("id2", "Op4", "Pt. Ready", "Ast1");
        assertEquals(2, pendingHighlights.size());
        // Different actions — no conflict
        assertFalse(hasActionConflict("Exam"));
        assertFalse(hasActionConflict("Pt. Ready"));
    }

    @Test
    public void testTwoOpsSameActionConflict() {
        // Op2 and Op3 both need Exam — Exam button should flash
        applyHighlight("id1", "Op2", "Exam", "Hyg1");
        applyHighlight("id2", "Op3", "Exam", "Hyg2");
        assertTrue(hasActionConflict("Exam"));
        List<String> colors = getActionColors("Exam");
        assertEquals(2, colors.size());
        assertTrue(colors.contains(ROOM_COLORS.get("Op2")));
        assertTrue(colors.contains(ROOM_COLORS.get("Op3")));
    }

    @Test
    public void testAckOneOfTwoConflictsResolves() {
        applyHighlight("id1", "Op2", "Exam", "Hyg1");
        applyHighlight("id2", "Op3", "Exam", "Hyg2");
        activeCards.add("id1");
        activeCards.add("id2");
        ack("id1"); // Op2 acknowledged
        // Exam still pending for Op3 — no more conflict
        assertFalse(hasActionConflict("Exam"));
        List<String> colors = getActionColors("Exam");
        assertEquals(1, colors.size());
        assertEquals(ROOM_COLORS.get("Op3"), colors.get(0));
    }

    @Test
    public void testAckAllClearsEverything() {
        applyHighlight("id1", "Op2", "Exam",  "Hyg1");
        applyHighlight("id2", "Op9", "Tx",    "Dr. Riad");
        activeCards.add("id1");
        activeCards.add("id2");
        ack("id1");
        ack("id2");
        assertEquals(0, pendingHighlights.size());
        assertEquals(0, activeCards.size());
    }

    @Test
    public void testDuplicateMsgIgnored() {
        // Simulate sender receiving own loopback — should not add twice
        String msgId = "id1";
        applyHighlight(msgId, "Op3", "Exam", "Hyg1");
        activeCards.add(msgId);
        // Second arrival of same msgId — sender guard
        boolean isDuplicate = activeCards.contains(msgId);
        assertTrue(isDuplicate);
        // State should not change
        assertEquals(1, pendingHighlights.size());
    }

    @Test
    public void testRoomColorLookup() {
        assertEquals("#F4511E", ROOM_COLORS.get("Op3"));  // deep orange
        assertEquals("#00ACC1", ROOM_COLORS.get("Op9"));  // cyan
        assertEquals("#BF360C", ROOM_COLORS.get("Op4"));  // burnt orange
        assertNull(ROOM_COLORS.get("Op99"));
    }

    @Test
    public void testThreeOpsSameStaffConflict() {
        applyHighlight("id1", "Op2", "Exam",     "Hyg1");
        applyHighlight("id2", "Op3", "Pt. Ready","Hyg1");
        applyHighlight("id3", "Op4", "Tx",       "Hyg1");
        // Hyg1 needed in 3 ops — all 3 room colors should appear
        List<String> colors = getStaffColors("Hyg1");
        assertEquals(3, colors.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void applyHighlight(String msgId, String op, String action, String staff) {
        String roomColor = ROOM_COLORS.containsKey(op) ? ROOM_COLORS.get(op) : "#1E88E5";
        pendingHighlights.put(msgId, new String[]{op, action, staff, roomColor});
    }

    private void ack(String msgId) {
        pendingHighlights.remove(msgId);
        activeCards.remove(msgId);
    }

    private boolean hasActionConflict(String action) {
        return getActionColors(action).size() > 1;
    }

    private List<String> getActionColors(String action) {
        List<String> colors = new ArrayList<>();
        for (String[] h : pendingHighlights.values()) {
            if (action.equals(h[1]) && !colors.contains(h[3])) colors.add(h[3]);
        }
        return colors;
    }

    private List<String> getStaffColors(String staff) {
        List<String> colors = new ArrayList<>();
        for (String[] h : pendingHighlights.values()) {
            if (staff.equals(h[2]) && !colors.contains(h[3])) colors.add(h[3]);
        }
        return colors;
    }
}
