package com.chromascape.web.gameplay.contract.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameplayV2ContractConformanceTest {
  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayV2ContractValidator(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  @Test
  void allValidFixtureObjectsAcceptSchemaAndSemanticRules() {
    GameplayV2ContractValidator.GameplayV2ContractViolation rejected =
        capture(
            () ->
                validator.validateWithRules(
                    "immutableTaskCatalogSnapshot", fixtures.path("catalogSnapshot")));
    assertThat(rejected).isNull();
    validate("capabilitySnapshot");
    validate("semanticObservation");
    validate("planningInput");
    validate("eligibilityResult");
    validate("claudeDecision", "providerDecision");
    validate("codexDecision", "providerDecision");
    validate("abstainDecision", "providerDecision");
    validate("goalPlan");
    validator.validateGoalPlanWithCatalog(
        fixtures.path("goalPlan"), fixtures.path("catalogSnapshot"));
    validator.validateProviderDecisionWithCatalog(
        fixtures.path("claudeDecision"), fixtures.path("catalogSnapshot"));
    validator.validateProviderDecisionWithCatalog(
        fixtures.path("codexDecision"), fixtures.path("catalogSnapshot"));
    validate("executionEnvelope");
    validate("authority");
    validate("semanticAction");
    validate("approvedScope");
    validate("toolOperations.catalog", "toolOperation");
    validate("toolOperations.reserve", "toolOperation");
    validate("toolOperations.start", "toolOperation");
    validate("toolOperations.status", "toolOperation");
    validate("toolOperations.stop", "toolOperation");
    validate("toolOperations.observe", "toolOperation");
    validate("lifecycleReceipts.reservation", "lifecycleReceipt");
    validate("lifecycleReceipts.delivery", "lifecycleReceipt");
    validate("lifecycleReceipts.confirmed", "lifecycleReceipt");
    validate("lifecycleReceipts.refuted", "lifecycleReceipt");
    validate("lifecycleReceipts.unverifiable", "lifecycleReceipt");
    validate("lifecycleReceipts.uncertain", "lifecycleReceipt");
    validate("lifecycleReceipts.stopped", "lifecycleReceipt");
  }

  @Test
  void integerValidationUsesTheNumericValue() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode envelope =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            fixtures.path("executionEnvelope").deepCopy();
    envelope.set("expectedObservationRevision", objectMapper.readTree("1E-1"));
    assertThatThrownBy(() -> validator.validateWithRules("executionEnvelope", envelope))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("INVALID_EXECUTION_ENVELOPE");

    envelope.set("expectedObservationRevision", objectMapper.readTree("1e0"));
    validator.validateWithRules("executionEnvelope", envelope);
    envelope.set("expectedObservationRevision", objectMapper.readTree("1.0"));
    validator.validateWithRules("executionEnvelope", envelope);
  }

  @Test
  void lowercaseRfc3339SeparatorsRemainValid() {
    com.fasterxml.jackson.databind.node.ObjectNode envelope =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            fixtures.path("executionEnvelope").deepCopy();
    envelope.put("deadline", "2026-09-03t12:10:00z");
    validator.validateWithRules("executionEnvelope", envelope);
  }

  @Test
  void capabilitySnapshotDigestIsIndependentlyProvable() {
    assertThat(GameplayV2EligibilityFilter.capabilitySnapshotDigest(objectMapper))
        .isEqualTo(fixtures.at("/expectedDigests/capabilitySnapshot").asText());
    assertThat(digest("capabilitySnapshot"))
        .isEqualTo(fixtures.at("/expectedDigests/capabilitySnapshot").asText());
    assertThat(fixtures.path("catalogSnapshot").path("capabilitySnapshotDigest").asText())
        .isEqualTo(fixtures.at("/expectedDigests/capabilitySnapshot").asText());
    assertThat(fixtures.path("semanticObservation").path("capabilitySnapshotDigest").asText())
        .isEqualTo(fixtures.at("/expectedDigests/capabilitySnapshot").asText());
  }

  @Test
  void canonicalFixturesPinExpectedDigests() {
    assertThat(digestWithout("catalogSnapshot", "catalogDigest"))
        .isEqualTo(fixtures.at("/expectedDigests/catalogSnapshot").asText());
    assertThat(digest("semanticObservation"))
        .isEqualTo(fixtures.at("/expectedDigests/semanticObservation").asText());
    assertThat(digest("planningInput"))
        .isEqualTo(fixtures.at("/expectedDigests/planningInput").asText());
    assertThat(digest("eligibilityResult"))
        .isEqualTo(fixtures.at("/expectedDigests/eligibilityResult").asText());
    assertThat(digestWithout("goalPlan", "goalPlanDigest"))
        .isEqualTo(fixtures.at("/expectedDigests/goalPlan").asText());
    assertThat(digest("semanticAction"))
        .isEqualTo(fixtures.at("/expectedDigests/semanticAction").asText());
    assertThat(digest("executionEnvelope"))
        .isEqualTo(fixtures.at("/expectedDigests/executionEnvelope").asText());
    assertThat(digest("approvedScope"))
        .isEqualTo(fixtures.at("/expectedDigests/approvedScope").asText());
    assertThat(digest("capabilitySnapshot"))
        .isEqualTo(fixtures.at("/expectedDigests/capabilitySnapshot").asText());
  }

  @Test
  void crossObjectDigestBindingsHold() {
    assertThat(fixtures.path("executionEnvelope").path("catalogDigest").asText())
        .isEqualTo(fixtures.path("catalogSnapshot").path("catalogDigest").asText());
    assertThat(fixtures.path("planningInput").path("observationDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("semanticObservation").asText());
    assertThat(fixtures.path("eligibilityResult").path("observationDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("semanticObservation").asText());
    assertThat(fixtures.path("executionEnvelope").path("planningInputDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("planningInput").asText());
    assertThat(fixtures.path("executionEnvelope").path("eligibilityResultDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("eligibilityResult").asText());
    assertThat(fixtures.path("claudeDecision").path("eligibleInputDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("eligibilityResult").asText());
    assertThat(fixtures.path("codexDecision").path("eligibleInputDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("eligibilityResult").asText());
    assertThat(fixtures.path("executionEnvelope").path("promptId").asText())
        .isEqualTo(fixtures.path("claudeDecision").path("promptId").asText());
    assertThat(fixtures.path("semanticAction").path("decisionId").asText())
        .isEqualTo(fixtures.path("claudeDecision").path("decisionId").asText());
    assertThat(fixtures.path("authority").path("envelopeDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("executionEnvelope").asText());
    assertThat(fixtures.path("authority").path("scopeDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("approvedScope").asText());
    assertThat(fixtures.path("executionEnvelope").path("semanticActionDigest").asText())
        .isEqualTo(fixtures.path("expectedDigests").path("semanticAction").asText());
    // Schema digest must equal the independently derived resource hash, not merely
    // the envelope's self-reported copy.
    assertThat(fixtures.path("executionEnvelope").path("schemaDigest").asText())
        .isEqualTo(GameplayV2EnvelopeRules.canonicalSchemaDigest());
    assertThat(
            GameplayV2EnvelopeRules.checkFullBindings(
                fixtures.path("executionEnvelope"),
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                fixtures.path("planningInput"),
                fixtures.path("eligibilityResult"),
                fixtures.path("semanticAction"),
                fixtures.path("goalPlan"),
                fixtures.path("claudeDecision"),
                GameplayV2EnvelopeRules.canonicalSchemaDigest(),
                java.time.Instant.parse("2026-09-03T12:04:00Z")))
        .isNull();
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                fixtures.path("authority"),
                fixtures.path("approvedScope"),
                fixtures.path("executionEnvelope"),
                fixtures.path("expectedDigests").path("executionEnvelope").asText(),
                fixtures.path("executionEnvelope").path("authorityId").asText(),
                fixtures.path("testSigningSecret").asText(),
                java.time.Instant.parse("2026-09-03T12:04:00Z"),
                java.util.Set.of()))
        .isNull();
  }

  @Test
  void canonicalV2ResourcesHaveFrozenByteDigests() throws Exception {
    assertThat(resourceSha256("contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json"))
        .isEqualTo(V2_SCHEMA_SHA256);
    assertThat(resourceSha256("contracts/gameplay/sc-1429-golden-fixtures-v2.json"))
        .isEqualTo(V2_FIXTURES_SHA256);
  }

  @Test
  void mutationCaseCorpusIsExecutable() {
    List<JsonNode> cases = new java.util.ArrayList<>();
    fixtures.path("cases").forEach(cases::add);
    assertThat(cases.size()).isEqualTo(85);
    assertThat(cases).allSatisfy(this::isExecutableCase);
  }

  private boolean isExecutableCase(JsonNode testCase) {
    return testCase.path("id").isTextual()
        && testCase.path("kind").isTextual()
        && testCase.path("source").isTextual()
        && testCase.path("mutation").isObject()
        && testCase.path("mutation").path("op").isTextual()
        && testCase.path("mutation").path("path").isTextual()
        && testCase.path("expected").isTextual();
  }

  private void validate(String pointer) {
    validate(
        pointer, pointer.contains(".") ? pointer.substring(pointer.indexOf('.') + 1) : pointer);
  }

  private void validate(String pointer, String definition) {
    JsonNode value = fixtures.at("/" + pointer.replace('.', '/'));
    validator.validateWithRules(definition, value);
  }

  private String digest(String pointer) {
    return GameplayV2EligibilityFilter.canonicalDigest(objectMapper, fixtures.at("/" + pointer));
  }

  private String digestWithout(String pointer, String omittedField) {
    JsonNode value = fixtures.at("/" + pointer).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) value).remove(omittedField);
    return GameplayV2EligibilityFilter.canonicalDigest(objectMapper, value);
  }

  private GameplayV2ContractValidator.GameplayV2ContractViolation capture(Runnable runnable) {
    try {
      runnable.run();
      return null;
    } catch (GameplayV2ContractValidator.GameplayV2ContractViolation violation) {
      return violation;
    }
  }

  static final String V2_SCHEMA_SHA256 =
      "1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b";
  static final String V2_FIXTURES_SHA256 =
      "96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549";

  private String resourceSha256(String resource) throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
    }
  }
}
