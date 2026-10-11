package com.chromascape.web.debug.buildergoal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import org.junit.jupiter.api.*;

class BuilderGoalEndpointAuthorityTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer registry;
  private ObjectNode runtime;
  private ObjectNode identity;
  private BuilderGoalEndpointAuthority authority;
  private final String token = "0123456789abcdef0123456789abcdef";

  @BeforeEach
  void setup() throws Exception {
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    runtime =
        mapper
            .createObjectNode()
            .put("machine_uuid", "machine")
            .put("executor_instance_id", "executor")
            .put("session_id", "session")
            .put("account_hash", "account")
            .put("endpoint_ref", "ref")
            .put("runtime_state", "ONLINE")
            .put("last_heartbeat_at", Instant.now().toString())
            .put("expires_at", Instant.now().plusSeconds(60).toString())
            .put("advertised_base_url", "http://127.0.0.1:8098");
    identity =
        mapper
            .createObjectNode()
            .put("machine_uuid", "machine")
            .put("executor_instance_id", "executor")
            .put("launcher_session_id", "session")
            .put("account_hash", "account")
            .put("endpoint_ref", "ref")
            .put("advertised_base_url", "http://127.0.0.1:22");
    runtime
        .put("endpoint_contract_version", 1)
        .put("issuer", "leeuwbotapi")
        .put("audience", "chromascape")
        .put("client_type", "micro6")
        .put("environment", "DEV")
        .put("allocation_id", "allocation")
        .put("fencing_generation", 1)
        .put("artifact_sha256", "sha256:" + "a".repeat(64))
        .put("issued_at", Instant.now().toString());
    identity
        .put("allocation_id", "allocation")
        .put("fencing_generation", 1)
        .put("artifact_sha256", "sha256:" + "a".repeat(64));
    registry.createContext(
        "/api/v1/launcher/runtimes/micro6/ref",
        exchange -> {
          if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, 2);
            exchange.getResponseBody().write("{}".getBytes());
            exchange.close();
            return;
          }
          byte[] bytes = mapper.writeValueAsBytes(runtime);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    registry.start();
    authority =
        new BuilderGoalEndpointAuthority(
            mapper,
            "http://127.0.0.1:" + registry.getAddress().getPort(),
            token,
            "DEV",
            "http://127.0.0.1:8098",
            "http://127.0.0.1:8098",
            true);
  }

  @AfterEach
  void stop() {
    registry.stop(0);
  }

  @Test
  void repeatedRevalidationReleasesProbeCapacity() {
    for (int i = 0; i < 20; i++) assertEquals("http://127.0.0.1:8098", authority.resolve(identity));
  }

  @Test
  void forgedAdvertisedUrlNeverChangesAttestedRoute() {
    assertEquals("http://127.0.0.1:8098", authority.resolve(identity));
  }

  @Test
  void unknownPortIsNotAuthority() {
    runtime.put("advertised_base_url", "http://127.0.0.1:22");
    assertNull(authority.resolve(identity));
  }

  @Test
  void staleRuntimeFailsClosed() {
    runtime.put("expires_at", Instant.now().minusSeconds(1).toString());
    assertNull(authority.resolve(identity));
  }

  @Test
  void changedLauncherSessionFencesOldReference() {
    runtime.put("session_id", "replacement");
    assertNull(authority.resolve(identity));
  }

  @Test
  void changedExecutorFencesOldReference() {
    runtime.put("executor_instance_id", "replacement");
    assertNull(authority.resolve(identity));
  }

  @Test
  void allocationGenerationAndArtifactDriftFenceOldReference() {
    runtime.put("fencing_generation", 2);
    assertNull(authority.resolve(identity));
    runtime.put("fencing_generation", 1).put("artifact_sha256", "sha256:" + "b".repeat(64));
    assertNull(authority.resolve(identity));
  }

  @Test
  void wrongEnvironmentOrAudienceCannotAuthorizeRoute() {
    runtime.put("environment", "PRODUCTION");
    assertNull(authority.resolve(identity));
    runtime.put("environment", "DEV").put("audience", "other");
    assertNull(authority.resolve(identity));
  }

  @Test
  void missingAllocationProofCannotAuthorizeRoute() {
    runtime.remove("allocation_id");
    assertNull(authority.resolve(identity));
  }

  @Test
  void wrongAccountAndMachineFailClosed() {
    identity.put("account_hash", "wrong");
    assertNull(authority.resolve(identity));
    identity.put("account_hash", "account").put("machine_uuid", "wrong");
    assertNull(authority.resolve(identity));
  }

  @Test
  void registryOutageDoesNotFallBackToAdvertisedRoute() {
    registry.stop(0);
    assertNull(authority.resolve(identity));
  }

  @Test
  void rawUrlAloneCannotAuthorizeRoute() {
    identity.remove("endpoint_ref");
    assertNull(authority.resolve(identity));
  }

  @Test
  void explicitDevRouteIgnoresCallerUrl() {
    identity.remove("endpoint_ref");
    identity.put("route_source", "DEV_STATIC_ROUTE");
    assertEquals("http://127.0.0.1:8098", authority.resolve(identity));
  }

  @Test
  void addressPolicyRejectsCredentialsMetadataDnsAndWildcard() {
    for (String url :
        java.util.List.of(
            "http://user:secret@127.0.0.1:8098",
            "http://169.254.169.254:8098",
            "http://0.0.0.0:8098",
            "http://executor.example:8098",
            "http://127.0.0.1:8098?secret=x"))
      assertNull(BuilderGoalEndpointAuthority.normalize(url), url);
    assertNotNull(
        java.net.URI.create(BuilderGoalEndpointAuthority.normalize("http://[::1]:8098")).getHost());
  }

  @org.junit.jupiter.api.Test
  void alternativeAddressEncodingsCannotBypassOriginPolicy() {
    org.junit.jupiter.api.Assertions.assertNull(
        BuilderGoalEndpointAuthority.normalize("http://2130706433:8098"));
    org.junit.jupiter.api.Assertions.assertNull(
        BuilderGoalEndpointAuthority.normalize("http://127.1:8098"));
    org.junit.jupiter.api.Assertions.assertNull(
        BuilderGoalEndpointAuthority.normalize("http://0177.0.0.1:8098"));
    org.junit.jupiter.api.Assertions.assertNull(
        BuilderGoalEndpointAuthority.normalize("http://127.000.0.1:8098"));
    org.junit.jupiter.api.Assertions.assertNull(
        BuilderGoalEndpointAuthority.normalize("http://[::1%25lo]:8098"));
  }
}
