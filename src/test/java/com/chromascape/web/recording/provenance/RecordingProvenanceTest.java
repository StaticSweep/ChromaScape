package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.recording.entity.RecordingSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RecordingProvenanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void unknownAndKnownEmptyDiffer() throws Exception {
    assertNull(RecordingProvenance.capture("FISHING_TRAINING", null).settingsHash());
    assertNotNull(
        RecordingProvenance.capture("FISHING_TRAINING", JSON.readTree("{}")).settingsHash());
  }

  @Test
  void canonicalSettingsMatchProfileHasher() throws Exception {
    var a =
        RecordingProvenance.capture("FISHING_TRAINING", JSON.readTree("{\"b\":2.0,\"a\":true}"));
    var b = RecordingProvenance.capture("FISHING_TRAINING", JSON.readTree("{\"a\":true,\"b\":2}"));
    assertEquals(a.settingsHash(), b.settingsHash());
    assertEquals(a.settingsHash(), Micro6CanonicalHash.sha256WithPrefix(a.taskArgs()));
  }

  @Test
  void snapshotCopiesAndCannotBeOverwritten() throws Exception {
    var args = JSON.createObjectNode().put("fish", "shrimp");
    var p = RecordingProvenance.capture("FISHING_TRAINING", args);
    args.put("fish", "lobster");
    assertEquals("shrimp", p.taskArgs().path("fish").asText());
    var row = new RecordingSession();
    row.captureProvenance(p);
    assertThrows(IllegalStateException.class, () -> row.captureProvenance(p));
    ((com.fasterxml.jackson.databind.node.ObjectNode) row.getTaskArgs()).put("fish", "bad");
    assertEquals("shrimp", row.getTaskArgs().path("fish").asText());
  }

  @Test
  void rejectsCredentialTransportAndNonObjectArgs() throws Exception {
    for (String value :
        new String[] {
          "{\"token\":\"private\"}",
          "{\"nested\":{\"api_key\":\"private\"}}",
          "[]",
          "{\"é\":1,\"é\":2}"
        })
      assertThrows(
          ProvenanceException.class,
          () -> RecordingProvenance.capture("FISHING_TRAINING", JSON.readTree(value)));
    assertThrows(
        ProvenanceException.class,
        () -> RecordingProvenance.capture(null, JSON.createObjectNode()));
    assertThrows(
        ProvenanceException.class, () -> RecordingProvenance.capture("Fishing Training", null));
  }

  @Test
  void rejectsDeepOrLargeArgs() {
    var root = JSON.createObjectNode();
    var nested = root;
    for (int i = 0; i < 18; i++) nested = nested.putObject("n");
    assertEquals(
        "TASK_ARGS_TOO_DEEP",
        assertThrows(
                ProvenanceException.class,
                () -> RecordingProvenance.capture("FISHING_TRAINING", root))
            .getCode());
    var big = JSON.createObjectNode().put("name", "x".repeat(66000));
    assertEquals(
        "TASK_ARGS_TOO_LARGE",
        assertThrows(
                ProvenanceException.class,
                () -> RecordingProvenance.capture("FISHING_TRAINING", big))
            .getCode());
  }
}
