package com.chromascape.web.gameplay.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HumanGameplayAuthorityServiceTest {
  private static final String SECRET = "authority-test-secret-at-least-32-bytes";
  private ObjectMapper objectMapper;
  private CanonicalGameplayJson canonicalJson;
  private HumanGameplayAuthorityService service;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    canonicalJson = new CanonicalGameplayJson(objectMapper);
    service =
        new HumanGameplayAuthorityService(
            canonicalJson,
            Clock.fixed(Instant.parse("2026-08-30T12:01:00Z"), ZoneOffset.UTC),
            SECRET);
    fixtures =
        objectMapper.readTree(
            getClass()
                .getClassLoader()
                .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json"));
  }

  @Test
  void bindsOneUseAuthorityToTheExactEnvelopeAndScope() throws Exception {
    var envelopeNode = fixtures.path("executionEnvelope").deepCopy();
    ExecutionEnvelope draft = objectMapper.treeToValue(envelopeNode, ExecutionEnvelope.class);
    assertThat(canonicalJson.digest(service.exactScope(draft)))
        .isEqualTo(fixtures.at("/expectedDigests/approvedScope").asText());
    var pending =
        service.approveExactScope(
            "operator-17", service.exactScope(draft), Instant.parse("2026-08-30T12:10:00Z"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelopeNode)
        .put("authorityId", pending.authorityId());
    ExecutionEnvelope envelope = objectMapper.treeToValue(envelopeNode, ExecutionEnvelope.class);

    HumanAuthority authority = service.bindEnvelope(pending, envelope);

    assertThat(authority.actorType()).isEqualTo("HUMAN");
    assertThat(authority.oneUse()).isTrue();
    assertThat(authority.envelopeDigest()).isEqualTo(canonicalJson.digest(envelope));
    assertThat(authority.scopeDigest())
        .isEqualTo(canonicalJson.digest(service.exactScope(envelope)));
    assertThat(authority.signature()).isEqualTo(expectedSignature(authority));
    assertThatThrownBy(() -> service.bindEnvelope(pending, envelope))
        .isInstanceOf(HumanGameplayAuthorityService.AuthorityException.class)
        .hasMessageContaining("absent or already bound");
  }

  @Test
  void changedScopeAndOverlongAuthorityFailClosed() throws Exception {
    var envelopeNode = fixtures.path("executionEnvelope").deepCopy();
    ExecutionEnvelope draft = objectMapper.treeToValue(envelopeNode, ExecutionEnvelope.class);
    var pending =
        service.approveExactScope(
            "operator-17", service.exactScope(draft), Instant.parse("2026-08-30T12:10:00Z"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelopeNode)
        .put("authorityId", pending.authorityId());
    ExecutionEnvelope envelope = objectMapper.treeToValue(envelopeNode, ExecutionEnvelope.class);
    var changedEnvelopeNode = envelopeNode.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedEnvelopeNode).put("provider", "CODEX");
    ExecutionEnvelope changedEnvelope =
        objectMapper.treeToValue(changedEnvelopeNode, ExecutionEnvelope.class);

    assertThatThrownBy(() -> service.bindEnvelope(pending, changedEnvelope))
        .isInstanceOf(HumanGameplayAuthorityService.AuthorityException.class)
        .hasMessageContaining("changed the approved scope");
    assertThatThrownBy(
            () ->
                service.approveExactScope(
                    "operator-17",
                    service.exactScope(envelope),
                    Instant.parse("2026-08-30T12:20:00Z")))
        .isInstanceOf(HumanGameplayAuthorityService.AuthorityException.class);
  }

  private String expectedSignature(HumanAuthority authority) throws Exception {
    Map<String, Object> unsigned = new LinkedHashMap<>();
    unsigned.put("contractVersion", authority.contractVersion());
    unsigned.put("authorityId", authority.authorityId());
    unsigned.put("actorType", authority.actorType());
    unsigned.put("approvedBy", authority.approvedBy());
    unsigned.put("approvedAt", authority.approvedAt());
    unsigned.put("expiresAt", authority.expiresAt());
    unsigned.put("envelopeDigest", authority.envelopeDigest());
    unsigned.put("scopeDigest", authority.scopeDigest());
    unsigned.put("oneUse", authority.oneUse());
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "hmac-sha256:"
        + java.util.HexFormat.of().formatHex(mac.doFinal(canonicalJson.canonicalBytes(unsigned)));
  }
}
