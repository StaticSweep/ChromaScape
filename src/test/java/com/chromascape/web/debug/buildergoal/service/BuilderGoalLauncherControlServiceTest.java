package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP-backed regression tests for {@link BuilderGoalLauncherControlService}.
 *
 * <p>Uses {@link com.sun.net.httpserver.HttpServer} on an ephemeral port, mirroring the existing
 * MCP transport test pattern, so launcher parsing and null-tolerant metadata handling are
 * exercised against a real HTTP round trip.
 */
class BuilderGoalLauncherControlServiceTest {

  private HttpServer testServer;
  private int testServerPort;
  private BuilderGoalLauncherControlService service;

  private static final String LOCAL_MACHINE_UUID = "1d391d65-6c97-5b77-b676-964f157e8c73";

  @BeforeEach
  void setUp() throws IOException {
    testServer = HttpServer.create(new InetSocketAddress(0), 0);
    testServerPort = testServer.getAddress().getPort();
    service =
        new BuilderGoalLauncherControlService(
            new ObjectMapper(),
            "http://localhost:" + testServerPort,
            "secret-token",
            "",
            LOCAL_MACHINE_UUID,
            "/api/v1/accounts",
            "/api/v1/launcher/machines",
            "/api/v1/launcher/commands/start",
            "/api/v1/launcher/commands",
            5000L,
            500L,
            10000L);
  }

  @AfterEach
  void tearDown() {
    if (testServer != null) {
      testServer.stop(0);
    }
  }

  private void serve(String path, int status, String body) {
    testServer.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    testServer.start();
  }

