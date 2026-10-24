package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/**
 * Java write-side anchors for native descriptor4 checkpoints, NOT a causal-v2 context or a
 * certification. Snapshot declarations alone never authorize restore. Context/row/ref are trusted
 * persisted anchors; their hashes are integrity bindings, not signatures against a database writer.
 */
final class NativeCheckpointContext {
  static final String SCHEMA = "xrsps-native-checkpoint-context/v1";
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "checkpointId",
          "runId",
          "attemptId",
          "branchKey",
          "parentCheckpointId",
          "manifestDigest",
          "runtimeDigest",
          "snapshotDigest",
          "snapshotChecksum",
          "stateHash",
          "chainHash",
          "tick",
          "actionIndex",
          "prefixDigest",
          "runStateDigest");

  private NativeCheckpointContext() {}

  static ObjectNode create(
      ReplayCheckpoint row,
      String artifactDigest,
      String manifestDigest,
      String runtimeDigest,
      NativeRunLedger ledger,
      CanonicalProjectionService hashes) {
    if (row.getActionIndex() == null
        || row.getActionIndex() != ledger.state().path("acceptedActions").asInt()
        || row.getTick() != ledger.state().path("tick").asLong())
      fail("write coordinates differ from ledger");
    ObjectNode context =
        JsonNodeFactory.instance
            .objectNode()
            .put("schema", SCHEMA)
            .put("checkpointId", row.getId().toString())
            .put("runId", row.getRunId().toString())
            .put("attemptId", row.getAttemptId().toString())
            .put("branchKey", row.getBranchKey())
            .put("manifestDigest", digest(manifestDigest))
            .put("runtimeDigest", digest(runtimeDigest))
            .put("snapshotDigest", digest(artifactDigest))
            .put("snapshotChecksum", digest(row.getSnapshotChecksum()))
            .put("stateHash", digest(row.getStateHash()))
            .put("chainHash", digest(row.getChainHash()))
            .put("tick", row.getTick())
            .put("actionIndex", row.getActionIndex())
            .put("prefixDigest", hashes.stateHash(ledger.prefix()))
            .put("runStateDigest", hashes.stateHash(ledger.state()));
    if (row.getBranchKey() == null || row.getBranchKey().isBlank()) fail("branch key missing");
    if (row.getParentCheckpointId() == null) context.putNull("parentCheckpointId");
    else context.put("parentCheckpointId", row.getParentCheckpointId().toString());
    return context;
  }

  static NativeRunLedger validate(
      JsonNode context,
      ReplayCheckpoint row,
      String artifactDigest,
      JsonNode manifest,
      String runtimeDigest,
      JsonNode snapshot,
      CanonicalProjectionService hashes) {
    if (context == null || !context.isObject() || context.size() != FIELDS.size())
      fail("closed context required");
    for (String field : FIELDS) if (!context.has(field)) fail("missing context field");
    if (!SCHEMA.equals(context.path("schema").asText()) || row.getChainArtifactChecksum() != null)
      fail("unsupported native context or causal chain binding");
    // Check independent anchors before interpreting any purported accepted prefix.
    if (!text(context, "checkpointId", row.getId().toString())
        || !text(context, "runId", row.getRunId().toString())
        || !text(context, "attemptId", row.getAttemptId().toString())
        || !text(context, "branchKey", row.getBranchKey())
        || !text(context, "manifestDigest", manifest.path("manifestChecksum").asText())
        || !text(context, "runtimeDigest", runtimeDigest)
        || !text(context, "snapshotDigest", artifactDigest)
        || !text(context, "snapshotDigest", row.getSnapshotArtifactChecksum())
        || !text(context, "snapshotChecksum", row.getSnapshotChecksum())
        || !text(context, "snapshotChecksum", snapshot.path("checksum").asText())
        || !text(context, "stateHash", row.getStateHash())
        || !text(context, "stateHash", snapshot.path("canonicalStateHash").asText())
        || !text(
            context,
            "stateHash",
            snapshot.path("contentState").path(0).path("observationHash").asText())
        || !text(context, "chainHash", row.getChainHash())
        || !coordinate(context.path("tick"), row.getTick())
        || row.getActionIndex() == null
        || !coordinate(context.path("actionIndex"), row.getActionIndex())
        || (row.getParentCheckpointId() == null
            ? !context.path("parentCheckpointId").isNull()
            : !text(context, "parentCheckpointId", row.getParentCheckpointId().toString())))
      fail("context differs from persisted checkpoint anchors");
    for (String field :
        Set.of(
            "manifestDigest",
            "runtimeDigest",
            "snapshotDigest",
            "snapshotChecksum",
            "stateHash",
            "chainHash",
            "prefixDigest",
            "runStateDigest"))
      digest(context.path(field).isTextual() ? context.path(field).asText() : null);
    JsonNode descriptor = snapshot.path("contentState").path(0);
    if (!text(context, "prefixDigest", hashes.stateHash(descriptor.path("steps")))
        || !text(context, "runStateDigest", hashes.stateHash(descriptor.path("runState"))))
      fail("native prefix binding differs");
    NativeRunLedger ledger = NativeRunLedger.fromAnchoredSnapshot(manifest, snapshot);
    // Compare normalized trusted state too; wire integer storage is not a protocol difference.
    if (!coordinate(ledger.state().path("tick"), row.getTick())
        || !coordinate(ledger.state().path("acceptedActions"), row.getActionIndex()))
      fail("restored ledger coordinates differ");
    return ledger;
  }

  private static boolean text(JsonNode node, String field, String expected) {
    return expected != null
        && node.path(field).isTextual()
        && expected.equals(node.path(field).asText());
  }

  private static boolean coordinate(JsonNode node, long expected) {
    return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() == expected;
  }

  private static String digest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) fail("malformed digest");
    return value;
  }

  private static void fail(String reason) {
    throw ReplayException.invalid(
        ReplayErrorCode.CHECKSUM_MISMATCH, "NATIVE_CHECKPOINT_INVALID: " + reason);
  }
}
