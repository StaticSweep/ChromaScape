package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PostgreSQL/JPA proof that normalized evidence survives a fresh persistence context. */
@SpringBootTest(
    classes = JpaAccountBuilderOrchestrationStoreTest.JpaTestBootstrap.class,
    properties = {
      "spring.flyway.target=32",
      "spring.jpa.hibernate.ddl-auto=validate",
      "stabilization.auth.token=sc1431-read-token",
      "stabilization.auth.write-token=sc1431-write-token",
      "gameplay.bootstrap.authority-hmac-secret=sc1431-test-authority-secret-32-bytes"
    })
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles({"test", "sc1431-jpa"})
class JpaAccountBuilderOrchestrationStoreTest {
  private static final Instant NOW = Instant.parse("2026-08-31T12:05:00Z");
  private static final String SECRET = "sc1431-test-authority-secret-32-bytes";
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CanonicalGameplayJson canonicalJson;
  @Autowired private JpaAccountBuilderOrchestrationStore store;

  @DynamicPropertySource
  static void isolatedPostgres(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
  }

  @Test
  void sc1431FixturesAreTestConfigurationsNotSpringBootApplications() {
    assertThat(JpaTestApplication.class.getAnnotation(TestConfiguration.class)).isNotNull();
    assertThat(JpaTestApplication.class.getAnnotation(SpringBootConfiguration.class)).isNull();
    assertThat(
            AccountBuilderOrchestrationPostgresConcurrencyTest.TestApplication.class.getAnnotation(
                TestConfiguration.class))
        .isNotNull();
    assertThat(
            AccountBuilderOrchestrationPostgresConcurrencyTest.TestApplication.class.getAnnotation(
                SpringBootConfiguration.class))
        .isNull();
  }

  @Test
  void normalizedRowsReconstructAfterPersistenceContextClear() {
    OrchestrationRequest request = request("jpa-reconstruct");
    String envelopeDigest = canonicalJson.digest(request.envelope());

    assertThat(
            store
                .claim(request, request.requestFingerprint(), envelopeDigest, budget(request))
                .created())
        .isTrue();
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
    store.consumeAuthority(request.orchestrationId(), request.authority(), envelopeDigest, request);
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

    AccountBuilderOrchestrationStore.NormalizedAggregate aggregate =
        store.readAggregate(request.orchestrationId());
    assertThat(aggregate.view().state()).isEqualTo(State.CONFIRMED);
    assertThat(aggregate.view().receipts()).hasSize(3);
    assertThat(aggregate.authority()).isNotNull();
    assertThat(aggregate.lease()).isEqualTo(lease);
    assertThat(aggregate.terminalResult()).isNotNull();
    assertThat(count("ab_snapshot_binding", request.orchestrationId())).isEqualTo(1);
    assertThat(count("ab_authority_consumption", request.orchestrationId())).isEqualTo(1);
    assertThat(count("ab_orchestration_lease", request.orchestrationId())).isEqualTo(1);
    assertThat(count("ab_orchestration_receipt", request.orchestrationId())).isEqualTo(3);
    assertThat(count("ab_orchestration_event", request.orchestrationId())).isEqualTo(8);
    assertThat(count("ab_orchestration_terminal_result", request.orchestrationId())).isEqualTo(1);

    String persistedRequest =
        jdbcTemplate.queryForObject(
            "select request_json from ab_orchestration where orchestration_id=?",
            String.class,
            request.orchestrationId());
    assertThat(persistedRequest).doesNotContain("secret-setting");
    assertThat(persistedRequest).doesNotContain("signature");
  }

