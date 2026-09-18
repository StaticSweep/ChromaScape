package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AccountBuilderOrchestrationConcurrencyTest {
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");

  @Test
  void identicalClaimsHaveOneFingerprintBoundOwner() throws Exception {
    InMemoryAccountBuilderOrchestrationStore store = store();
    OrchestrationRequest request = request("identical-claims", "shared-authority");
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<AccountBuilderOrchestrationStore.Claim>> futures =
          IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () ->
                              store.claim(
                                  request,
                                  digest("same-fingerprint"),
                                  envelopeDigest(request),
                                  budget(request))))
              .toList();
      List<AccountBuilderOrchestrationStore.Claim> claims =
          futures.stream().map(this::get).toList();

      assertThat(claims.stream().filter(AccountBuilderOrchestrationStore.Claim::created).count())
          .isEqualTo(1);
      assertThat(claims.stream().map(claim -> claim.view().orchestrationId()).distinct())
          .containsExactly(request.orchestrationId());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void conflictingAuthorityClaimsHaveOneDurableOwner() throws Exception {
    InMemoryAccountBuilderOrchestrationStore store = store();
    OrchestrationRequest first = request("authority-race-a", "shared-authority");
    OrchestrationRequest second = request("authority-race-b", "shared-authority");
    String firstFingerprint = digest("authority-race-a");
    String secondFingerprint = digest("authority-race-b");
    store.claim(first, firstFingerprint, envelopeDigest(first), budget(first));
    store.claim(second, secondFingerprint, envelopeDigest(second), budget(second));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> firstFuture =
          executor.submit(
              () ->
                  store.consumeAuthority(
                      first.orchestrationId(), first.authority(), envelopeDigest(first), first));
      Future<?> secondFuture =
          executor.submit(
              () ->
                  store.consumeAuthority(
                      second.orchestrationId(),
                      second.authority(),
                      envelopeDigest(second),
                      second));
      int successes = 0;
      int authorityConflicts = 0;
      for (Future<?> future : List.of(firstFuture, secondFuture)) {
        try {
          future.get();
          successes++;
        } catch (ExecutionException exception) {
          if (exception.getCause() instanceof AccountBuilderOrchestrationException error
              && error.failureCode()
                  == AccountBuilderOrchestrationModels.FailureCode.AUTHORITY_ALREADY_USED) {
            authorityConflicts++;
          } else {
            throw exception;
          }
        }
      }
      assertThat(successes).isEqualTo(1);
      assertThat(authorityConflicts).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private InMemoryAccountBuilderOrchestrationStore store() {
    return new InMemoryAccountBuilderOrchestrationStore(
        Clock.fixed(NOW, ZoneOffset.UTC), (authority, envelope) -> true);
  }

  private OrchestrationRequest request(String suffix, String authorityId) {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    CanonicalGameplayJson canonical = new CanonicalGameplayJson(mapper);
    var action = JsonNodeFactory.instance.objectNode().put("kind", "START_FISHING_TASK");
    ExecutionEnvelope envelope =
        new ExecutionEnvelope(
            GameplayContracts.VERSION,
            Provider.CLAUDE,
            "model-v1",
            "decision-" + suffix,
            "envelope-request",
            "envelope-idempotency",
            digest("goal"),
            List.of(digest("skill")),
            "FISHING_TRAINING",
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            digest("catalog"),
            digest("schema"),
            digest("policy"),
            digest("knowledge"),
            canonical.digest(action),
            "FISHING_XP_INCREASED",
            "verifier-v1",
            "build-v1",
            1,
            1,
            authorityId,
            1,
            1,
            JsonNodeFactory.instance.objectNode(),
            NOW.plusSeconds(300),
            "RUN");
    ProviderDecision decision =
        new ProviderDecision(
            GameplayContracts.VERSION,
            Provider.CLAUDE,
            envelope.modelVersion(),
            envelope.decisionId(),
            1.0,
            action,
            List.of("observation:one"));
    SnapshotBindings bindings =
        new SnapshotBindings(
            canonical.digest(decision),
            canonical.digest(action),
            envelope.catalogDigest(),
            digest("capability"),
            envelope.goalPlanDigest(),
            envelope.taskSkillDigests(),
            envelope.schemaDigest(),
            envelope.knowledgeDigest(),
            envelope.policyDigest(),
            envelope.verifierId(),
            envelope.buildId(),
            1,
            1);
    HumanAuthority authority =
        new HumanAuthority(
            GameplayContracts.VERSION,
            authorityId,
            "HUMAN",
            "operator",
            NOW,
            NOW.plusSeconds(120),
            canonical.digest(envelope),
            digest("approved-scope"),
            true,
            "hmac-sha256:" + "0".repeat(64));
    return new OrchestrationRequest(
        "orchestration-" + suffix,
        "request-" + suffix,
        "idempotency-" + suffix,
        null,
        Mode.LIVE_TASK,
        "TEST",
        decision,
        envelope,
        bindings,
        authority,
        JsonNodeFactory.instance.objectNode(),
        envelope.deadline());
  }

  private String envelopeDigest(OrchestrationRequest request) {
    return new CanonicalGameplayJson(new ObjectMapper().findAndRegisterModules())
        .digest(request.envelope());
  }

  private String digest(String value) {
    return "sha256:" + "a".repeat(64);
  }

  private AccountBuilderOrchestrationStore.Claim get(
      Future<AccountBuilderOrchestrationStore.Claim> future) {
    try {
      return future.get();
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static AccountBuilderBudgetPolicy.OrchestrationBudget budget(
      OrchestrationRequest request) {
    return new AccountBuilderBudgetPolicy.OrchestrationBudget(
        5, 2, 60_000, 0, 0, NOW, request.deadline());
  }
}
