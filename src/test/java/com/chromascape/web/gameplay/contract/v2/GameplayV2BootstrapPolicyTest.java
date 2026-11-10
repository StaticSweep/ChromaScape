package com.chromascape.web.gameplay.contract.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The bootstrap policy digest is the value both {@code semanticObservation.policyDigest} and {@code
 * planningInput.policyDigest} must carry. It has to be independently recomputable from the declared
 * policy object, and its bounds have to sit inside the catalog's policy bounds, or the deterministic
 * filter rejects the whole bundle.
 */
class GameplayV2BootstrapPolicyTest {
  private final ObjectMapper objectMapper =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  @Test
  void digestIsTheCanonicalDigestOfTheDeclaredPolicyObject() {
    String expected =
        GameplayV2EligibilityFilter.canonicalDigest(
            objectMapper, GameplayV2BootstrapPolicy.document(objectMapper));

    assertThat(GameplayV2BootstrapPolicy.digest(objectMapper)).isEqualTo(expected);
    assertThat(expected).matches("sha256:[0-9a-f]{64}");
  }

  @Test
  void digestIsIndependentOfFieldOrderAndWhitespace() throws Exception {
    // The same policy written with different key order and spacing must hash identically: the
    // canonicalizer sorts keys and drops insignificant whitespace.
    String reordered =
        "{\"killSwitchScope\":\"RUN\",\"maxElapsedMs\":300000,\"maxAttempts\":1,"
            + "\"maxActions\":1,\"taskKey\":\"FISHING_TRAINING\","
            + "\"contractVersion\":\"sc-1429/v2\"}";

    assertThat(GameplayV2EligibilityFilter.canonicalDigest(objectMapper, objectMapper.readTree(reordered)))
        .isEqualTo(GameplayV2BootstrapPolicy.digest(objectMapper));
  }

  @Test
  void digestChangesWhenAnyDeclaredBoundChanges() {
    String baseline = GameplayV2BootstrapPolicy.digest(objectMapper);

    var widened = GameplayV2BootstrapPolicy.document(objectMapper);
    widened.put("maxActions", 2);

    assertThat(GameplayV2EligibilityFilter.canonicalDigest(objectMapper, widened))
        .isNotEqualTo(baseline);
  }

  @Test
  void declaredBoundsStayInsideTheCatalogPolicyBounds() {
    // The catalog's FISHING_TRAINING maxPolicyBounds are 3 actions, 3 attempts and 900000 ms.
    var policy = GameplayV2BootstrapPolicy.document(objectMapper);
    assertThat(policy.path("maxActions").asInt()).isBetween(1, 3);
    assertThat(policy.path("maxAttempts").asInt()).isBetween(1, 3);
    assertThat(policy.path("maxElapsedMs").asLong()).isBetween(1000L, 900000L);
    assertThat(policy.path("killSwitchScope").asText()).isEqualTo("RUN");
    assertThat(policy.path("taskKey").asText()).isEqualTo("FISHING_TRAINING");
    assertThat(policy.path("contractVersion").asText()).isEqualTo("sc-1429/v2");
  }

  @Test
  void digestMatchesAnIndependentSha256OfTheCanonicalJson() throws Exception {
    // Independent recomputation: sorted keys, no whitespace, UTF-8, sha256. This is the same rule
    // the operator tooling uses, so the server and the tool cannot drift.
    String canonical =
        "{\"contractVersion\":\"sc-1429/v2\",\"killSwitchScope\":\"RUN\",\"maxActions\":1,"
            + "\"maxAttempts\":1,\"maxElapsedMs\":300000,\"taskKey\":\"FISHING_TRAINING\"}";
    String expected =
        "sha256:"
            + HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));

    assertThat(GameplayV2BootstrapPolicy.digest(objectMapper)).isEqualTo(expected);
  }
}
