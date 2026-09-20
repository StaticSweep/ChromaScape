package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Flyway and constraint proof for the SC-1498 durable approval/recording schema (V50). */
@SpringBootTest(
    classes = Sc1498FlywaySchemaTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class Sc1498FlywaySchemaTest {
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void v50TablesExistWithSafetyAndImmutabilityGuards() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = current_schema() and table_name='gameplay_bootstrap_approval'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = current_schema() and table_name='gameplay_bootstrap_recording'",
                Integer.class))
        .isEqualTo(1);
    // Safety flags must be constrained false; the migration check name is the contract.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_constraint c join pg_namespace n on n.oid = c.connamespace where n.nspname = current_schema() and c.conname='gameplay_bootstrap_recording_safety_check'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_constraint c join pg_namespace n on n.oid = c.connamespace where n.nspname = current_schema() and c.conname='gameplay_bootstrap_approval_state_check'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void approvalBindAttachesAuthorityEvidence() {
    insertApproval("approval-bind-1", "a");
    jdbc.execute(
        "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
            + " bound_authority_id='auth-1',"
            + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
            + " updated_at=now() WHERE approval_id='approval-bind-1'");
    assertThat(
            jdbc.queryForObject(
                "select state from gameplay_bootstrap_approval where approval_id='approval-bind-1'",
                String.class))
        .isEqualTo("BOUND");
    // Terminal states are immutable: no further update is permitted.
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET updated_at=now()"
                        + " WHERE approval_id='approval-bind-1'"))
        .hasMessageContaining("already left APPROVED");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "DELETE FROM gameplay_bootstrap_approval WHERE approval_id='approval-bind-1'"))
        .hasMessageContaining("append-only");
  }

  @Test
  void approvalCancelAndExpireCarryNoEvidence() {
    insertApproval("approval-cancel-1", "b");
    jdbc.execute(
        "UPDATE gameplay_bootstrap_approval SET state='CANCELLED', updated_at=now()"
            + " WHERE approval_id='approval-cancel-1'");
    insertApproval("approval-expire-1", "c");
    jdbc.execute(
        "UPDATE gameplay_bootstrap_approval SET state='EXPIRED', updated_at=now()"
            + " WHERE approval_id='approval-expire-1'");
    assertThat(
            jdbc.queryForObject(
                "select state from gameplay_bootstrap_approval where approval_id='approval-cancel-1'",
                String.class))
        .isEqualTo("CANCELLED");
  }

  @Test
  void approvalBoundRequiresSignatureDigest() {
    insertApproval("approval-nosig-1", "d");
    // Direct SQL must not bind without the signature evidence, even with an authority id.
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
                        + " bound_authority_id='auth-1', updated_at=now()"
                        + " WHERE approval_id='approval-nosig-1'"))
        .hasMessageContaining("BOUND requires authority evidence");
    insertApproval("approval-badsig-1", "e");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
                        + " bound_authority_id='auth-1', signature_digest='not-a-digest',"
                        + " updated_at=now() WHERE approval_id='approval-badsig-1'"))
        .hasMessageContaining("BOUND requires authority evidence");
  }

  @Test
  void approvalCancelCannotSmuggleAuthorityEvidence() {
    insertApproval("approval-smuggle-1", "f");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='CANCELLED',"
                        + " bound_authority_id='auth-1',"
                        + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                        + " updated_at=now() WHERE approval_id='approval-smuggle-1'"))
        .hasMessageContaining("only BOUND carries authority evidence");
  }

  @Test
  void approvalIdentityRewritingIsRejected() {
    insertApproval("approval-rewrite-1", "1");
    // APPROVED -> BOUND must not rewrite scope, request identity, operator or expiry.
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
                        + " bound_authority_id='auth-1',"
                        + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                        + " scope_digest='sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',"
                        + " updated_at=now() WHERE approval_id='approval-rewrite-1'"))
        .hasMessageContaining("identity is immutable");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
                        + " bound_authority_id='auth-1',"
                        + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                        + " approved_by='intruder',"
                        + " updated_at=now() WHERE approval_id='approval-rewrite-1'"))
        .hasMessageContaining("identity is immutable");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
                        + " bound_authority_id='auth-1',"
                        + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                        + " request_id='req-intruder',"
                        + " updated_at=now() WHERE approval_id='approval-rewrite-1'"))
        .hasMessageContaining("identity is immutable");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
                        + " bound_authority_id='auth-1',"
                        + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
                        + " expires_at=now() + interval '99 minutes',"
                        + " updated_at=now() WHERE approval_id='approval-rewrite-1'"))
        .hasMessageContaining("identity is immutable");
    // The rejected rewrites left the row APPROVED and bindable with honest evidence.
    jdbc.execute(
        "UPDATE gameplay_bootstrap_approval SET state='BOUND',"
            + " bound_authority_id='auth-1',"
            + " signature_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
            + " updated_at=now() WHERE approval_id='approval-rewrite-1'");
    assertThat(
            jdbc.queryForObject(
                "select state from gameplay_bootstrap_approval where approval_id='approval-rewrite-1'",
                String.class))
        .isEqualTo("BOUND");
  }

  @Test
  void recordingStartsWithoutParentButFinalizesOnlyWithTerminalEvidence() {
    // Recording begins before SC-1431 delivery, so STARTED without a parent is legal.
    insertRecording("orch-noparent-1", "rec-noparent-1");
    assertThatThrownBy(() -> finalizeRecording("orch-noparent-1"))
        .hasMessageContaining("cannot finalize without terminal SC-1431 evidence");

    insertRecording("orch-parentonly-1", "rec-parentonly-1");
    insertOrchestrationParent("orch-parentonly-1");
    // A delivered parent without a durable terminal result still cannot finalize.
    assertThatThrownBy(() -> finalizeRecording("orch-parentonly-1"))
        .hasMessageContaining("cannot finalize without terminal SC-1431 evidence");

    insertRecording("orch-terminal-1", "rec-terminal-1");
    insertOrchestrationParent("orch-terminal-1");
    insertTerminalResult("orch-terminal-1");
    finalizeRecording("orch-terminal-1");
    assertThat(
            jdbc.queryForObject(
                "select manifest_state from gameplay_bootstrap_recording"
                    + " where orchestration_id='orch-terminal-1'",
                String.class))
        .isEqualTo("FINALIZED");
  }

  @Test
  void finalizedRecordingRowsAreImmutableAndUndeletable() {
    insertRecording("orch-frozen-1", "rec-frozen-1");
    insertOrchestrationParent("orch-frozen-1");
    insertTerminalResult("orch-frozen-1");
    finalizeRecording("orch-frozen-1");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "UPDATE gameplay_bootstrap_recording SET artifact_ref='tampered.mp4'"
                        + " WHERE orchestration_id='orch-frozen-1'"))
        .hasMessageContaining("FINALIZED and immutable");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "DELETE FROM gameplay_bootstrap_recording WHERE orchestration_id='orch-frozen-1'"))
        .hasMessageContaining("append-only");
  }

  private void insertApproval(String approvalId, String hex) {
    String scope = "sha256:" + hex.repeat(64);
    String fingerprint = "sha256:" + "b".repeat(64);
    jdbc.execute(
        "INSERT INTO gameplay_bootstrap_approval (approval_id, scope_digest,"
            + " provider_session_id, provider_turn_id, orchestration_id, request_id, recording_id,"
            + " request_fingerprint, approved_by, approved_at, expires_at, state,"
            + " created_at, updated_at) VALUES ('"
            + approvalId
            + "','"
            + scope
            + "','sess-1','turn-1','orch-"
            + approvalId
            + "','req-"
            + approvalId
            + "','rec-"
            + approvalId
            + "','"
            + fingerprint
            + "','operator-sc1498',"
            + " now(), now() + interval '10 minutes','APPROVED', now(), now())");
  }

  private void insertRecording(String orchestrationId, String recordingId) {
    jdbc.execute(
        "INSERT INTO gameplay_bootstrap_recording (orchestration_id, recording_id,"
            + " provider_session_id, provider_turn_id, decision_digest, envelope_digest,"
            + " provider, model_version, manifest_state, created_at) VALUES ('"
            + orchestrationId
            + "','"
            + recordingId
            + "','sess-1','turn-1',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'CLAUDE','model-v1','STARTED',now())");
  }

  private void finalizeRecording(String orchestrationId) {
    jdbc.execute(
        "UPDATE gameplay_bootstrap_recording SET manifest_state='FINALIZED',"
            + " terminal_outcome='CONFIRMED', stable_code='NONE',"
            + " manifest_digest='sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',"
            + " finalized_at=now() WHERE orchestration_id='"
            + orchestrationId
            + "'");
  }

  private void insertOrchestrationParent(String orchestrationId) {
    jdbc.execute(
        "INSERT INTO ab_orchestration (orchestration_id, request_id, idempotency_key,"
            + " request_fingerprint, envelope_digest, source_type, mode, state, failure_code,"
            + " request_json, view_json, retry_allowed, view_version, created_at, updated_at,"
            + " entity_version) VALUES ('"
            + orchestrationId
            + "','req-"
            + orchestrationId
            + "',"
            + "'idem-"
            + orchestrationId
            + "',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'PROVIDER:CLAUDE','BOOTSTRAP_RECORDING','CONFIRMED','NONE','{}','{}',FALSE,0,"
            + " now(),now(),0) ON CONFLICT DO NOTHING");
  }

  private void insertTerminalResult(String orchestrationId) {
    jdbc.execute(
        "INSERT INTO ab_orchestration_terminal_result (orchestration_id, outcome, stable_code,"
            + " retry_allowed, reconciled, evidence_digest, terminal_at) VALUES ('"
            + orchestrationId
            + "','CONFIRMED','NONE',FALSE,TRUE,"
            + " 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',now())"
            + " ON CONFLICT DO NOTHING");
  }

  @Test
  void safetyFlagsRejectTrue() {
    // Minimal parent for the recording terminal-evidence gate (there is no foreign key).
    jdbc.execute(
        "INSERT INTO ab_orchestration (orchestration_id, request_id, idempotency_key,"
            + " request_fingerprint, envelope_digest, source_type, mode, state, failure_code,"
            + " request_json, view_json, retry_allowed, view_version, created_at, updated_at,"
            + " entity_version) VALUES ('orch-safety-1','req-safety-1','idem-safety-1',"
            + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
            + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
            + " 'PROVIDER:CLAUDE','BOOTSTRAP_RECORDING','ACTIVE','NONE','{}','{}',FALSE,0,"
            + " now(),now(),0) ON CONFLICT DO NOTHING");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "INSERT INTO gameplay_bootstrap_recording (orchestration_id, recording_id,"
                        + " provider_session_id, provider_turn_id, decision_digest, envelope_digest,"
                        + " provider, model_version, manifest_state, learned_model_activated,"
                        + " continuing_authority, simulator_trace, created_at) VALUES"
                        + " ('orch-safety-1','rec-safety-1','s','t',"
                        + " 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
                        + " 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
                        + " 'CLAUDE','m','STARTED',TRUE,FALSE,FALSE,now())"))
        .hasMessageContaining("gameplay_bootstrap_recording_safety_check");
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(
      basePackages = {
        "com.chromascape.web.gameplay.bootstrap",
        "com.chromascape.web.gameplay.orchestration.accountbuilder"
      })
  @Import(JsonConfiguration.class)
  static class TestApplication {}

  @TestConfiguration
  static class JsonConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
    }

    @Bean
    CanonicalGameplayJson canonicalGameplayJson(ObjectMapper objectMapper) {
      return new CanonicalGameplayJson(objectMapper);
    }
  }
}
