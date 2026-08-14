package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class Micro6OnlineClientRegistryServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void recordHeartbeat_registersRunnableClient() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-1",
              "account_name": "Test Account",
              "account_hash": "abc123",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "executor_bind_addr": "0.0.0.0:8098",
              "source_client_type": "micro6",
              "executor_profile": "accountbuilder_builderpro",
              "status": "IDLE",
              "current_task": null,
              "requested_world": 360,
              "current_world": 360,
              "world_matched": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "pathfinder_ready": true
            }
            """));

    assertThat(service.listClients()).hasSize(1);
    assertThat(service.listClients().get(0).get("accountName")).isEqualTo("Test Account");
    assertThat(service.listClients().get(0).get("advertisedBaseUrl"))
        .isEqualTo("http://192.168.1.50:8098");
    assertThat(service.listClients().get(0).get("runnable")).isEqualTo(true);
    assertThat(service.listClients().get(0).get("dispatchReady")).isEqualTo(true);
    assertThat(service.getRunnableClient("client-1")).isPresent();
  }

  @Test
  void recordHeartbeat_missingReadinessFieldsFailClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-incomplete",
              "account_name": "Test Account",
              "account_hash": "abc123",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "executor_bind_addr": "0.0.0.0:8098",
              "source_client_type": "micro6",
              "executor_profile": "accountbuilder_builderpro",
              "status": "IDLE"
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("online")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(false);
    assertThat(summary.get("dispatchReady")).isEqualTo(false);
    assertThat(service.getRunnableClient("client-incomplete")).isEmpty();
  }

  @Test
  void recordHeartbeat_wrongWorldIsNotDispatchReady() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-wrong-world",
              "account_name": "Test Account",
              "account_hash": "abc123",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "executor_bind_addr": "0.0.0.0:8098",
              "source_client_type": "micro6",
              "executor_profile": "accountbuilder_builderpro",
              "status": "IDLE",
              "requested_world": 360,
              "current_world": 301,
              "world_matched": false,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "pathfinder_ready": true
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("online")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(false);
    assertThat(service.getRunnableClient("client-wrong-world")).isEmpty();
  }

  @Test
  void recordHeartbeat_capturesWorldAndScriptLifecycleFields() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-2",
              "account_name": "Test Account",
              "account_hash": "abc123",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "executor_bind_addr": "0.0.0.0:8098",
              "source_client_type": "micro6",
              "executor_profile": "accountbuilder_builderpro",
              "status": "IDLE",
              "requested_world": 360,
              "current_world": 360,
              "world_matched": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "launch_attempt_id": "attempt-123",
              "process_id": 4242
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("requestedWorld")).isEqualTo(360);
    assertThat(summary.get("currentWorld")).isEqualTo(360);
    assertThat(summary.get("worldMatched")).isEqualTo(true);
    assertThat(summary.get("scriptLifecycleState")).isEqualTo("RUNNING");
    assertThat(summary.get("scriptLoopAlive")).isEqualTo(true);
    assertThat(summary.get("launchAttemptId")).isEqualTo("attempt-123");
    assertThat(summary.get("processId")).isEqualTo(4242);
  }

  @Test
  void recordHeartbeat_absentWorldFieldsAreNotMatched() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-3",
              "account_name": "Test Account",
              "account_hash": "abc123",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "status": "IDLE"
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("requestedWorld")).isNull();
    assertThat(summary.get("worldMatched")).isNull();
    assertThat(summary.get("scriptLoopAlive")).isNull();
  }

  @Test
  void dispatchReadyLookup_hashIsAuthoritativeOverMatchingName() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-hash-a", "same-name", "hash-AAA", 360);
    registerReadyClient(service, "client-hash-b", "same-name", "hash-BBB", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-BBB", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent()
        .get()
        .extracting(Micro6OnlineClientRegistryService.TargetClient::clientId)
        .isEqualTo("client-hash-b");
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-AAA", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent()
        .get()
        .extracting(Micro6OnlineClientRegistryService.TargetClient::clientId)
        .isEqualTo("client-hash-a");
  }

  @Test
  void dispatchReadyLookup_nameFallbackAllowedOnlyWhenNoExpectedHash() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-name-match", "Fallback Name", "hash-1", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                null, "FALLBACK NAME", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent()
        .get()
        .extracting(Micro6OnlineClientRegistryService.TargetClient::clientId)
        .isEqualTo("client-name-match");
  }

  @Test
  void dispatchReadyLookup_matchingNameNeverOverridesHashMismatch() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-hash-b", "same-name", "hash-BBB", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-AAA", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_missingIdentityRejected() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-1", "Some Name", "hash-1", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                null, null, null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_requiresExactCurrentWorldNotHeartbeatFlag() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    // Heartbeat claims world_matched=true but observed current_world differs from the requested
    // launch world. The flag is diagnostic only; the observed world must be authoritative.
    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-stale-flag",
              "account_name": "Test Account",
              "account_hash": "hash-1",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "executor_bind_addr": "0.0.0.0:8098",
              "source_client_type": "micro6",
              "executor_profile": "accountbuilder_builderpro",
              "status": "IDLE",
              "requested_world": 360,
              "current_world": 301,
              "world_matched": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "pathfinder_ready": true
            }
            """));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
    assertThat(service.getRunnableClient("client-stale-flag")).isEmpty();
  }

  @Test
  void dispatchReadyLookup_wrongExpectedWorldRejected() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-360", "Test Account", "hash-1", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 301))
        .isEmpty();
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent();
  }

  @Test
  void dispatchReadyLookup_dispatchHoldFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-held", "hash-1", 360)
            .put("dispatch_hold", true)
            .put("dispatch_hold_reason", "waiting_for_script"));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_loggedOutFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(readyHeartbeat("client-logged-out", "hash-1", 360).put("login_state", "auto_login_failed"));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_startupIncompleteFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-startup", "hash-1", 360).put("startup_state", "initializing_bot_logic"));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_pathfinderIncompleteFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(readyHeartbeat("client-pf", "hash-1", 360).put("pathfinder_ready", false));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_missingLoginStateFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    com.fasterxml.jackson.databind.node.ObjectNode heartbeat =
        readyHeartbeat("client-nologin", "hash-1", 360);
    heartbeat.remove("login_state");
    service.recordHeartbeat(heartbeat);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void genericRunnableDoesNotProveSelectedAccountEligibility() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-other-account", "actual-hash", 360)
            .put("expected_account_hash", "selected-hash"));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("dispatchReady")).isEqualTo(true);
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(false);
    assertThat(summary.get("selectedAccountEligibilityReason"))
        .isEqualTo("Actual account hash does not match expected account hash");
  }

  @Test
  void runnableAndDispatchReadyAgreeWithEvaluator() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-ready", "Test Account", "hash-1", 360);

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("online")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("dispatchReady")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(summary.get("dispatchReady"));
    assertThat(service.getRunnableClient("client-ready")).isPresent();
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent();
  }

  private void registerReadyClient(
      Micro6OnlineClientRegistryService service, String clientId, String accountName, String accountHash, int world)
      throws Exception {
    service.recordHeartbeat(
        readyHeartbeat(clientId, accountHash, world).put("account_name", accountName));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode readyHeartbeat(
      String clientId, String accountHash, int world) {
    return (com.fasterxml.jackson.databind.node.ObjectNode)
        objectMapper.createObjectNode()
            .put("executor_instance_id", clientId)
            .put("account_name", "Test Account")
            .put("account_hash", accountHash)
            .put("expected_account_hash", accountHash)
            .put("account_hash_scheme", AccountIdentityContract.HASH_SCHEME)
            .put("advertised_base_url", "http://192.168.1.50:8098/")
            .put("executor_bind_addr", "0.0.0.0:8098")
            .put("source_client_type", "micro6")
            .put("executor_profile", "accountbuilder_builderpro")
            .put("status", "IDLE")
            .put("requested_world", world)
            .put("current_world", world)
            .put("world_matched", true)
            .put("script_lifecycle_state", "RUNNING")
            .put("script_loop_alive", true)
            .put("dispatch_hold", false)
            .put("startup_state", "accountbuilder_ready")
            .put("login_state", "logged_in")
            .put("pathfinder_ready", true);
  }
}
