package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.UUID;

/** Actual recovery transactions in the second JVM; no fabricated final report or progress. */
final class NativeRestartRecovery {
  private NativeRestartRecovery() {}

  static void verify(
      NativeCheckpointPersistenceTest t,
      ReplayExpiredAttemptRecoveryService recovery,
      ReplayRecoveryReportWriter writer,
      Path root)
      throws Exception {
    JsonNode prepared =
        t.json.readTree(
            Files.readAllBytes(root.resolve("prepare/native-database-continuation.json")));
    assertThat(prepared.path("jvmPid").asLong()).isNotEqualTo(ProcessHandle.current().pid());
    var report =
        t.json
            .createObjectNode()
            .put("origin", "AUTHORED_TEST_ONLY")
            .put("jvmPid", ProcessHandle.current().pid())
            .put("allRecoveriesPassed", false);
    var results = report.putArray("cases");
    var cases = new ArrayList<JsonNode>();
    prepared.path("variants").forEach(variant -> cases.add(variant.path("abandonedRestore")));
    cases.add(prepared.path("abandonedAdvance"));
    try {
      for (JsonNode abandoned : cases) {
        UUID id = UUID.fromString(abandoned.path("runId").asText());
        UUID attemptId = UUID.fromString(abandoned.path("attemptId").asText());
        var attempt = t.attempts.findById(attemptId).orElseThrow();
        assertThat(attempt.getNativeSourceCursor()).isEqualTo(abandoned.path("cursor"));
        assertThat(t.runs.findById(id).orElseThrow().getState()).isEqualTo(ReplayRunState.RUNNING);
        assertThat(t.reports.findByRunId(id)).isEmpty();
        assertThat(
                t.jdbc.queryForObject(
                    "SELECT count(*) FROM state_hash_samples WHERE run_id=?", Long.class, id))
            .isEqualTo(abandoned.path("hashSamples").asLong());
        // The fixture owns these exact IDs. Expire on the DB clock without waiting 300 seconds.
        assertThat(
                t.jdbc.update(
                    "UPDATE replay_attempts SET lease_expires_at=CURRENT_TIMESTAMP - interval '1 second'"
                        + " WHERE id=? AND run_id=? AND state='RUNNING' AND fencing_generation=?",
                    attemptId,
                    id,
                    abandoned.path("generation").asInt()))
            .isEqualTo(1);
        assertThat(recovery.recover(attemptId)).contains(ReplayRunState.FAILED);
        var terminal = t.runs.findById(id).orElseThrow();
        assertThat(terminal.getOutcome()).isEqualTo(ReplayOutcome.INFRASTRUCTURE_FAILURE);
        assertThat(terminal.getTerminalReason()).isEqualTo("LEASE_EXPIRED");
        var expired = t.attempts.findById(attemptId).orElseThrow();
        assertThat(expired.getState()).isEqualTo("EXPIRED");
        assertThat(expired.getFencingGeneration())
            .isEqualTo(abandoned.path("generation").asInt() + 1);
        var stored = t.reports.findByRunId(id).orElseThrow();
        assertThat(stored.getExecutedActionCount())
            .isEqualTo(abandoned.path("sourceActions").asInt());
        assertThat(stored.isControlAllowed()).isFalse();
        assertThat(recovery.recover(attemptId)).isEmpty();
        assertThat(writer.repair(id))
            .isEqualTo(ReplayRecoveryReportWriter.RepairOutcome.ALREADY_PRESENT);
        assertThat(t.reports.findByRunId(id).orElseThrow().getReportChecksum())
            .isEqualTo(stored.getReportChecksum());
        results
            .addObject()
            .put("runId", id.toString())
            .put("sourceActions", stored.getExecutedActionCount())
            .put("hashSamples", abandoned.path("hashSamples").asLong())
            .put("reportChecksum", stored.getReportChecksum())
            .put("recoveryIdempotent", true);
      }
      assertThat(results).hasSize(4);
      report.put("allRecoveriesPassed", true);
    } finally {
      Files.writeString(
          root.resolve("recovery.json"),
          t.json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
          StandardOpenOption.CREATE_NEW);
    }
  }
}
