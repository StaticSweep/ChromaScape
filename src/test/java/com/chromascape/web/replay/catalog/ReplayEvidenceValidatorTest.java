package com.chromascape.web.replay.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.catalog.ReplayEvidenceValidator.AcceptedAction;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of the ONE shared evidence interpretation used by preflight AND the compiler:
 * action-tick snapshot presence, unique stable targets, per-tick NPC validity, and option/spot-type
 * agreement at the ACTION's tick — not merely the first snapshot.
 */
class ReplayEvidenceValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private TickSnapshot snapshot(long tick, String npcsJson) throws Exception {
    TickSnapshot snap =
        new TickSnapshot(
            "sess",
            tick,
            LocalDateTime.of(2026, 1, 1, 0, 0),
            mapper.readTree(
                "{\"position\":{\"x\":1,\"y\":2,\"plane\":0},"
                    + "\"world\":{\"worldId\":330},\"collisionRevision\":\"c\"}"));
    snap.setSchemaVersion(ReplayEvidenceValidator.SUPPORTED_SNAPSHOT_SCHEMA_VERSION);
    snap.setInventory(mapper.readTree("[]"));
    snap.setEquipment(mapper.readTree("[]"));
    if (npcsJson != null) {
      snap.setNpcs(mapper.readTree(npcsJson));
    }
    return snap;
  }

  private AcceptedAction interactNpc(long tick, String stableId, String option) throws Exception {
    return new AcceptedAction(
        tick,
        0,
        "INTERACT_NPC",
        stableId,
        option == null ? null : mapper.readTree("{\"option\":\"" + option + "\"}"));
  }

  @Test
  void missingSnapshotAtTheActionTickIsAViolation() throws Exception {
    // Target exists at tick 0 but NO snapshot covers tick 5 where the action fires.
    TickSnapshot tick0 =
        snapshot(
            0L,
            "[{\"stableId\":\"spot:a:1\",\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"net\"}]");
    var violations =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(interactNpc(5L, "spot:a:1", "net")),
            ReplayEvidenceValidator.indexByTick(List.of(tick0)));

    assertFalse(violations.isEmpty());
    assertEquals(ReplayErrorCode.ACTION_TARGET_UNRESOLVED, violations.get(0).code());
    assertTrue(violations.get(0).message().contains("tick 5"));
  }

  @Test
  void targetPresentAtStartButGoneAtActionTickViolates() throws Exception {
    TickSnapshot tick0 =
        snapshot(
            0L,
            "[{\"stableId\":\"spot:a:1\",\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"net\"}]");
    TickSnapshot tick7 = snapshot(7L, "[]"); // spot moved away by the action tick
    var violations =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(interactNpc(7L, "spot:a:1", "net")),
            ReplayEvidenceValidator.indexByTick(List.of(tick0, tick7)));

    assertFalse(violations.isEmpty(), "first-snapshot-only checking is FORBIDDEN");
    assertTrue(violations.get(0).message().contains("not present at its action tick"));
  }

  @Test
  void duplicatedStableTargetAtActionTickViolates() throws Exception {
    String dup =
        "[{\"stableId\":\"spot:a:1\",\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"net\"},"
            + " {\"stableId\":\"spot:a:1\",\"npcId\":332,\"position\":{\"x\":3,\"y\":4,\"plane\":0},\"spotType\":\"bait\"}]";
    TickSnapshot tick2 = snapshot(2L, dup);
    var violations =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(interactNpc(2L, "spot:a:1", "net")),
            ReplayEvidenceValidator.indexByTick(List.of(tick2)));

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.message().contains("appears 2 times")));
  }

  @Test
  void optionMustMatchTheRecordedSpotTypeAtTheActionTick() throws Exception {
    TickSnapshot tick3 =
        snapshot(
            3L,
            "[{\"stableId\":\"spot:b:2\",\"npcId\":77,\"position\":{\"x\":9,\"y\":8,\"plane\":0},\"spotType\":\"bait\"}]");
    var violations =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(interactNpc(3L, "spot:b:2", "harpoon")),
            ReplayEvidenceValidator.indexByTick(List.of(tick3)));

    assertFalse(violations.isEmpty(), "option/spot mismatch must be caught in preflight too");
    assertEquals(ReplayErrorCode.ACTION_UNSUPPORTED, violations.get(0).code());
    assertTrue(violations.get(0).message().contains("does not match recorded spotType"));
  }

  @Test
  void unsupportedOrMissingOptionViolates() throws Exception {
    TickSnapshot tick1 =
        snapshot(
            1L,
            "[{\"stableId\":\"spot:c:3\",\"npcId\":9,\"position\":{\"x\":1,\"y\":1,\"plane\":0},\"spotType\":\"net\"}]");
    var violations =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(new AcceptedAction(1L, 0, "INTERACT_NPC", "spot:c:3", null)),
            ReplayEvidenceValidator.indexByTick(List.of(tick1)));
    assertTrue(violations.stream().anyMatch(v -> v.code() == ReplayErrorCode.ACTION_UNSUPPORTED));

    var badOption =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(interactNpc(1L, "spot:c:3", "chop")),
            ReplayEvidenceValidator.indexByTick(List.of(tick1)));
    assertTrue(badOption.stream().anyMatch(v -> v.code() == ReplayErrorCode.ACTION_UNSUPPORTED));
  }

  @Test
  void waitUntilTickRequiresOnlyASnapshotAtItsTick() throws Exception {
    var violations =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(new AcceptedAction(4L, 0, "WAIT_UNTIL_TICK", null, null)),
            ReplayEvidenceValidator.indexByTick(List.of()));
    assertFalse(violations.isEmpty());

    TickSnapshot tick4 = snapshot(4L, "[]");
    var ok =
        ReplayEvidenceValidator.validateAcceptedActions(
            List.of(new AcceptedAction(4L, 0, "WAIT_UNTIL_TICK", null, null)),
            ReplayEvidenceValidator.indexByTick(List.of(tick4)));
    assertTrue(ok.isEmpty());
  }

  @Test
  void recordingLevelChecksRejectGapsAndNullSchema() throws Exception {
    TickSnapshot a = snapshot(0L, "[]");
    TickSnapshot b = snapshot(1L, "[]");

    // Continuity: count must equal max-min+1 exactly.
    var gap = ReplayEvidenceValidator.validateRecordingEvidence(0, 2, 2, List.of(a, b));
    assertTrue(gap.stream().anyMatch(v -> v.code() == ReplayErrorCode.TICK_GAP_DETECTED));

    b.setSchemaVersion(null);
    var nullSchema = ReplayEvidenceValidator.validateRecordingEvidence(0, 1, 2, List.of(a, b));
    assertTrue(
        nullSchema.stream()
            .anyMatch(
                v ->
                    v.code() == ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED
                        && v.message().contains("NO schema version")));
  }
}