  @Test
  void listMachineSummaries_preservesNullRuntimeMetadataAndIsImmutable() {
    serve(
        "/api/v1/launcher/machines",
        200,
        """
        {
          "machines": [
            {
              "machine_uuid": "1d391d65-6c97-5b77-b676-964f157e8c73",
              "display_name": "CZC8518C27 (Beheerder)",
              "hostname": "CZC8518C27",
              "status": "online",
              "enabled": true,
              "online": true,
              "runtime": null
            }
          ]
        }
        """);

    List<Map<String, Object>> summaries = service.listMachineSummaries();

    assertThat(summaries).hasSize(1);
    Map<String, Object> summary = summaries.get(0);
    assertThat(summary.get("machineUuid")).isEqualTo("1d391d65-6c97-5b77-b676-964f157e8c73");
    assertThat(summary.get("displayName")).isEqualTo("CZC8518C27 (Beheerder)");
    assertThat(summary.get("enabled")).isEqualTo(true);
    assertThat(summary.get("online")).isEqualTo(true);
    assertThat(summary.get("builderGoalRunnable")).isEqualTo(true);
    assertThat(summary.get("localMachine")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) summary.get("metadata");
    assertThat(metadata).containsKey("runtime");
    assertThat(metadata.get("runtime")).isNull();
    assertThat(metadata.get("hostname")).isEqualTo("CZC8518C27");

    BuilderGoalLauncherControlService.LauncherMachine machine =
        service.findMachineByUuid("1d391d65-6c97-5b77-b676-964f157e8c73").orElseThrow();
    assertThatThrownBy(() -> machine.metadata().put("runtime", "mutated"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void listMachines_marksOnlyTheEnabledOnlineLocalMachineAsBuilderGoalRunnable() {
    serve(
        "/api/v1/launcher/machines",
        200,
        """
        {
          "machines": [
            {
              "machine_uuid": "1d391d65-6c97-5b77-b676-964f157e8c73",
              "display_name": "CZC8518C27 (Beheerder)",
              "hostname": "CZC8518C27",
              "status": "online",
              "enabled": true,
              "online": true,
              "runtime": null
            },
            {
              "machine_uuid": "809b43ec-0000-0000-0000-000000000000",
              "display_name": "jew (jomar)",
              "hostname": "jew",
              "status": "online",
              "enabled": true,
              "online": true,
              "runtime": null
            }
          ]
        }
        """);

    List<BuilderGoalLauncherControlService.LauncherMachine> machines = service.listMachines();

    assertThat(machines).hasSize(2);
    BuilderGoalLauncherControlService.LauncherMachine local = machines.get(0);
    BuilderGoalLauncherControlService.LauncherMachine remote = machines.get(1);
    assertThat(service.isLocalMachine(local)).isTrue();
    assertThat(service.isLocalMachine(remote)).isFalse();
    assertThat(service.isBuilderGoalRunnableMachine(local)).isTrue();
    assertThat(service.isBuilderGoalRunnableMachine(remote)).isFalse();
    assertThat(service.getBuilderGoalMachineDisabledReason(remote))
        .contains("local ChromaScape host");
  }

  @Test
  void listAccounts_preservesNullMetadataFields() {
    serve(
        "/api/v1/accounts",
        200,
        """
        {
          "accounts": [
            {
              "account_hash": "3720a9a3b5c9f57b",
              "display_name": "udasuAvuco",
              "enabled": true,
              "status": "ready",
              "runtime": null
            }
          ]
        }
        """);

    List<BuilderGoalLauncherControlService.LauncherAccount> accounts = service.listAccounts();

    assertThat(accounts).hasSize(1);
    assertThat(accounts.get(0).metadata()).containsKey("runtime");
    assertThat(accounts.get(0).metadata().get("runtime")).isNull();
    assertThat(accounts.get(0).accountHash()).isEqualTo("3720a9a3b5c9f57b");
    assertThat(accounts.get(0).displayName()).isEqualTo("udasuAvuco");
  }

  @Test
  void getCommandStatus_preservesNullMetadataFields() {
    serve(
        "/api/v1/launcher/commands/cmd-1",
        200,
        """
        {
          "command_id": "cmd-1",
          "status": "succeeded",
          "last_error": null,
          "metadata": {
            "runtime": null,
            "pid": 1234
          }
        }
        """);

    BuilderGoalLauncherControlService.CommandStatus status = service.getCommandStatus("cmd-1");

    assertThat(status.status()).isEqualTo("succeeded");
    assertThat(status.terminal()).isTrue();
    assertThat(status.metadata()).containsKey("runtime");
    assertThat(status.metadata().get("runtime")).isNull();
    assertThat(status.metadata().get("pid")).isEqualTo(1234);
  }

  @Test
  void listMachineSummaries_includesUpstreamStatusAndSanitizedBodyButNotCredentials() {
    serve(
        "/api/v1/launcher/machines",
        401,
        """
        {"error":"unauthorized","request_id":"req-1","echo_token":"secret-token"}
        """);

    assertThatThrownBy(service::listMachineSummaries)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageContaining("unauthorized")
        .satisfies(
            throwable -> assertThat(throwable.getMessage()).doesNotContain("secret-token"));
  }

  @Test
  void listMachineSummaries_invalidJsonReportsActionableParsingCause() {
    serve("/api/v1/launcher/machines", 200, "{not valid json");

    assertThatThrownBy(service::listMachineSummaries)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to load launcher machines")
        .hasMessageContaining("JsonParseException")
        .satisfies(
            throwable -> assertThat(throwable.getMessage()).isNotNull().isNotBlank());
  }

  @Test
  void startAccount_transportFailureUsesSanitizedNonNullCause() {
    serve("/api/v1/launcher/commands/start", 500, "boom");

    assertThatThrownBy(() -> service.startAccount("acc-hash", LOCAL_MACHINE_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to submit launcher start command")
        .hasMessageContaining("HTTP 500")
        .satisfies(
            throwable ->
                assertThat(throwable.getMessage()).doesNotContain("secret-token"));
  }

  @Test
  void startAccount_transportFailureWithNullMessageReportsExceptionType() {
    serve("/api/v1/launcher/commands/start", 500, "boom");

    assertThatThrownBy(() -> service.startAccount("acc-hash", LOCAL_MACHINE_UUID))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(
            throwable ->
                assertThat(throwable.getMessage())
                    .contains("Failed to submit launcher start command")
                    .isNotBlank());
  }

  @Test
  void getCommandStatus_transportFailureUsesSanitizedNonNullCause() {
    serve("/api/v1/launcher/commands/cmd-1", 500, "boom");

    assertThatThrownBy(() -> service.getCommandStatus("cmd-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to read launcher command status for cmd-1")
        .hasMessageContaining("HTTP 500")
        .satisfies(
            throwable ->
                assertThat(throwable.getMessage()).doesNotContain("secret-token"));
  }

  @Test
  void describeSanitizedFailure_neverDisclosesConfiguredToken() {
    String description =
        service.describeSanitizedFailure(
            new IllegalStateException("HTTP 401: echo secret-token and secret-token again"));

    assertThat(description).doesNotContain("secret-token").contains("***");
  }

  @Test
  void describeSanitizedFailure_returnsExceptionTypeForBlankMessage() {
    String description = service.describeSanitizedFailure(new IllegalStateException(""));

    assertThat(description).isEqualTo("IllegalStateException");
  }

  @Test
  void describeSanitizedFailure_returnsExceptionTypeForNullMessage() {
    String description = service.describeSanitizedFailure(new IllegalStateException((String) null));

    assertThat(description).isEqualTo("IllegalStateException");
  }

  @Test
  void describeSanitizedFailure_truncatesUnboundedUpstreamResponse() {
    String longBody = "x".repeat(2000);
    String description = service.describeSanitizedFailure(new IllegalStateException(longBody));

    assertThat(description).hasSizeLessThan(600).contains("(truncated)");
  }

  @Test
  void describeSanitizedFailure_nullFailureReturnsGenericDetail() {
    assertThat(service.describeSanitizedFailure(null))
        .isEqualTo("Unexpected launcher machine discovery failure");
  }

  @Test
  void listAccounts_metadataIsImmutableSnapshotNotTiedToSourceMap() {
    Map<String, Object> sourceMetadata = new java.util.LinkedHashMap<>();
    sourceMetadata.put("runtime", null);
    BuilderGoalLauncherControlService.LauncherAccount account =
        new BuilderGoalLauncherControlService.LauncherAccount(
            "id", "hash", "name", "email", "ready", true, sourceMetadata);

    assertThatThrownBy(() -> account.metadata().put("runtime", "mutated"))
        .isInstanceOf(UnsupportedOperationException.class);

    sourceMetadata.put("runtime", "mutated");
    assertThat(account.metadata().get("runtime")).isNull();
  }
}
