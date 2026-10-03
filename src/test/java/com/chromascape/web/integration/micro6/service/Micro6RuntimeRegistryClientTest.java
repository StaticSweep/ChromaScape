package com.chromascape.web.integration.micro6.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.integration.micro6.service.Micro6RuntimeRegistryClient.Micro6RegistryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for endpoint-reference hardening (SC-584A): malformed references are rejected with
 * a structured reason code before any HTTP traffic, and valid references still pass validation.
 */
class Micro6RuntimeRegistryClientTest {
  private final ObjectMapper mapper = new ObjectMapper();
  // Unreachable port: any network attempt would surface as registry_unreachable, proving malformed
  // references are rejected before a request is ever sent.
  private final Micro6RuntimeRegistryClient client =
      new Micro6RuntimeRegistryClient(mapper, "http://127.0.0.1:1", "test-token", 1000);

  private static final List<String> MALFORMED_REFS =
      List.of(
          "dev-micro6:AA/BB",
          "/dev-micro6:AAAA",
          "dev-micro6:AA?mode=full",
          "dev-micro6:AA#frag",
          "dev-micro6:AA%2fBB",
          "dev-micro6:AA BB",
          "dev-micro6:AA\tBB",
          "dev-micro6:AA\nBB",
          "..",
          "../..",
          "dev-micro6:../BB",
          "dev-micro6:AA..BB");

  @Test
  void resolveRuntimeRejectsMalformedReferencesBeforeNetwork() {
    for (String ref : MALFORMED_REFS) {
      assertThatThrownBy(() -> client.resolveRuntime(ref))
          .as("ref=%s", ref)
          .isInstanceOf(Micro6RegistryException.class)
          .extracting(e -> ((Micro6RegistryException) e).reasonCode())
          .isEqualTo("endpoint_ref_invalid");
    }
  }

  @Test
  void fetchCatalogRejectsMalformedReferencesBeforeNetwork() {
    for (String ref : MALFORMED_REFS) {
      assertThatThrownBy(() -> client.fetchCatalog(ref, "lightweight"))
          .as("ref=%s", ref)
          .isInstanceOf(Micro6RegistryException.class)
          .extracting(e -> ((Micro6RegistryException) e).reasonCode())
          .isEqualTo("endpoint_ref_invalid");
    }
  }

  @Test
  void malformedReferencesAreNeverSentAsIs() {
    for (String ref : MALFORMED_REFS) {
      Micro6RegistryException e =
          org.assertj.core.api.Assertions.catchThrowableOfType(
              () -> client.resolveRuntime(ref), Micro6RegistryException.class);
      assertThat(e).as("ref=%s", ref).isNotNull();
      assertThat(e.getMessage()).isNotBlank();
      assertThat(e.reasonCode()).isEqualTo("endpoint_ref_invalid");
    }
  }

  @Test
  void validReferencePassesValidation() {
    // A valid opaque reference must not be rejected: the only failure here is the unreachable
    // registry, never endpoint validation.
    assertThatThrownBy(() -> client.resolveRuntime("dev-micro6:AAAA"))
        .isInstanceOf(Micro6RegistryException.class)
        .extracting(e -> ((Micro6RegistryException) e).reasonCode())
        .isEqualTo("registry_unreachable");
  }
}
