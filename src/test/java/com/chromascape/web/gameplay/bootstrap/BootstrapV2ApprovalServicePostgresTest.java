package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Bundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A v2 approval is bound to one frozen draft's scope digest. It reuses the single approval surface,
 * so it inherits the durable one-use rules rather than introducing a second state machine.
 */
@SpringBootTest(
    classes = BootstrapV2ApprovalServicePostgresTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapV2ApprovalServicePostgresTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-10T04:00:30Z"), ZoneOffset.UTC);

  @Autowired private BootstrapV2DraftStore drafts;
  @Autowired private BootstrapV2ApprovalService approvals;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void approvesThePersistedDraftsScopeDigest() {
    var draft = drafts.save(preview("request-a"));

    var approval =
        approvals.approve(
            draft.scopeDigest(), "operator-1", CLOCK.instant().plusSeconds(300), "CONFIRM");

    assertThat(approval.state()).isEqualTo("APPROVED");
    assertThat(approval.scopeDigest()).isEqualTo(draft.scopeDigest());
    assertThat(approval.requestFingerprint()).isEqualTo(draft.bundleDigest());
    assertThat(approval.recordingId()).isEqualTo("recording-request-a");
  }

  @Test
  void refusesAScopeDigestWithNoFrozenDraft() {
    assertThatThrownBy(
            () ->
                approvals.approve(
                    "sha256:" + "9".repeat(64),
                    "operator-1",
                    CLOCK.instant().plusSeconds(300),
                    "CONFIRM"))
        .isInstanceOfSatisfying(
            BootstrapV2ApprovalService.ApprovalException.class,
            exception -> assertThat(exception.failureCode()).isEqualTo("DRAFT_NOT_FOUND"));
  }

  @Test
  void refusesWithoutTheTypedConfirmation() {
    var draft = drafts.save(preview("request-b"));

    assertThatThrownBy(
            () ->
                approvals.approve(
                    draft.scopeDigest(), "operator-1", CLOCK.instant().plusSeconds(300), "yes"))
        .isInstanceOfSatisfying(
            BootstrapV2ApprovalService.ApprovalException.class,
            exception ->
                assertThat(exception.failureCode()).isEqualTo("APPROVAL_CONFIRMATION_REQUIRED"));
  }

  @Test
  void refusesADraftWhoseDeadlineHasPassed() {
    var draft = drafts.save(preview("request-c", CLOCK.instant().minusSeconds(1)));

    assertThatThrownBy(
            () ->
                approvals.approve(
                    draft.scopeDigest(), "operator-1", CLOCK.instant().plusSeconds(300), "CONFIRM"))
        .isInstanceOfSatisfying(
            BootstrapV2ApprovalService.ApprovalException.class,
            exception -> assertThat(exception.failureCode()).isEqualTo("DEADLINE_EXPIRED"));
  }

  @Test
  void bindingMovesTheApprovalToBoundAndRejectsADifferentAuthority() {
    var draft = drafts.save(preview("request-d"));
    var approval =
        approvals.approve(
            draft.scopeDigest(), "operator-1", CLOCK.instant().plusSeconds(300), "CONFIRM");

    var bound =
        approvals.bind(
            approval.approvalId(),
            "auth-1",
            "sha256:" + "7".repeat(64));

    assertThat(bound.state()).isEqualTo("BOUND");
    assertThat(bound.boundAuthorityId()).isEqualTo("auth-1");
    assertThatThrownBy(
            () ->
                approvals.bind(
                    approval.approvalId(), "auth-2", "sha256:" + "8".repeat(64)))
        .isInstanceOf(BootstrapApprovalStore.BootstrapApprovalException.class);
  }

  private BootstrapV2DraftService.Preview preview(String requestId) {
    return preview(requestId, CLOCK.instant().plusSeconds(600));
  }

  private BootstrapV2DraftService.Preview preview(String requestId, Instant deadline) {
    String seed = String.format("%064x", Math.abs(requestId.hashCode()));
    FrozenV2Bundle frozen =
        new FrozenV2Bundle(
            ("{\"bundle\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8),
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "sha256:" + seed,
            "auth-" + requestId,
            requestId,
            "idem-" + requestId,
            deadline);
    return new BootstrapV2DraftService.Preview(
        "session-1",
        "turn-1",
        requestId,
        "idem-" + requestId,
        "recording-" + requestId,
        frozen,
        JsonMapper.builder().build().createObjectNode(),
        JsonMapper.builder().build().createObjectNode(),
        java.util.Map.of(),
        CLOCK.instant());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackages = {"com.chromascape.web.gameplay.bootstrap"})
  @Import(JsonConfiguration.class)
  static class TestApplication {
    @Bean
    BootstrapV2DraftStore draftStore() {
      return new BootstrapV2DraftStore(CLOCK);
    }

    @Bean
    BootstrapApprovalStore approvalStore(EntityManager entityManager, CanonicalGameplayJson json) {
      return new BootstrapApprovalStore(entityManager, json, CLOCK);
    }

    @Bean
    BootstrapV2ApprovalService v2ApprovalService(
        BootstrapV2DraftStore drafts, BootstrapApprovalStore approvals) {
      return new BootstrapV2ApprovalService(drafts, approvals, CLOCK);
    }
  }

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
