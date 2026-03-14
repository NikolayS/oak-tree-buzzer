package com.example.redbutton;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link BuzzerProtocol}.
 *
 * Covers every regression scenario encountered during Oak Tree Buzzer development:
 *  1. MSG packet parsing — valid / incomplete / malformed
 *  2. ACK packet parsing
 *  3. Display text builder
 *  4. Room color lookup
 *  5. Duplicate guard (same msgId ignored on re-delivery)
 *  6. ACK clears card on receiving tablet
 *  7. Reset-all broadcasts ACK for every pending card then clears state
 *  8. Multi-op conflict detection (same action needed by two ops → flash colors)
 *  9. Sender-side local apply (send() adds to own state without waiting for loopback)
 * 10. Message log cap at 30 entries
 * 11. Send with no selection → no-op
 * 12. Timestamp-based msgId uniqueness
 * 13. Op button pending state tracking
 * 14. Partial MSG (missing fields) → UNKNOWN packet
 */
public class BuzzerProtocolTest {

    private BuzzerProtocol.State state;

    @Before
    public void setUp() {
        state = new BuzzerProtocol.State();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. MSG packet parsing
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testParseMsgPacket_valid() {
        String raw = "MSG|1700000001234|Op3 — Tx — Hyg2|Op3|Tx|Hyg2";
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket(raw);

        assertEquals(BuzzerProtocol.PacketKind.MSG, p.kind);
        assertEquals("1700000001234", p.msgId);
        assertEquals("Op3 — Tx — Hyg2", p.displayText);
        assertEquals("Op3", p.op);
        assertEquals("Tx", p.action);
        assertEquals("Hyg2", p.staff);
    }

    @Test
    public void testParseMsgPacket_emptyOptionalFields() {
        // Op only — no action, no staff
        String raw = "MSG|111|Op5||";
        // Only 5 parts — should parse as UNKNOWN (missing 6th field)
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket(raw);
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, p.kind);
    }

    @Test
    public void testParseMsgPacket_fullSixParts_emptyOptional() {
        String raw = "MSG|222|Op5|Op5||";
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket(raw);
        assertEquals(BuzzerProtocol.PacketKind.MSG, p.kind);
        assertEquals("Op5", p.op);
        assertEquals("", p.action);
        assertEquals("", p.staff);
    }

