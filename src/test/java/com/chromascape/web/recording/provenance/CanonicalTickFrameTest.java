package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class CanonicalTickFrameTest {
  static final ObjectMapper JSON = new ObjectMapper();

  static ObjectNode context() {
    var c =
        JSON.createObjectNode()
            .put("debugSessionId", "00000000-0000-4000-8000-000000000001")
            .put("runId", "00000000-0000-4000-8000-000000000002");
    for (String f :
        new String[] {
          "allocationId",
          "executorId",
          "clientInstanceId",
          "taskKey",
          "behaviorProfileId",
          "catalogHash",
          "settingsHash"
        }) c.put(f, f);
    c.put("behaviorProfileVersion", 1);
    c.putObject("privacy").put("policyVersion", "test-v1").putArray("redactedFields");
    return c;
  }

  static ObjectNode frame() {
    var c = context();
    var f =
        JSON.createObjectNode()
            .put("schemaVersion", 3)
            .put("type", "TICK_BATCH")
            .put("sessionId", c.path("debugSessionId").asText())
            .put("runId", c.path("runId").asText())
            .put("producerEpoch", "00000000-0000-4000-8000-000000000003")
            .put("sequence", 1)
            .put("tick", 100);
    f.set("runContext", c);
    f.put("semanticContractDigest", BehaviorSemanticContract.digest());
    f.set("privacy", c.path("privacy"));
    f.putObject("taskState")
        .put("stage", "FISHING")
        .put("stageRevision", 0)
        .put("progressRevision", 0);
    var e = f.putArray("semanticEvents").addObject();
    e.put("eventId", "00000000-0000-4000-8000-000000000004")
        .put("eventSequence", 1)
        .put("sourceTick", 100)
        .put("source", "CLIENT_EVENT")
        .put("kind", "FISHING_INTERACTION")
        .put("stage", "FISHING")
        .put("snapshotTick", 100);
    var q =
        f.putObject("quality")
            .put("expected", 1)
            .put("observed", 1)
            .put("dropped", 0)
            .put("unknown", 0)
            .put("producerVersion", "test-v1")
            .put("adapterVersion", "test-v1");
    q.putArray("captureLimitations");
    q.putArray("unsupportedFamilies");
    q.putArray("gapRanges");
    q.putObject("eventFamilyCounters").put("CLIENT_EVENT", 1);
    hash(f);
    return f;
  }

  static void hash(ObjectNode f) {
    f.remove("contentHash");
    f.put("contentHash", Micro6CanonicalHash.sha256WithPrefix(f).substring(7));
  }

  @Test
  void immutableDigestMatchesRecordedPayload() {
    var f = frame();
    var frozen = CanonicalTickFrame.validate(f, context());
    f.put("tick", 200);
    assertEquals(100, frozen.payload().path("tick").asInt());
    assertEquals(frozen.digest(), frozen.payload().path("contentHash").asText());
  }

  @Test
  void hashAndCrossSessionFailClosed() {
    var f = frame();
    f.put("tick", 110);
    assertEquals(
        "FRAME_HASH_MISMATCH",
        assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.validate(f, context()))
            .getCode());
    f.put("sessionId", "00000000-0000-4000-8000-000000000010");
    hash(f);
    assertEquals(
        "CROSS_SESSION_FRAME",
        assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.validate(f, context()))
            .getCode());
  }

  @Test
  void actionAlignmentFailuresAreStable() {
    for (var pair :
        java.util.Map.of(102, "FUTURE_OBSERVATION", 98, "STALE_OBSERVATION").entrySet()) {
      var f = frame();
      ((ObjectNode) f.path("semanticEvents").get(0)).put("sourceTick", pair.getKey());
      hash(f);
      assertEquals(
          pair.getValue(),
          assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.validate(f, context()))
              .getCode());
    }
    var f = frame();
    ((ObjectNode) f.path("semanticEvents").get(0)).put("snapshotTick", 102);
    hash(f);
    assertEquals(
        "FUTURE_SNAPSHOT",
        assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.validate(f, context()))
            .getCode());
  }

  @Test
  void humanActionsNeverBecomeAutomated() {
    var f = frame();
    ((ObjectNode) f.path("semanticEvents").get(0))
        .put("automated", true)
        .put("humanObserved", true);
    hash(f);
    assertEquals(
        "HUMAN_ACTION_RELABELLED",
        assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.validate(f, context()))
            .getCode());
  }

  @Test
  void unknownQualityCannotBecomeCompleteFromSnapshot() {
    var q =
        JSON.createObjectNode()
            .put("acceptedFrames", 1)
            .put("dropped", 0)
            .put("unknown", 1)
            .put("closure", "EXPLICIT_STOP");
    q.putArray("gapRanges");
    q.putArray("unsupportedFamilies");
    assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.requireComplete(q));
    q.put("unknown", 0);
    CanonicalTickFrame.requireComplete(q);
    q.put("closure", "DISCONNECT");
    assertThrows(ProvenanceException.class, () -> CanonicalTickFrame.requireComplete(q));
  }

  @Test
  void semanticMeaningOrderAndMaskMustConform() {
    var contract = (ObjectNode) BehaviorSemanticContract.definition();
    BehaviorSemanticContract.conform(contract);
    ((ObjectNode) contract.path("actions").get(1)).put("mask", "always");
    assertEquals(
        "SEMANTIC_CONTRACT_MISMATCH",
        assertThrows(ProvenanceException.class, () -> BehaviorSemanticContract.conform(contract))
            .getCode());
  }
}