  @Test
  void changedFingerprintCannotReuseDurableIdentity() {
    OrchestrationRequest request = request("jpa-conflict");
    String envelopeDigest = canonicalJson.digest(request.envelope());
    store.claim(request, request.requestFingerprint(), envelopeDigest, budget(request));
    OrchestrationRequest changed = withFingerprint(request, digest("changed-fingerprint"));

    assertThatThrownBy(
            () ->
                store.claim(changed, changed.requestFingerprint(), envelopeDigest, budget(request)))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.IDEMPOTENCY_CONFLICT);
    assertThat(store.find(request.orchestrationId())).isPresent();
  }

  private long count(String table, String orchestrationId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where orchestration_id=?", Long.class, orchestrationId);
  }

  @Test
  void allZeroHmacIsRejectedBeforeAuthorityOwnership() {
    OrchestrationRequest request = request("jpa-authority-zero-hmac");
    HumanAuthority invalid =
        copyAuthority(request.authority(), "hmac-sha256:" + "0".repeat(64), null, null, null);

    assertAuthorityRejected(request, invalid, FailureCode.AUTHORITY_INVALID);
  }

  @Test
  void correctlyShapedButWrongHmacIsRejectedBeforeAuthorityOwnership() {
    OrchestrationRequest request = request("jpa-authority-wrong-hmac");
    HumanAuthority invalid =
        copyAuthority(request.authority(), "hmac-sha256:" + "1".repeat(64), null, null, null);

    assertAuthorityRejected(request, invalid, FailureCode.AUTHORITY_INVALID);
  }

  @Test
  void authorityBoundToDifferentEnvelopeIsRejected() {
    OrchestrationRequest request = request("jpa-authority-different-envelope");
    HumanAuthority invalid =
        copyAuthority(
            request.authority(),
            null,
            digest("different-envelope"),
            request.authority().scopeDigest(),
            null);

    assertAuthorityRejected(request, invalid, FailureCode.AUTHORITY_INVALID);
  }

  @Test
  void changedApprovedScopeIsRejectedBeforeAuthorityOwnership() {
    OrchestrationRequest request = request("jpa-authority-changed-scope");
    HumanAuthority invalid =
        copyAuthority(request.authority(), null, null, digest("changed-approved-scope"), null);

    assertAuthorityRejected(request, invalid, FailureCode.AUTHORITY_INVALID);
  }

  @Test
  void expiredAuthorityIsRejectedBeforeAuthorityOwnership() {
    OrchestrationRequest request = request("jpa-authority-expired");
    HumanAuthority invalid =
        copyAuthority(request.authority(), null, null, null, NOW.minusSeconds(1));

    assertAuthorityRejected(request, invalid, FailureCode.AUTHORITY_INVALID);
  }

  @Test
  void cryptographicallyValidDuplicateAuthorityIdIsRejectedAcrossOrchestrations() {
    OrchestrationRequest owner = request("jpa-authority-owner");
    OrchestrationRequest contender =
        withAuthorityId(request("jpa-authority-contender"), owner.authority().authorityId());
    String ownerEnvelopeDigest = canonicalJson.digest(owner.envelope());
    String contenderEnvelopeDigest = canonicalJson.digest(contender.envelope());
    claimAndValidate(owner);
    claimAndValidate(contender);

    store.consumeAuthority(owner.orchestrationId(), owner.authority(), ownerEnvelopeDigest, owner);

    assertThatThrownBy(
            () ->
                store.consumeAuthority(
                    contender.orchestrationId(),
                    contender.authority(),
                    contenderEnvelopeDigest,
                    contender))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(FailureCode.AUTHORITY_ALREADY_USED);
    assertThat(
            count("ab_authority_consumption", owner.orchestrationId())
                + count("ab_authority_consumption", contender.orchestrationId()))
        .isEqualTo(1);
  }

  private void assertAuthorityRejected(
      OrchestrationRequest request, HumanAuthority authority, FailureCode failureCode) {
    claimAndValidate(request);
    assertThatThrownBy(
            () ->
                store.consumeAuthority(
                    request.orchestrationId(),
                    authority,
                    canonicalJson.digest(request.envelope()),
                    request))
        .isInstanceOf(AccountBuilderOrchestrationException.class)
        .extracting("failureCode")
        .isEqualTo(failureCode);
    assertThat(count("ab_authority_consumption", request.orchestrationId())).isZero();
  }

  private void claimAndValidate(OrchestrationRequest request) {
    assertThat(
            store
                .claim(
                    request,
                    request.requestFingerprint(),
                    canonicalJson.digest(request.envelope()),
                    budget(request))
                .created())
        .isTrue();
    store.transition(
        request.orchestrationId(), State.VALIDATED, FailureCode.NONE, null, null, false, false);
  }

  private HumanAuthority copyAuthority(
      HumanAuthority source,
      String signature,
      String envelopeDigest,
      String scopeDigest,
      Instant expiresAt) {
    return new HumanAuthority(
        source.contractVersion(),
        source.authorityId(),
        source.actorType(),
        source.approvedBy(),
        source.approvedAt(),
        expiresAt == null ? source.expiresAt() : expiresAt,
        envelopeDigest == null ? source.envelopeDigest() : envelopeDigest,
        scopeDigest == null ? source.scopeDigest() : scopeDigest,
        source.oneUse(),
        signature == null ? source.signature() : signature);
  }

  private static AccountBuilderBudgetPolicy.OrchestrationBudget budget(
      OrchestrationRequest request) {
    return new AccountBuilderBudgetPolicy.OrchestrationBudget(
        5, 2, 60_000, 0, 0, NOW, request.deadline());
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
            1,
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
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

  private OrchestrationRequest withAuthorityId(OrchestrationRequest request, String authorityId) {
    ExecutionEnvelope current = request.envelope();
    ExecutionEnvelope envelope =
        new ExecutionEnvelope(
            current.contractVersion(),
            current.provider(),
            current.modelVersion(),
            current.decisionId(),
            current.requestId(),
            current.idempotencyKey(),
            current.goalPlanDigest(),
            current.taskSkillDigests(),
            current.taskKey(),
            current.settings(),
            current.identities(),
            current.catalogDigest(),
            current.schemaDigest(),
            current.policyDigest(),
            current.knowledgeDigest(),
            current.semanticActionDigest(),
            current.expectedTransition(),
            current.verifierId(),
            current.buildId(),
            current.expectedObservationRevision(),
            current.expectedTaskRevision(),
            authorityId,
            current.leaseGeneration(),
            current.fence(),
            current.budgets(),
            current.deadline(),
            current.killSwitchScope());
    OrchestrationRequest changed =
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            null,
            request.mode(),
            request.sourceType(),
            request.decision(),
            envelope,
            request.bindings(),
            signedAuthority(envelope),
            request.observation(),
            request.deadline());
    return withFingerprint(changed, fingerprint(changed));
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
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("sc1431-jpa")
  @Import(JpaTestApplication.class)
  static class JpaTestBootstrap {}

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("sc1431-jpa")
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

    @Bean
    JpaAccountBuilderOrchestrationStore orchestrationStore(
        EntityManager entityManager,
        ObjectMapper objectMapper,
        CanonicalGameplayJson canonicalJson,
        HumanGameplayAuthorityService authorityService) {
      return new JpaAccountBuilderOrchestrationStore(
          entityManager, objectMapper, canonicalJson, CLOCK, authorityService);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("sc1431-jpa")
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
