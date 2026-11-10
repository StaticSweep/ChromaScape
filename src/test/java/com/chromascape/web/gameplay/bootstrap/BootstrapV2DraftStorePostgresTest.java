package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A frozen v2 draft is written once and never rewritten. The database enforces that as well as the
 * store, so an approval can never be re-pointed at different bytes.
 */
@SpringBootTest(
    classes = BootstrapV2DraftStorePostgresTest.TestApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1498-read-token",
      "stabilization.auth.write-token=sc1498-write-token"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class BootstrapV2DraftStorePostgresTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-10T04:00:30Z"), ZoneOffset.UTC);

  @Autowired private BootstrapV2DraftStore store;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void persistsTheFrozenBytesAndEveryDigest() {
    var view = store.save(preview("request-a", "sha256:" + "1".repeat(64)));

    assertThat(view.bundleDigest()).isEqualTo("sha256:" + "1".repeat(64));
    assertThat(new String(view.bytes(), StandardCharsets.UTF_8)).isEqualTo("{\"bundle\":\"1\"}");
    assertThat(view.scopeDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(view.envelopeDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(view.goalPlanDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(view.createdAt()).isEqualTo(CLOCK.instant());
  }

  @Test
  void rePersistingTheSameDraftIsIdempotent() {
    var first = store.save(preview("request-b", "sha256:" + "a".repeat(64)));
    var second = store.save(preview("request-b", "sha256:" + "a".repeat(64)));

    assertThat(second.draftId()).isEqualTo(first.draftId());
    assertThat(second.bundleDigest()).isEqualTo(first.bundleDigest());
  }

  @Test
  void aDifferentDraftUnderTheSameRequestConflicts() {
    store.save(preview("request-c", "sha256:" + "b".repeat(64)));

    assertThatThrownBy(() -> store.save(preview("request-c", "sha256:" + "c".repeat(64))))
        .isInstanceOfSatisfying(
            BootstrapV2DraftStore.BootstrapV2DraftException.class,
            exception -> assertThat(exception.failureCode()).isEqualTo("DRAFT_CONFLICT"));
  }

  @Test
  void theDatabaseRejectsAnyUpdateOrDelete() {
    var view = store.save(preview("request-d", "sha256:" + "d".repeat(64)));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE gameplay_bootstrap_v2_draft SET bundle_bytes='tampered'"
                        + " WHERE draft_id=?",
                    view.draftId()))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM gameplay_bootstrap_v2_draft WHERE draft_id=?", view.draftId()))
        .hasMessageContaining("immutable");
  }

  @Test
  void findsADraftByItsApprovedScopeDigest() {
    var saved = store.save(preview("request-e", "sha256:" + "e".repeat(64)));

    assertThat(store.findByScopeDigest(saved.scopeDigest())).isPresent();
    assertThat(store.findByScopeDigest("sha256:" + "9".repeat(64))).isEmpty();
  }

  private BootstrapV2DraftService.Preview preview(String requestId, String bundleDigest) {
    String seed = String.format("%064x", Math.abs(requestId.hashCode()));
    FrozenV2Bundle frozen =
        new FrozenV2Bundle(
            ("{\"bundle\":\"" + bundleDigest.charAt(7) + "\"}").getBytes(StandardCharsets.UTF_8),
            bundleDigest,
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
            CLOCK.instant().plusSeconds(600));
    return new BootstrapV2DraftService.Preview(
        "session-1",
        "turn-1",
        requestId,
        "idem-" + requestId,
        "recording-1",
        frozen,
        JsonMapper.builder().build().createObjectNode(),
        JsonMapper.builder().build().createObjectNode(),
        java.util.Map.of(),
        CLOCK.instant());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackages = {"com.chromascape.web.gameplay.bootstrap"})
  static class TestApplication {
    @Bean
    BootstrapV2DraftStore draftStore() {
      return new BootstrapV2DraftStore(CLOCK);
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
  }
}
