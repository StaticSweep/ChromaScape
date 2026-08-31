package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** PostgreSQL/JPA proof that normalized evidence survives a fresh persistence context. */
@SpringBootTest(
    classes = JpaAccountBuilderOrchestrationStoreTest.JpaTestApplication.class,
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/chromascape_sc1431_test?sslmode=disable",
      "spring.datasource.username=chromascape",
      "spring.datasource.password=chromascape123",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "stabilization.auth.token=sc1431-read-token",
      "stabilization.auth.write-token=sc1431-write-token",
      "gameplay.bootstrap.authority-hmac-secret=sc1431-test-authority-secret-32-bytes"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class JpaAccountBuilderOrchestrationStoreTest {
  private static final Instant NOW = Instant.parse("2026-08-31T12:05:00Z");
  private static final String SECRET = "sc1431-test-authority-secret-32-bytes";
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Autowired private EntityManager entityManager;
  @Autowired private HumanGameplayAuthorityService authorityService;
  @Autowired private CanonicalGameplayJson canonicalJson;
  @Autowired private ObjectMapper mapper;
  private JpaAccountBuilderOrchestrationStore store;

  @BeforeEach
  void setUpStore() {
    store =
        new JpaAccountBuilderOrchestrationStore(
            entityManager, mapper, canonicalJson, CLOCK, authorityService);
  }

  @Test
  void normalizedRowsReconstructAfterPersistenceContextClear() {
    OrchestrationRequest request = request("jpa-reconstruct");
    String envelopeDigest = canonicalJson.digest(request.envelope());

    assertThat(store.claim(request, request.requestFingerprint(), envelopeDigest).created())
        .isTrue();
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    store.consumeAuthority(
        request.orchestrationId(), request.authority(), envelopeDigest, request);
    store.transition(
        request.orchestrationId(), State.AUTHORIZED, FailureCode.NONE, null, null, false, false);

    Lease lease =
        new Lease(
            "reservation-jpa-reconstruct",
            request.envelope().leaseGeneration(),
            request.envelope().fence(),
            digest("fence-token"),
            NOW.plusSeconds(120));
    store.transition(
        request.orchestrationId(),
        State.RESERVED,
        FailureCode.NONE,
        lease,
        receipt(request, envelopeDigest, "RESERVATION", State.RESERVED, lease, false, false),
        false,
        false);
    store.transition(
        request.orchestrationId(),
        State.DISPATCH_SUBMITTED,
        FailureCode.NONE,
        lease,
        null,
        false,
        false);
    store.transition(
        request.orchestrationId(),
        State.ACTIVE,
        FailureCode.NONE,
        lease,
        receipt(request, envelopeDigest, "DISPATCH", State.ACTIVE, lease, true, false),
        false,
        false);
    store.transition(
        request.orchestrationId(),
        State.CONFIRMED,
        FailureCode.NONE,
        lease,
        receipt(request, envelopeDigest, "STATUS", State.CONFIRMED, lease, true, true),
        false,
        false);

    entityManager.flush();
    entityManager.clear();

    AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
        store.readAggregate(request.orchestrationId());
    assertThat(aggregate.view().state()).isEqualTo(State.CONFIRMED);
    assertThat(aggregate.view().receipts()).hasSize(3);
    assertThat(aggregate.authority()).isNotNull();
    assertThat(aggregate.lease()).isEqualTo(lease);
    assertThat(aggregate.terminalResult()).isNotNull();
    assertThat(count("ab_snapshot_binding")).isEqualTo(1);
    assertThat(count("ab_authority_consumption")).isEqualTo(1);
    assertThat(count("ab_orchestration_lease")).isEqualTo(1);
    assertThat(count("ab_orchestration_receipt")).isEqualTo(3);
    assertThat(count("ab_orchestration_event")).isEqualTo(8);
    assertThat(count("ab_orchestration_terminal_result")).isEqualTo(1);

    String persistedRequest =
        (String)
            entityManager
                .createNativeQuery(
                    "select request_json from ab_orchestration where orchestration_id=:id")
                .setParameter("id", request.orchestrationId())
                .getSingleResult();
    assertThat(persistedRequest).doesNotContain("secret-setting");
    assertThat(persistedRequest).doesNotContain("signature");
  }

  @Test
  void changedFingerprintCannotReuseDurableIdentity() {
    OrchestrationRequest request = request("jpa-conflict");
    String envelopeDigest = canonicalJson.digest(request.envelope());
    store.claim(request, request.requestFingerprint(), envelopeDigest);
    OrchestrationRequest changed = withFingerprint(request, digest("changed-fingerprint"));

    assertThatThrownBy(
            () -> store.claim(changed, changed.requestFingerprint(), envelopeDigest))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    assertThat(store.find(request.orchestrationId())).isPresent();
  }

  private long count(String table) {
    return ((Number)
            entityManager.createNativeQuery("select count(*) from " + table).getSingleResult())
        .longValue();
  }

  private OrchestrationRequest request(String suffix) {
    var action =
        JsonNodeFactory.instance
            .objectNode()
            .put("taskKey", "FISHING_TRAINING")
            .put("expectedTransition", "FISHING_XP_INCREASED")
            .put("verifierId", "verifier-v1");
    var settings = JsonNodeFactory.instance.objectNode().put("secret-setting", "must-not-persist");
    var identities = JsonNodeFactory.instance.objectNode().put("runId", "run-" + suffix);
    var budgets = JsonNodeFactory.instance.objectNode().put("actions", 1);
    ExecutionEnvelope envelope =
        new ExecutionEnvelope(
            GameplayContracts.VERSION,
            Provider.CLAUDE,
            "model-v1",
            "decision-" + suffix,
            "request-" + suffix,
            "idempotency-" + suffix,
            digest("goal-plan"),
            List.of(digest("task-skill")),
            "FISHING_TRAINING",
            settings,
            identities,
            digest("catalog"),
            digest("tool-schema"),
            digest("policy"),
            digest("knowledge"),
            canonicalJson.digest(action),
            "FISHING_XP_INCREASED",
            "verifier-v1",
            "build-v1",
            1,
            1,
            "authority-" + suffix,
            1,
            1,
            budgets,
            NOW.plusSeconds(300),
            "RUN");
    ProviderDecision decision =
        new ProviderDecision(
            GameplayContracts.VERSION,
            Provider.CLAUDE,
            envelope.modelVersion(),
            envelope.decisionId(),
            0.9,
            action,
            List.of("observation:" + suffix));
    SnapshotBindings bindings =
        new SnapshotBindings(
            canonicalJson.digest(decision),
            canonicalJson.digest(action),
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
    HumanAuthority authority = signedAuthority(envelope);
    // ObjectNode.set returns JsonNode, so the object cannot be built in one chained expression.
    var context =
        JsonNodeFactory.instance
            .objectNode()
            .put("revision", 1)
            .put("catalogDigest", envelope.catalogDigest())
            .put("buildId", envelope.buildId());
    context.set("identities", identities);
    context.set("taskState", JsonNodeFactory.instance.objectNode().put("revision", 1));
    OrchestrationRequest withoutFingerprint =
        new OrchestrationRequest(
            "orchestration-" + suffix,
            envelope.requestId(),
            envelope.idempotencyKey(),
            null,
            Mode.LIVE_TASK,
            "TEST",
            decision,
            envelope,
            bindings,
            authority,
            context,
            envelope.deadline());
    return withFingerprint(withoutFingerprint, fingerprint(withoutFingerprint));
  }

  private HumanAuthority signedAuthority(ExecutionEnvelope envelope) {
    Instant approvedAt = NOW.minusSeconds(30);
    Instant expiresAt = NOW.plusSeconds(240);
    String scopeDigest = canonicalJson.digest(exactScope(envelope));
    HumanAuthority unsigned =
        new HumanAuthority(
            GameplayContracts.VERSION,
            envelope.authorityId(),
            "HUMAN",
            "test-operator",
            approvedAt,
            expiresAt,
            canonicalJson.digest(envelope),
            scopeDigest,
            true,
            "hmac-sha256:" + "0".repeat(64));
    return new HumanAuthority(
        unsigned.contractVersion(),
        unsigned.authorityId(),
        unsigned.actorType(),
        unsigned.approvedBy(),
        unsigned.approvedAt(),
        unsigned.expiresAt(),
        unsigned.envelopeDigest(),
        unsigned.scopeDigest(),
        unsigned.oneUse(),
        "hmac-sha256:" + hmac(unsigned));
  }

  private Map<String, Object> exactScope(ExecutionEnvelope envelope) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("contractVersion", envelope.contractVersion());
    scope.put("provider", envelope.provider());
    scope.put("modelVersion", envelope.modelVersion());
    scope.put("identities", envelope.identities().deepCopy());
    scope.put("taskKey", envelope.taskKey());
    scope.put("settings", envelope.settings().deepCopy());
    scope.put("budgets", envelope.budgets().deepCopy());
    scope.put("semanticActionDigest", envelope.semanticActionDigest());
    scope.put("expectedTransition", envelope.expectedTransition());
    scope.put("verifierId", envelope.verifierId());
    scope.put("deadline", envelope.deadline());
    scope.put("killSwitchScope", envelope.killSwitchScope());
    return Map.copyOf(scope);
  }

  private String hmac(HumanAuthority authority) {
    try {
      Map<String, Object> payload =
          Map.of(
              "contractVersion",
              authority.contractVersion(),
              "authorityId",
              authority.authorityId(),
              "actorType",
              authority.actorType(),
              "approvedBy",
              authority.approvedBy(),
              "approvedAt",
              authority.approvedAt(),
              "expiresAt",
              authority.expiresAt(),
              "envelopeDigest",
              authority.envelopeDigest(),
              "scopeDigest",
              authority.scopeDigest(),
              "oneUse",
              authority.oneUse());
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonicalJson.canonicalBytes(payload)));
    } catch (GeneralSecurityException exception) {
      throw new AssertionError(exception);
    }
  }

  private GatewayReceipt receipt(
      OrchestrationRequest request,
      String envelopeDigest,
      String phase,
      State state,
      Lease lease,
      boolean acknowledged,
      boolean reconciled) {
    return new GatewayReceipt(
        request.requestId(),
        envelopeDigest,
        phase,
        state,
        FailureCode.NONE,
        acknowledged,
        reconciled,
        lease,
        List.of("jpa-" + phase),
        NOW);
  }

  private String fingerprint(OrchestrationRequest request) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("contractVersion", AccountBuilderOrchestrationModels.VERSION);
    values.put("orchestrationId", request.orchestrationId());
    values.put("requestId", request.requestId());
    values.put("idempotencyKey", request.idempotencyKey());
    values.put("mode", request.mode());
    values.put("sourceType", request.sourceType());
    values.put("decision", request.decision());
    values.put("envelope", request.envelope());
    values.put("bindings", request.bindings());
    values.put("authority", request.authority());
    values.put("observation", request.observation());
    values.put("deadline", request.deadline());
    return canonicalJson.digest(values);
  }

  private OrchestrationRequest withFingerprint(OrchestrationRequest request, String fingerprint) {
    return new OrchestrationRequest(
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        fingerprint,
        request.mode(),
        request.sourceType(),
        request.decision(),
        request.envelope(),
        request.bindings(),
        request.authority(),
        request.observation(),
        request.deadline());
  }

  private String digest(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackages = "com.chromascape.web.gameplay.orchestration.accountbuilder")
  @Import(JsonConfiguration.class)
  static class JpaTestApplication {
    @Bean
    HumanGameplayAuthorityService authorityService(
        CanonicalGameplayJson canonicalJson,
        @Value("${gameplay.bootstrap.authority-hmac-secret}") String signingSecret) {
      return new HumanGameplayAuthorityService(canonicalJson, signingSecret);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
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
