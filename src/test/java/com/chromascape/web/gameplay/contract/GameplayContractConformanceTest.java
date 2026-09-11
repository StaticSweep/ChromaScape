package com.chromascape.web.gameplay.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.provider.ClaudeGameplayProviderAdapter;
import com.chromascape.web.gameplay.provider.CodexGameplayProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameplayContractConformanceTest {
  private ObjectMapper objectMapper;
  private GameplayContractValidator validator;
  private CanonicalGameplayJson canonicalJson;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayContractValidator(objectMapper);
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  @Test
  void canonicalFixturesValidateAndPinDigests() throws Exception {
    validator.validate("observation", fixtures.path("observation"));
    validator.validate("semanticAction", fixtures.path("semanticAction"));
    validator.validate("providerProposal", fixtures.path("claudeProposal"));
    validator.validate("providerProposal", fixtures.path("codexProposal"));
    validator.validate("providerProposal", fixtures.path("abstainProposal"));

    ExecutionEnvelope envelope =
        objectMapper.treeToValue(fixtures.path("executionEnvelope"), ExecutionEnvelope.class);
    HumanAuthority authority =
        objectMapper.treeToValue(fixtures.path("authority"), HumanAuthority.class);
    validator.validate("executionEnvelope", envelope);
    validator.validate("authority", authority);

    assertThat(canonicalJson.digest(fixtures.path("semanticAction")))
        .isEqualTo(fixtures.at("/expectedDigests/semanticAction").asText());
    assertThat(canonicalJson.digest(envelope))
        .isEqualTo(fixtures.at("/expectedDigests/executionEnvelope").asText());
    assertThat(canonicalJson.digest(fixtures.path("approvedScope")))
        .isEqualTo(fixtures.at("/expectedDigests/approvedScope").asText());
    assertThat(fixtures.path("cases")).hasSize(23);
  }

  @Test
  void claudeAndCodexUseTheSameClosedDownstreamShape() {
    var claude =
        new ClaudeGameplayProviderAdapter(validator)
            .adapt("claude-fixture-v1", fixtures.path("claudeProposal"));
    var codex =
        new CodexGameplayProviderAdapter(validator)
            .adapt("codex-fixture-v1", fixtures.path("codexProposal"));

    assertThat(claude.semanticAction()).isEqualTo(codex.semanticAction());
    assertThat(claude.decisionId()).isEqualTo(codex.decisionId());
    assertThat(claude.policyConfidence()).isEqualTo(codex.policyConfidence());
    assertThat(claude.provider()).isNotEqualTo(codex.provider());
  }

  @Test
  void securitySensitiveObjectsRejectUnknownOrUnsupportedFields() {
    JsonNode proposal = fixtures.path("claudeProposal").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) proposal).put("rawMouseHandle", "forbidden");
    assertThatThrownBy(() -> validator.validate("providerProposal", proposal))
        .isInstanceOf(GameplayContractValidator.GameplayContractViolation.class);

    JsonNode action = fixtures.path("semanticAction").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) action).put("kind", "RAW_CLICK");
    assertThatThrownBy(() -> validator.validate("semanticAction", action))
        .isInstanceOf(GameplayContractValidator.GameplayContractViolation.class);
  }

  @Test
  void canonicalResourcesHaveFrozenByteDigests() throws Exception {
    assertThat(resourceSha256("contracts/gameplay/sc-1429-gameplay-contract-v1.schema.json"))
        .isEqualTo("010cba11ada57f328b43968be894f4875edd2ef8ee582c6eb5a924153e461469");
    assertThat(resourceSha256("contracts/gameplay/sc-1429-golden-fixtures-v1.json"))
        .isEqualTo("c55ad78af5b6367803bc7098e0ef0a03e03d8efff6465bd4eae7829aa743c19c");
  }

  private String resourceSha256(String resource) throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
    }
  }
}