    @Test
    public void testParseMsgPacket_tooFewParts() {
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket("MSG|only-two");
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, p.kind);
    }

    @Test
    public void testParseMsgPacket_nullInput() {
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, BuzzerProtocol.parsePacket(null).kind);
    }

    @Test
    public void testParseMsgPacket_emptyInput() {
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, BuzzerProtocol.parsePacket("").kind);
    }

    @Test
    public void testParseMsgPacket_splitLimit6_preservesPipesInDisplayText() {
        // displayText itself contains " — " which uses em-dash, but edge: test real pipe in text
        String raw = "MSG|333|A|B|C|D|extra-pipe-should-be-in-staff";
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket(raw);
        assertEquals(BuzzerProtocol.PacketKind.MSG, p.kind);
        // With limit 6, everything from part[5] onward is in staff
        assertEquals("D|extra-pipe-should-be-in-staff", p.staff);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. ACK packet parsing
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testParseAckPacket_valid() {
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket("ACK|1700000009999");
        assertEquals(BuzzerProtocol.PacketKind.ACK, p.kind);
        assertEquals("1700000009999", p.msgId);
    }

    @Test
    public void testParseAckPacket_emptyId() {
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket("ACK|");
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, p.kind);
    }

    @Test
    public void testParseUnknownPacket() {
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, BuzzerProtocol.parsePacket("PING|hello").kind);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. Display text builder
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testBuildDisplayText_allFields() {
        assertEquals("Op4 — Exam — Dr. Riad",
            BuzzerProtocol.buildDisplayText("Op4", "Exam", "Dr. Riad"));
    }

    @Test
    public void testBuildDisplayText_opOnly() {
        assertEquals("Op2", BuzzerProtocol.buildDisplayText("Op2", "", ""));
    }

    @Test
    public void testBuildDisplayText_opAndAction() {
        assertEquals("Op9 — Help", BuzzerProtocol.buildDisplayText("Op9", "Help", ""));
    }

    @Test
    public void testBuildDisplayText_actionAndStaffOnly() {
        assertEquals("Tx — Amanda", BuzzerProtocol.buildDisplayText("", "Tx", "Amanda"));
    }

    @Test
    public void testBuildDisplayText_allEmpty() {
        assertEquals("", BuzzerProtocol.buildDisplayText("", "", ""));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. Room color lookup
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testRoomColor_knownOp() {
        // Op3 = #F4511E
        int expected = BuzzerProtocol.parseColor("#F4511E");
        assertEquals(expected, BuzzerProtocol.roomColor("Op3"));
    }

    @Test
    public void testRoomColor_allTenOpsPresent() {
        String[] ops = {"Op1","Op2","Op3","Op4","Op5","Op6","Op7","Op8","Op9","Op10"};
        for (String op : ops) {
            assertTrue("Missing color for " + op, BuzzerProtocol.ROOM_COLORS.containsKey(op));
        }
    }

    @Test
    public void testRoomColor_unknownOp_returnsDefault() {
        int defaultBlue = BuzzerProtocol.parseColor("#1E88E5");
        assertEquals(defaultBlue, BuzzerProtocol.roomColor("Op99"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. Duplicate guard — same msgId ignored on second delivery
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testDuplicateGuard_secondMessageIgnored() {
        String raw = "MSG|dup-001|Op1 — Tx|Op1|Tx|";
        boolean first  = state.receive(raw);
        boolean second = state.receive(raw);

        assertTrue("First delivery should be accepted", first);
        assertFalse("Second delivery should be ignored (duplicate)", second);
        assertEquals(1, state.activeCardIds.size());
        assertEquals(1, state.logMessages.size());
    }

    @Test
    public void testDuplicateGuard_differentIdsAccepted() {
        state.receive("MSG|id-A|Op1 — Tx|Op1|Tx|");
        state.receive("MSG|id-B|Op2 — Exam|Op2|Exam|");
        assertEquals(2, state.activeCardIds.size());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. ACK clears card on receiving tablet
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testAck_clearsCard() {
        state.receive("MSG|ack-001|Op3 — Help|Op3|Help|");
        assertTrue(state.activeCardIds.contains("ack-001"));
        assertTrue(state.pendingHighlights.containsKey("ack-001"));

        boolean changed = state.receive("ACK|ack-001");
        assertTrue(changed);
        assertFalse(state.activeCardIds.contains("ack-001"));
        assertFalse(state.pendingHighlights.containsKey("ack-001"));
    }

    @Test
    public void testAck_unknownId_noChange() {
        boolean changed = state.receive("ACK|nonexistent-id");
        assertFalse(changed);
    }

    @Test
    public void testAck_onlyRemovesTargetCard_othersRemain() {
        state.receive("MSG|m1|Op1|Op1||");
        state.receive("MSG|m2|Op2|Op2||");
        state.receive("ACK|m1");

        assertFalse(state.activeCardIds.contains("m1"));
        assertTrue(state.activeCardIds.contains("m2"));
        assertEquals(1, state.pendingHighlights.size());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 7. Reset-all: broadcasts ACK for every pending card, then clears state
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testResetAll_clearsAllState() {
        state.receive("MSG|r1|Op1|Op1|Tx|");
        state.receive("MSG|r2|Op2|Op2|Exam|");
        state.receive("MSG|r3|Op3|Op3||Randi");

        state.resetAll();

        assertTrue(state.activeCardIds.isEmpty());
        assertTrue(state.pendingHighlights.isEmpty());
        assertNull(state.selectedOp);
        assertNull(state.selectedAction);
        assertNull(state.selectedStaff);
    }

    @Test
    public void testResetAll_broadcastsAckForEachPending() {
        state.receive("MSG|r1|Op1|Op1||");
        state.receive("MSG|r2|Op2|Op2||");
        state.outboundPackets.clear(); // ignore any prior outbound

        state.resetAll();

        // Should have broadcast exactly 2 ACK packets
        assertEquals(2, state.outboundPackets.size());
        assertTrue(state.outboundPackets.contains("ACK|r1"));
        assertTrue(state.outboundPackets.contains("ACK|r2"));
    }

    @Test
    public void testResetAll_emptyState_noAcks() {
        state.resetAll();
        assertTrue(state.outboundPackets.isEmpty());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 8. Multi-op conflict: same action needed by two ops → flash colors
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testActionConflict_singleOp_oneColor() {
        state.receive("MSG|c1|Op3 — Tx|Op3|Tx|");
        Map<String, List<Integer>> conflicts = state.getActionConflicts();

        assertTrue(conflicts.containsKey("Tx"));
        assertEquals(1, conflicts.get("Tx").size());
        assertEquals((Integer) BuzzerProtocol.roomColor("Op3"), conflicts.get("Tx").get(0));
    }

    @Test
    public void testActionConflict_twoOps_twoColors() {
        state.receive("MSG|c1|Op3 — Tx|Op3|Tx|");
        state.receive("MSG|c2|Op5 — Tx|Op5|Tx|");

        Map<String, List<Integer>> conflicts = state.getActionConflicts();
        assertTrue(conflicts.containsKey("Tx"));
        assertEquals(2, conflicts.get("Tx").size());
        assertTrue(conflicts.get("Tx").contains(BuzzerProtocol.roomColor("Op3")));
        assertTrue(conflicts.get("Tx").contains(BuzzerProtocol.roomColor("Op5")));
    }

    @Test
    public void testStaffConflict_twoOps_twoColors() {
        state.receive("MSG|s1|Op1 — Exam — Randi|Op1|Exam|Randi");
        state.receive("MSG|s2|Op9 — Tx — Randi|Op9|Tx|Randi");

        Map<String, List<Integer>> conflicts = state.getStaffConflicts();
        assertTrue(conflicts.containsKey("Randi"));
        assertEquals(2, conflicts.get("Randi").size());
    }

    @Test
    public void testActionConflict_afterAck_removesColor() {
        state.receive("MSG|c1|Op3 — Tx|Op3|Tx|");
        state.receive("MSG|c2|Op5 — Tx|Op5|Tx|");
        state.receive("ACK|c1"); // Op3 acknowledged

        Map<String, List<Integer>> conflicts = state.getActionConflicts();
        assertEquals(1, conflicts.get("Tx").size());
        assertEquals((Integer) BuzzerProtocol.roomColor("Op5"), conflicts.get("Tx").get(0));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 9. Sender-side local apply (send() adds to own state immediately)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testSend_appliesLocally_beforeBroadcast() {
        state.selectedOp     = "Op4";
        state.selectedAction = "Tx";
        state.selectedStaff  = "Lindsay";
        state.currentRoomColor = BuzzerProtocol.roomColor("Op4");

        boolean sent = state.send("send-001");

        assertTrue(sent);
        assertTrue(state.activeCardIds.contains("send-001"));
        assertTrue(state.pendingHighlights.containsKey("send-001"));
        assertEquals(1, state.logMessages.size());
    }

    @Test
    public void testSend_enqueusMsgPacket() {
        state.selectedOp = "Op2";
        state.send("snd-002");

        assertEquals(1, state.outboundPackets.size());
        assertTrue(state.outboundPackets.get(0).startsWith("MSG|snd-002|"));
    }

    @Test
    public void testSend_clearsComposingSelection() {
        state.selectedOp     = "Op1";
        state.selectedAction = "Help";
        state.selectedStaff  = "Randi";
        state.send("snd-003");

        assertNull(state.selectedOp);
        assertNull(state.selectedAction);
        assertNull(state.selectedStaff);
    }

    @Test
    public void testSend_nothingSelected_returnsFalse() {
        boolean sent = state.send("snd-empty");
        assertFalse(sent);
        assertTrue(state.activeCardIds.isEmpty());
        assertTrue(state.outboundPackets.isEmpty());
    }

    @Test
    public void testSend_senderDuplicateGuard_loopbackIgnored() {
        // After send(), the same msgId arriving via multicast loopback should be ignored
        state.selectedOp = "Op7";
        state.send("loopback-id");
        state.outboundPackets.clear();

        boolean loopback = state.receive("MSG|loopback-id|Op7|Op7||");
        assertFalse("Loopback should be ignored by duplicate guard", loopback);
        assertEquals(1, state.activeCardIds.size());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 10. Message log cap at 30 entries
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testLogCap_at30Entries() {
        for (int i = 0; i < 35; i++) {
            state.receive("MSG|cap-" + i + "|Msg " + i + "|Op1||");
        }
        assertEquals(30, state.logMessages.size());
    }

    @Test
    public void testLogOrder_mostRecentFirst() {
        state.receive("MSG|ord-1|First|Op1||");
        state.receive("MSG|ord-2|Second|Op1||");
        state.receive("MSG|ord-3|Third|Op1||");

        assertEquals("Third",  state.logMessages.get(0));
        assertEquals("Second", state.logMessages.get(1));
        assertEquals("First",  state.logMessages.get(2));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 11. Send with partial selection (only op selected)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testSend_opOnly_isValid() {
        state.selectedOp = "Op6";
        boolean sent = state.send("partial-001");
        assertTrue(sent);
        assertTrue(state.pendingHighlights.containsKey("partial-001"));
    }

    @Test
    public void testSend_staffOnly_isValid() {
        state.selectedStaff = "Amanda";
        boolean sent = state.send("partial-002");
        assertTrue(sent);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 12. Timestamp-based msgId uniqueness
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testMsgIdUniqueness() throws InterruptedException {
        String id1 = BuzzerProtocol.generateMsgId();
        Thread.sleep(2); // ensure clock advances
        String id2 = BuzzerProtocol.generateMsgId();
        assertNotEquals("Two consecutive msgIds must be unique", id1, id2);
    }

    @Test
    public void testMsgIdIsNumeric() {
        String id = BuzzerProtocol.generateMsgId();
        assertTrue("msgId should be all digits", id.matches("\\d+"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 13. Op button pending state tracked in pendingHighlights
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testPendingHighlights_containsOpField() {
        state.receive("MSG|ph-001|Op9 — Exam|Op9|Exam|");
        String[] h = state.pendingHighlights.get("ph-001");
        assertNotNull(h);
        assertEquals("Op9", h[0]);   // op
        assertEquals("Exam", h[1]);  // action
        assertEquals("", h[2]);      // staff
        // h[3] = room color as int string
        int expectedColor = BuzzerProtocol.roomColor("Op9");
        assertEquals(String.valueOf(expectedColor), h[3]);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 14. Partial MSG (too few fields) → UNKNOWN
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testPartialMsg_fiveFields_isUnknown() {
        // Missing 6th field (staff)
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket("MSG|id|text|op|action");
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, p.kind);
    }

    @Test
    public void testPartialMsg_noIdAfterPrefix_isUnknown() {
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket("MSG|");
        assertEquals(BuzzerProtocol.PacketKind.UNKNOWN, p.kind);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 15. Acknowledge via state.acknowledge() enqueues ACK packet
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testAcknowledge_enqueusBroadcast() {
        state.receive("MSG|ack-local|Op4 — Tx|Op4|Tx|");
        state.outboundPackets.clear();

        state.acknowledge("ack-local");

        assertEquals(1, state.outboundPackets.size());
        assertEquals("ACK|ack-local", state.outboundPackets.get(0));
        assertFalse(state.activeCardIds.contains("ack-local"));
        assertFalse(state.pendingHighlights.containsKey("ack-local"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 16. Packet builder round-trip
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testBuildAndParseMsg_roundTrip() {
        String raw = BuzzerProtocol.buildMsgPacket("999", "Op2 — Exam", "Op2", "Exam", "Dr. Riad");
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket(raw);

        assertEquals(BuzzerProtocol.PacketKind.MSG, p.kind);
        assertEquals("999", p.msgId);
        assertEquals("Op2 — Exam", p.displayText);
        assertEquals("Op2", p.op);
        assertEquals("Exam", p.action);
        assertEquals("Dr. Riad", p.staff);
    }

    @Test
    public void testBuildAndParseAck_roundTrip() {
        String raw = BuzzerProtocol.buildAckPacket("42");
        BuzzerProtocol.Packet p = BuzzerProtocol.parsePacket(raw);
        assertEquals(BuzzerProtocol.PacketKind.ACK, p.kind);
        assertEquals("42", p.msgId);
    }
}
