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
              "account_hash": "0123456789abcdef",
              "requested_account_hash": "0123456789abcdef",
              "account_hash_scheme": "accountconnector/v1",
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
              "pathfinder_ready": true,
              "launch_attempt_id": "attempt-1",
              "process_id": 4242
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
              "account_hash": "0123456789abcdef",
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
              "account_hash": "0123456789abcdef",
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
              "pathfinder_ready": true,
              "launch_attempt_id": "attempt-eligible",
              "process_id": 4242
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
              "account_hash": "0123456789abcdef",
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
              "account_hash": "0123456789abcdef",
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
    registerReadyClient(service, "client-hash-a", "same-name", "aaaaaaaaaaaaaaaa", 360);
    registerReadyClient(service, "client-hash-b", "same-name", "bbbbbbbbbbbbbbbb", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "bbbbbbbbbbbbbbbb", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent()
        .get()
        .extracting(Micro6OnlineClientRegistryService.TargetClient::clientId)
        .isEqualTo("client-hash-b");
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "aaaaaaaaaaaaaaaa", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent()
        .get()
        .extracting(Micro6OnlineClientRegistryService.TargetClient::clientId)
        .isEqualTo("client-hash-a");
  }

  @Test
  void dispatchReadyLookup_nameFallbackAllowedOnlyWhenNoExpectedHash() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-name-match", "Fallback Name", "1111111111111111", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                null, "FALLBACK NAME", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent()
        .get()
        .extracting(Micro6OnlineClientRegistryService.TargetClient::clientId)
        .isEqualTo("client-name-match");
  }

  @Test
  void dispatchReadyLookup_nameFallbackRejectsMalformedReportedIdentity() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-name-malformed", "Fallback Name", "hash-1", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                null, "Fallback Name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_matchingNameNeverOverridesHashMismatch() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-hash-b", "same-name", "bbbbbbbbbbbbbbbb", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "aaaaaaaaaaaaaaaa", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_matchingMalformedHashesNeverAuthorizeDispatch() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-malformed", "same-name", "hash-1", 360);

    // String equality alone is not identity evidence: malformed values must fail before a
    // selected executor can be returned, even when the legacy display name also matches.
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "hash-1", "same-name", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_missingIdentityRejected() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-1", "Some Name", "1111111111111111", 360);

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
              "account_hash": "1111111111111111",
              "requested_account_hash": "1111111111111111",
              "account_hash_scheme": "accountconnector/v1",
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
              "pathfinder_ready": true,
              "launch_attempt_id": "attempt-legacy",
              "process_id": 4242
            }
            """));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
    assertThat(service.getRunnableClient("client-stale-flag")).isEmpty();
  }

  @Test
  void dispatchReadyLookup_usesObservedWorldWhenHeartbeatFlagIsStale() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-stale-match-flag", "1111111111111111", 360)
            .put("world_matched", false));

    assertThat(service.getRunnableClient("client-stale-match-flag")).isPresent();
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111",
                "Test Account",
                null,
                java.time.Instant.now().minusSeconds(45),
                360))
        .isPresent();
  }

  @Test
  void dispatchReadyLookup_wrongExpectedWorldRejected() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-360", "Test Account", "1111111111111111", 360);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 301))
        .isEmpty();
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isPresent();
  }

  @Test
  void dispatchReadyLookup_dispatchHoldFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-held", "1111111111111111", 360)
            .put("dispatch_hold", true)
            .put("dispatch_hold_reason", "waiting_for_script"));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_loggedOutFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(readyHeartbeat("client-logged-out", "1111111111111111", 360).put("login_state", "auto_login_failed"));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_startupIncompleteFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-startup", "1111111111111111", 360).put("startup_state", "initializing_bot_logic"));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_pathfinderIncompleteFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(readyHeartbeat("client-pf", "1111111111111111", 360).put("pathfinder_ready", false));

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void dispatchReadyLookup_missingLoginStateFailsClosed() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    com.fasterxml.jackson.databind.node.ObjectNode heartbeat =
        readyHeartbeat("client-nologin", "1111111111111111", 360);
    heartbeat.remove("login_state");
    service.recordHeartbeat(heartbeat);

    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
        .isEmpty();
  }

  @Test
  void genericRunnableDoesNotProveSelectedAccountEligibility() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-other-account", "b01c7f006bace664", 360)
            .put("requested_account_hash", "3720a9a3b5c9f57b"));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("dispatchReady")).isEqualTo(true);
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(false);
    assertThat(summary.get("selectedAccountEligibilityReason"))
        .isEqualTo("Actual account hash does not match requested account hash");
  }

  @Test
  void heartbeatWithRequestedAccountHashIsEligibleWhenAllConditionsMatch() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-eligible",
              "account_name": "Test Account",
              "account_hash": "b01c7f006bace664",
              "requested_account_hash": "b01c7f006bace664",
              "account_hash_scheme": "accountconnector/v1",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "status": "IDLE",
              "requested_world": 360,
              "current_world": 360,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "pathfinder_ready": true,
              "launch_attempt_id": "attempt-both",
              "process_id": 4242
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("actualAccountHash")).isEqualTo("b01c7f006bace664");
    assertThat(summary.get("requestedAccountHash")).isEqualTo("b01c7f006bace664");
    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(true);
    assertThat(summary.get("selectedAccountEligibilityReason")).isNull();
  }

  @Test
  void legacyExpectedAccountHashRemainsCompatibilityOnly() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-legacy",
              "account_name": "Test Account",
              "account_hash": "b01c7f006bace664",
              "expected_account_hash": "b01c7f006bace664",
              "account_hash_scheme": "accountconnector/v1",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "status": "IDLE",
              "requested_world": 360,
              "current_world": 360,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "pathfinder_ready": true,
              "launch_attempt_id": "attempt-both-compatibility",
              "process_id": 4242
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("requestedAccountHash")).isEqualTo("b01c7f006bace664");
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(true);
  }

  @Test
  void requestedAccountHashWinsOverLegacyExpectedField() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        objectMapper.readTree(
            """
            {
              "executor_instance_id": "client-both",
              "account_name": "Test Account",
              "account_hash": "b01c7f006bace664",
              "requested_account_hash": "b01c7f006bace664",
              "expected_account_hash": "3720a9a3b5c9f57b",
              "account_hash_scheme": "accountconnector/v1",
              "advertised_base_url": "http://192.168.1.50:8098/",
              "status": "IDLE",
              "requested_world": 360,
              "current_world": 360,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true,
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "pathfinder_ready": true,
              "launch_attempt_id": "attempt-legacy",
              "process_id": 4242
            }
            """));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("requestedAccountHash")).isEqualTo("b01c7f006bace664");
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(true);
  }

  @Test
  void sameNameDifferentHashRejects() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    service.recordHeartbeat(
        readyHeartbeat("client-same-name", "b01c7f006bace664", 360)
            .put("account_name", "Shared Name")
            .put("requested_account_hash", "3720a9a3b5c9f57b"));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(false);
    assertThat(summary.get("selectedAccountEligibilityReason"))
        .isEqualTo("Actual account hash does not match requested account hash");
  }

  @Test
  void missingOrWrongHashSchemeRejects() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        readyHeartbeat("client-no-scheme", "b01c7f006bace664", 360)
            .put("account_hash_scheme", (String) null));

    java.util.Map<String, Object> noScheme = service.listClients().get(0);
    assertThat(noScheme.get("selectedAccountEligible")).isEqualTo(false);
    assertThat(noScheme.get("selectedAccountEligibilityReason"))
        .isEqualTo("Account hash scheme is missing or unsupported");

    service.recordHeartbeat(
        readyHeartbeat("client-wrong-scheme", "b01c7f006bace664", 360)
            .put("account_hash_scheme", "profile/v1"));

    java.util.Map<String, Object> wrongScheme = service.listClients().get(1);
    assertThat(wrongScheme.get("selectedAccountEligible")).isEqualTo(false);
  }

  @Test
  void invalidHashValuesReject() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();

    service.recordHeartbeat(
        readyHeartbeat("client-invalid-hash", "not-a-canonical-hash", 360));

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("selectedAccountEligible")).isEqualTo(false);
    assertThat(summary.get("selectedAccountEligibilityReason"))
        .isEqualTo("Account hash values are not canonical 16-hex identities");
  }

  @Test
  void runnableAndDispatchReadyAgreeWithEvaluator() throws Exception {
    Micro6OnlineClientRegistryService service = new Micro6OnlineClientRegistryService();
    registerReadyClient(service, "client-ready", "Test Account", "1111111111111111", 360);

    java.util.Map<String, Object> summary = service.listClients().get(0);
    assertThat(summary.get("online")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(true);
    assertThat(summary.get("dispatchReady")).isEqualTo(true);
    assertThat(summary.get("runnable")).isEqualTo(summary.get("dispatchReady"));
    assertThat(service.getRunnableClient("client-ready")).isPresent();
    assertThat(
            service.findDispatchReadyClientForRecovery(
                "1111111111111111", "Test Account", null, java.time.Instant.now().minusSeconds(45), 360))
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
            .put("requested_account_hash", accountHash)
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
            .put("pathfinder_ready", true)
            .put("launch_attempt_id", "attempt-" + clientId)
            .put("process_id", 4242);
  }
}
