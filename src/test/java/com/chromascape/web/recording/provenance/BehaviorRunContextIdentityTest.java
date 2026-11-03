package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Identity of a bound capture context. SC-274 was refused {@code IMMUTABLE_PROVENANCE} on a reused
 * session, and the check compared raw trees while every consumer identifies a context by its
 * canonical digest.
 */
class BehaviorRunContextIdentityTest {

  private final ObjectMapper json = new ObjectMapper();
  private final BehaviorRunContextService contexts =
      new BehaviorRunContextService(null, null, null, null, json);

  private ObjectNode snapshot(long privacyLimit) {
    return snapshot(json.getNodeFactory().numberNode(privacyLimit));
  }

  private ObjectNode snapshot(double privacyLimit) {
    return snapshot(json.getNodeFactory().numberNode(privacyLimit));
  }

  private ObjectNode snapshot(com.fasterxml.jackson.databind.JsonNode privacyLimit) {
    ObjectNode snapshot = json.createObjectNode().put("schemaVersion", 1);
    snapshot.put("runId", "0f0e0d0c-0b0a-4908-8706-050403020100");
    snapshot.put("dataOrigin", "HUMAN");
    snapshot.put("graphRevision", "sha256:" + "a".repeat(64));
    ObjectNode privacy = snapshot.putObject("privacy");
    privacy.put("policyVersion", "privacy/v1");
    privacy.putArray("redactedFields").add("password");
    privacy.set("limit", privacyLimit);
    return snapshot;
  }

  @Test
  void numericRepresentationAloneKeepsOneIdentity() {
    ObjectNode stored = contexts.bind(snapshot(5L), "rec_1", "session_1");
    ObjectNode echo = contexts.bind(snapshot(5.0d), "rec_1", "session_1");

    assertNotEquals(
        stored.get("privacy"), echo.get("privacy"), "raw trees differ only by number type");
    assertTrue(contexts.sameProvenance(echo, stored));
    assertTrue(contexts.differingPaths(echo, stored).isEmpty());
  }

  @Test
  void aDifferentValueIsStillRefused() {
    ObjectNode stored = contexts.bind(snapshot(5L), "rec_1", "session_1");
    ObjectNode changed = snapshot(5L);
    changed.put("graphRevision", "sha256:" + "b".repeat(64));

    assertFalse(contexts.sameProvenance(contexts.bind(changed, "rec_1", "session_1"), stored));
    assertEquals(
        List.of("graphRevision", "contextHash"),
        contexts.differingPaths(contexts.bind(changed, "rec_1", "session_1"), stored));
  }

  @Test
  void aDifferentRecordingOrSessionIsStillRefused() {
    ObjectNode stored = contexts.bind(snapshot(5L), "rec_1", "session_1");

    assertFalse(contexts.sameProvenance(contexts.bind(snapshot(5L), "rec_2", "session_1"), stored));
    assertFalse(contexts.sameProvenance(contexts.bind(snapshot(5L), "rec_1", "session_2"), stored));
  }

  @Test
  void aStoredContextWhoseDigestNoLongerVerifiesIsRefused() {
    ObjectNode stored = contexts.bind(snapshot(5L), "rec_1", "session_1");
    // The digest still matches the original tree, so it no longer verifies the edited one.
    stored.put("graphRevision", "sha256:" + "c".repeat(64));

    assertFalse(contexts.sameProvenance(contexts.bind(snapshot(5L), "rec_1", "session_1"), stored));
    assertEquals(
        List.of("graphRevision"),
        contexts.differingPaths(contexts.bind(snapshot(5L), "rec_1", "session_1"), stored));
  }

  @Test
  void aContextWithoutAnyDigestIsRefused() {
    ObjectNode stored = contexts.bind(snapshot(5L), "rec_1", "session_1");
    stored.remove("contextHash");

    assertFalse(contexts.sameProvenance(contexts.bind(snapshot(5L), "rec_1", "session_1"), stored));
  }

  @Test
  void theDiagnosticNamesFieldsWithoutTheirValues() {
    ObjectNode stored = contexts.bind(snapshot(5L), "rec_1", "session_1");
    ObjectNode changed = snapshot(5.0d);
    changed.put("executorId", "exec_2");

    List<String> paths =
        contexts.differingPaths(contexts.bind(changed, "rec_1", "session_1"), stored);

    assertTrue(paths.contains("executorId"));
    assertFalse(paths.contains("privacy"), "a purely numeric difference is not reported");
    assertTrue(paths.size() <= 16);
  }
}
