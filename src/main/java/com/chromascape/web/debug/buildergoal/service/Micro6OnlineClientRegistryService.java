package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class Micro6OnlineClientRegistryService {

  private static final Duration STALE_AFTER = Duration.ofSeconds(30);
  private static final Duration EXPIRE_AFTER = Duration.ofMinutes(2);

  private final ConcurrentHashMap<String, ClientState> clients = new ConcurrentHashMap<>();

  public Map<String, Object> recordHeartbeat(JsonNode payload) {
    String clientId = textValue(payload.path("executor_instance_id"));
    if (clientId == null) {
      throw new IllegalArgumentException("executor_instance_id is required");
    }

    ClientState state = new ClientState();
    state.clientId = clientId;
    state.accountName = textValue(payload.path("account_name"));
    state.accountHash = textValue(payload.path("account_hash"));
    // The canonical launch-selected hash is requested_account_hash (producer contract). The legacy
    // expected_account_hash name is accepted only for compatibility; Micro6 never emits it.
    state.requestedAccountHash =
        firstNonBlank(
            textValue(payload.path(AccountIdentityContract.EXPECTED_ACCOUNT_HASH_FIELD)),
            textValue(payload.path(AccountIdentityContract.LEGACY_EXPECTED_ACCOUNT_HASH_FIELD)));
    state.accountHashScheme = textValue(payload.path("account_hash_scheme"));
    state.advertisedBaseUrl = normalizeBaseUrl(textValue(payload.path("advertised_base_url")));
    state.executorBindAddr = textValue(payload.path("executor_bind_addr"));
    state.sourceClientType = textValue(payload.path("source_client_type"));
    state.executorProfile = textValue(payload.path("executor_profile"));
    state.status = firstNonBlank(textValue(payload.path("status")), "UNKNOWN");
    state.currentTask = textValue(payload.path("current_task"));
    state.requestedWorld = intValue(payload.path("requested_world"));
    state.currentWorld = intValue(payload.path("current_world"));
    state.worldMatched =
        payload.path("world_matched").isBoolean()
            ? payload.path("world_matched").asBoolean()
            : null;
    state.scriptLifecycleState = textValue(payload.path("script_lifecycle_state"));
    state.scriptLoopAlive =
        payload.path("script_loop_alive").isBoolean()
            ? payload.path("script_loop_alive").asBoolean()
            : null;
    state.dispatchHold =
        payload.path("dispatch_hold").isBoolean()
            ? payload.path("dispatch_hold").asBoolean()
            : null;
    state.dispatchHoldReason = textValue(payload.path("dispatch_hold_reason"));
    state.startupState = textValue(payload.path("startup_state"));
    state.loginState = textValue(payload.path("login_state"));
    state.pathfinderReady =
        payload.path("pathfinder_ready").isBoolean()
            ? payload.path("pathfinder_ready").asBoolean()
            : null;
    state.launchAttemptId = textValue(payload.path("launch_attempt_id"));
    state.processId = intValue(payload.path("process_id"));
    state.lastSeenAt = Instant.now();

    clients.put(clientId, state);
    return toSummary(state, false);
  }

  public List<Map<String, Object>> listClients() {
    pruneExpired();
    List<ClientState> activeClients = new ArrayList<>(clients.values());
    activeClients.sort(
        Comparator.comparing((ClientState state) -> !isOnline(state))
            .thenComparing((ClientState state) -> isStale(state))
            .thenComparing(
                (ClientState state) ->
                    state.accountName == null ? "~" : state.accountName.toLowerCase(Locale.ENGLISH))
            .thenComparing((ClientState state) -> state.lastSeenAt, Comparator.reverseOrder()));
    return activeClients.stream().map(state -> toSummary(state, isStale(state))).toList();
  }

  public Optional<TargetClient> getRunnableClient(String clientId) {
    pruneExpired();
    ClientState state = clients.get(clientId);
    if (state == null || !isDispatchReady(state)) {
      return Optional.empty();
    }
    return Optional.of(toTargetClient(state, false));
  }

  /** Canonical generic executor readiness check; selected-account identity is checked separately. */
  public boolean isDispatchReady(TargetClient client) {
    if (client == null || client.advertisedBaseUrl() == null || client.advertisedBaseUrl().isBlank()) {
      return false;
    }
    boolean worldMatched =
        client.requestedWorld() != null
            && client.currentWorld() != null
            && client.requestedWorld().equals(client.currentWorld());
    return Micro6ExecutorReadinessEvaluator.evaluate(
            client.dispatchHold(),
            client.dispatchHoldReason(),
            client.startupState(),
            client.loginState(),
            null,
            null,
            null,
            worldMatched,
            client.requestedWorld(),
            client.currentWorld(),
            Boolean.TRUE.equals(client.pathfinderReady()),
            Boolean.TRUE.equals(client.scriptLoopAlive()),
            client.scriptLifecycleState())
        .publishReady()
        // world_matched is retained for diagnostics, but it may have been computed for an older
        // launch request. The observed requested/current values are the authoritative readiness
        // evidence for this registry snapshot.
        && client.requestedWorld() != null
        && client.currentWorld() != null
        && client.requestedWorld().equals(client.currentWorld())
        && client.clientId() != null
        && !client.clientId().isBlank()
        && client.advertisedBaseUrl() != null
        && !client.advertisedBaseUrl().isBlank()
        && client.launchAttemptId() != null
        && !client.launchAttemptId().isBlank()
        && client.processId() != null
        && client.processId() > 0;
  }

  public boolean isSelectedAccountEligible(TargetClient client, String expectedAccountHash) {
    return isDispatchReady(client)
        && AccountIdentityContract.hasRequiredScheme(client.accountHashScheme())
        && AccountIdentityContract.isCanonicalHash(client.accountHash())
        && AccountIdentityContract.isCanonicalHash(expectedAccountHash)
        && AccountIdentityContract.isCanonicalHash(client.requestedAccountHash())
        && AccountIdentityContract.matchesCanonicalHash(client.accountHash(), expectedAccountHash)
        && AccountIdentityContract.matchesCanonicalHash(client.requestedAccountHash(), expectedAccountHash)
        && client.requestedWorld() != null
        && client.currentWorld() != null
        && client.requestedWorld().equals(client.currentWorld());
  }

  /**
   * Online-only lookup used for diagnostics and catalog display. A client that is still logging in,
   * on the wrong world, or running a stopped script is still "online" and selectable for display,
   * but it is not dispatch-ready.
   */
  public Optional<TargetClient> findOnlineClientForRecovery(
      String accountHash, String accountName, Integer expectedPort, Instant seenAfter) {
    pruneExpired();
    Stream<ClientState> stream =
        filterRecoveryStream(
            clients.values().stream().filter(this::isOnline),
            accountHash,
            accountName,
            expectedPort,
            seenAfter);
    return stream
        .sorted(
            Comparator.comparing(
                (ClientState state) -> state.lastSeenAt, Comparator.reverseOrder()))
        .findFirst()
        .map(state -> toTargetClient(state, false));
  }

  /**
   * Dispatch-ready lookup used by account/catalog selection and direct dispatch. Requires the
   * client to be online and logged into the requested world with a live AccountBuilder execution
   * loop, so a wrong-world or stopped-script client is never selected as a dispatch target.
   */
  public Optional<TargetClient> findDispatchReadyClientForRecovery(
      String accountHash,
      String accountName,
      Integer expectedPort,
      Instant seenAfter,
      Integer expectedWorld) {
    return findDispatchReadyClientsForRecovery(
            accountHash, accountName, expectedPort, seenAfter, expectedWorld)
        .stream()
        .findFirst();
  }

  /**
   * Returns every exact dispatch target. Callers that own a publish operation must reject an
   * ambiguous result instead of selecting an arbitrary heartbeat.
   */
  public List<TargetClient> findDispatchReadyClientsForRecovery(
      String accountHash,
      String accountName,
      Integer expectedPort,
      Instant seenAfter,
      Integer expectedWorld) {
    pruneExpired();
    // Identity is mandatory for dispatch selection: hash wins when present, name is a fallback
    // only when no hash is supplied, and unspecified identity is always rejected.
    if ((accountHash == null || accountHash.isBlank())
        && (accountName == null || accountName.isBlank())) {
      return List.of();
    }
    if (expectedWorld == null || expectedWorld <= 0) {
      return List.of();
    }
    if (accountHash != null && !accountHash.isBlank()
        && !AccountIdentityContract.isCanonicalHash(accountHash)) {
      return List.of();
    }
    Stream<ClientState> stream =
        filterRecoveryStream(
            clients.values().stream().filter(this::isDispatchReady),
            accountHash,
            accountName,
            expectedPort,
            seenAfter);
    return stream
        .filter(state -> expectedWorld.equals(state.requestedWorld))
        .filter(state -> expectedWorld.equals(state.currentWorld))
        .filter(
            state ->
                (accountHash != null && !accountHash.isBlank())
                    || safeForNameFallback(state))
        .filter(
            state ->
                accountHash == null
                    || accountHash.isBlank()
                    || (AccountIdentityContract.hasRequiredScheme(state.accountHashScheme)
                        && AccountIdentityContract.isCanonicalHash(state.accountHash)
                        && AccountIdentityContract.isCanonicalHash(state.requestedAccountHash)
                        && AccountIdentityContract.matchesCanonicalHash(
                            state.accountHash, accountHash)
                        && AccountIdentityContract.matchesCanonicalHash(
                            state.requestedAccountHash, accountHash)))
        .filter(state -> state.clientId != null && !state.clientId.isBlank())
        .filter(state -> state.advertisedBaseUrl != null && !state.advertisedBaseUrl.isBlank())
        .filter(state -> state.launchAttemptId != null && !state.launchAttemptId.isBlank())
        .filter(state -> state.processId != null && state.processId > 0)
        .sorted(
            Comparator.comparing(
                (ClientState state) -> state.lastSeenAt, Comparator.reverseOrder()))
        .map(state -> toTargetClient(state, false))
        .toList();
  }

  private boolean safeForNameFallback(ClientState state) {
    return isBlankOrCanonicalHash(state.accountHash)
        && isBlankOrCanonicalHash(state.requestedAccountHash);
  }

  private boolean isBlankOrCanonicalHash(String value) {
    return value == null || value.isBlank() || AccountIdentityContract.isCanonicalHash(value);
  }

  /**
   * Compatibility lookup for diagnostics callers that do not have a selected world. It can never
   * select a dispatch target because readiness requires the current launch's expected world.
   */
  /** @deprecated Dispatch selection must always provide the expected world. */
  @Deprecated
  public Optional<TargetClient> findDispatchReadyClientForRecovery(
      String accountHash, String accountName, Integer expectedPort, Instant seenAfter) {
    return Optional.empty();
  }

  private Stream<ClientState> filterRecoveryStream(
      Stream<ClientState> stream,
      String accountHash,
      String accountName,
      Integer expectedPort,
      Instant seenAfter) {
    if (accountHash != null && !accountHash.isBlank()) {
      String normalizedHash = accountHash.trim().toLowerCase(Locale.ENGLISH);
      stream =
          stream.filter(
              state ->
                  state.accountHash != null
                      && state
                          .accountHash
                          .trim()
                          .toLowerCase(Locale.ENGLISH)
                          .equals(normalizedHash));
    } else if (accountName != null && !accountName.isBlank()) {
      String normalizedAccount = accountName.trim().toLowerCase(Locale.ENGLISH);
      stream =
          stream.filter(
              state ->
                  state.accountName != null
                      && state
                          .accountName
                          .trim()
                          .toLowerCase(Locale.ENGLISH)
                          .equals(normalizedAccount));
    }
    if (expectedPort != null && expectedPort > 0) {
      stream = stream.filter(state -> expectedPort.equals(resolvePort(state)));
    }
    if (seenAfter != null) {
      stream =
          stream.filter(state -> state.lastSeenAt != null && !state.lastSeenAt.isBefore(seenAfter));
    }
    return stream;
  }

  private TargetClient toTargetClient(ClientState state, boolean stale) {
    return new TargetClient(
        state.clientId,
        state.accountName,
        state.accountHash,
        state.advertisedBaseUrl,
        state.executorBindAddr,
        state.sourceClientType,
        state.executorProfile,
        normalizedStatus(state, stale),
        state.currentTask,
        state.lastSeenAt,
        state.requestedWorld,
        state.currentWorld,
        state.worldMatched,
        state.scriptLifecycleState,
        state.scriptLoopAlive,
        state.dispatchHold,
        state.dispatchHoldReason,
        state.startupState,
        state.loginState,
        state.pathfinderReady,
        state.launchAttemptId,
        state.processId,
        state.accountHashScheme,
        state.requestedAccountHash);
  }

  private Map<String, Object> toSummary(ClientState state, boolean stale) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("clientId", state.clientId);
    summary.put("accountName", state.accountName);
    summary.put("accountHash", state.accountHash);
    summary.put("actualAccountHash", state.accountHash);
    summary.put("requestedAccountHash", state.requestedAccountHash);
    summary.put("accountHashScheme", state.accountHashScheme);
    summary.put("advertisedBaseUrl", state.advertisedBaseUrl);
    summary.put("executorBindAddr", state.executorBindAddr);
    summary.put("sourceClientType", state.sourceClientType);
    summary.put("executorProfile", state.executorProfile);
    summary.put("status", normalizedStatus(state, stale));
    summary.put("currentTask", state.currentTask);
    summary.put("requestedWorld", state.requestedWorld);
    summary.put("currentWorld", state.currentWorld);
    summary.put("worldMatched", state.worldMatched);
    summary.put("scriptLifecycleState", state.scriptLifecycleState);
    summary.put("scriptLoopAlive", state.scriptLoopAlive);
    summary.put("dispatchHold", state.dispatchHold);
    summary.put("dispatchHoldReason", state.dispatchHoldReason);
    summary.put("startupState", state.startupState);
    summary.put("loginState", state.loginState);
    summary.put("pathfinderReady", state.pathfinderReady);
    summary.put("launchAttemptId", state.launchAttemptId);
    summary.put("processId", state.processId);
    summary.put("lastSeenAt", state.lastSeenAt);
    summary.put("stale", stale);
    summary.put("online", !stale && state.advertisedBaseUrl != null);
    boolean dispatchReady = isDispatchReady(state) && !stale;
    boolean selectedAccountEligible =
        dispatchReady
            && AccountIdentityContract.hasRequiredScheme(state.accountHashScheme)
            && AccountIdentityContract.isCanonicalHash(state.accountHash)
            && AccountIdentityContract.isCanonicalHash(state.requestedAccountHash)
            && AccountIdentityContract.matchesCanonicalHash(
                state.accountHash, state.requestedAccountHash)
            && state.requestedWorld != null
            && state.currentWorld != null
            && state.requestedWorld.equals(state.currentWorld);
    summary.put("runnable", dispatchReady);
    summary.put("dispatchReady", dispatchReady);
    summary.put("selectedAccountEligible", selectedAccountEligible);
    summary.put(
        "selectedAccountEligibilityReason",
        selectedAccountEligibilityReason(state, dispatchReady, selectedAccountEligible));
    return summary;
  }

  private String selectedAccountEligibilityReason(
      ClientState state, boolean dispatchReady, boolean eligible) {
    if (eligible) {
      return null;
    }
    if (!dispatchReady) {
      return "Executor is not generically dispatch-ready";
    }
    if (state.requestedAccountHash == null || state.requestedAccountHash.isBlank()) {
      return "Requested canonical account hash is missing";
    }
    if (!AccountIdentityContract.isCanonicalHash(state.accountHash)
        || !AccountIdentityContract.isCanonicalHash(state.requestedAccountHash)) {
      return "Account hash values are not canonical 16-hex identities";
    }
    if (!AccountIdentityContract.hasRequiredScheme(state.accountHashScheme)) {
      return "Account hash scheme is missing or unsupported";
    }
    if (!AccountIdentityContract.matchesCanonicalHash(
        state.accountHash, state.requestedAccountHash)) {
      return "Actual account hash does not match requested account hash";
    }
    if (state.requestedWorld == null || state.currentWorld == null
        || !state.requestedWorld.equals(state.currentWorld)) {
      return "Actual world does not match expected world";
    }
    return "Selected-account eligibility evidence is incomplete";
  }

  private String normalizedStatus(ClientState state, boolean stale) {
    if (stale) {
      return "STALE";
    }
    return firstNonBlank(state.status, "UNKNOWN");
  }

  private boolean isOnline(ClientState state) {
    return state != null && !isStale(state) && state.advertisedBaseUrl != null;
  }

  private boolean isDispatchReady(ClientState state) {
    if (!isOnline(state)) {
      return false;
    }
    boolean worldMatched =
        state.requestedWorld != null
            && state.currentWorld != null
            && state.requestedWorld.equals(state.currentWorld);
    return Micro6ExecutorReadinessEvaluator.evaluate(
            state.dispatchHold,
            state.dispatchHoldReason,
            state.startupState,
            state.loginState,
            null,
            null,
            null,
            worldMatched,
            state.requestedWorld,
            state.currentWorld,
            Boolean.TRUE.equals(state.pathfinderReady),
            Boolean.TRUE.equals(state.scriptLoopAlive),
            state.scriptLifecycleState)
        .publishReady()
        && state.clientId != null
        && !state.clientId.isBlank()
        && state.launchAttemptId != null
        && !state.launchAttemptId.isBlank()
        && state.processId != null
        && state.processId > 0;
  }

  private boolean isStale(ClientState state) {
    return state == null
        || state.lastSeenAt == null
        || Instant.now().isAfter(state.lastSeenAt.plus(STALE_AFTER));
  }

  private Integer resolvePort(ClientState state) {
    if (state == null) {
      return null;
    }
    String candidate = firstNonBlank(state.advertisedBaseUrl, state.executorBindAddr);
    if (candidate == null) {
      return null;
    }
    int colon = candidate.lastIndexOf(':');
    if (colon < 0 || colon == candidate.length() - 1) {
      return null;
    }
    String portText = candidate.substring(colon + 1).replaceAll("[^0-9].*$", "").trim();
    if (portText.isEmpty()) {
      return null;
    }
    try {
      return Integer.parseInt(portText);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private void pruneExpired() {
    Instant now = Instant.now();
    clients
        .entrySet()
        .removeIf(
            entry ->
                entry.getValue() == null
                    || entry.getValue().lastSeenAt == null
                    || now.isAfter(entry.getValue().lastSeenAt.plus(EXPIRE_AFTER)));
  }

  private String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.isBlank() ? null : normalized;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private Integer intValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
      return null;
    }
    return node.asInt();
  }

  public record TargetClient(
      String clientId,
      String accountName,
      String accountHash,
      String advertisedBaseUrl,
      String executorBindAddr,
      String sourceClientType,
      String executorProfile,
      String status,
      String currentTask,
      Instant lastSeenAt,
      Integer requestedWorld,
      Integer currentWorld,
      Boolean worldMatched,
      String scriptLifecycleState,
      Boolean scriptLoopAlive,
      Boolean dispatchHold,
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Boolean pathfinderReady,
      String launchAttemptId,
      Integer processId,
      String accountHashScheme,
      String requestedAccountHash) {
    /** Backward-compatible constructor for callers predating readiness fields. */
    public TargetClient(
        String clientId,
        String accountName,
        String accountHash,
        String advertisedBaseUrl,
        String executorBindAddr,
        String sourceClientType,
        String executorProfile,
        String status,
        String currentTask,
        Instant lastSeenAt,
        Integer requestedWorld,
        Integer currentWorld,
        Boolean worldMatched,
        String scriptLifecycleState,
        Boolean scriptLoopAlive,
        Boolean dispatchHold,
        String dispatchHoldReason) {
      this(
          clientId,
          accountName,
          accountHash,
          advertisedBaseUrl,
          executorBindAddr,
          sourceClientType,
          executorProfile,
          status,
          currentTask,
          lastSeenAt,
          requestedWorld,
          currentWorld,
          worldMatched,
          scriptLifecycleState,
          scriptLoopAlive,
          dispatchHold,
          dispatchHoldReason,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    /** Backward-compatible constructor for persisted/test callers predating the hash scheme. */
    public TargetClient(
        String clientId,
        String accountName,
        String accountHash,
        String advertisedBaseUrl,
        String executorBindAddr,
        String sourceClientType,
        String executorProfile,
        String status,
        String currentTask,
        Instant lastSeenAt,
        Integer requestedWorld,
        Integer currentWorld,
        Boolean worldMatched,
        String scriptLifecycleState,
        Boolean scriptLoopAlive,
        Boolean dispatchHold,
        String dispatchHoldReason,
        String startupState,
        String loginState,
        Boolean pathfinderReady,
        String launchAttemptId,
        Integer processId) {
      this(
          clientId,
          accountName,
          accountHash,
          advertisedBaseUrl,
          executorBindAddr,
          sourceClientType,
          executorProfile,
          status,
          currentTask,
          lastSeenAt,
          requestedWorld,
          currentWorld,
          worldMatched,
          scriptLifecycleState,
          scriptLoopAlive,
          dispatchHold,
          dispatchHoldReason,
          startupState,
          loginState,
          pathfinderReady,
          launchAttemptId,
          processId,
          null,
          null);
    }

    /** Backward-compatible full constructor carrying the hash scheme but no requested hash. */
    public TargetClient(
        String clientId,
        String accountName,
        String accountHash,
        String advertisedBaseUrl,
        String executorBindAddr,
        String sourceClientType,
        String executorProfile,
        String status,
        String currentTask,
        Instant lastSeenAt,
        Integer requestedWorld,
        Integer currentWorld,
        Boolean worldMatched,
        String scriptLifecycleState,
        Boolean scriptLoopAlive,
        Boolean dispatchHold,
        String dispatchHoldReason,
        String startupState,
        String loginState,
        Boolean pathfinderReady,
        String launchAttemptId,
        Integer processId,
        String accountHashScheme) {
      this(
          clientId,
          accountName,
          accountHash,
          advertisedBaseUrl,
          executorBindAddr,
          sourceClientType,
          executorProfile,
          status,
          currentTask,
          lastSeenAt,
          requestedWorld,
          currentWorld,
          worldMatched,
          scriptLifecycleState,
          scriptLoopAlive,
          dispatchHold,
          dispatchHoldReason,
          startupState,
          loginState,
          pathfinderReady,
          launchAttemptId,
          processId,
          accountHashScheme,
          null);
    }

    /** Constructor for health snapshots that carry the explicit hash scheme. */
    public TargetClient(
        String clientId,
        String accountName,
        String accountHash,
        String advertisedBaseUrl,
        String executorBindAddr,
        String sourceClientType,
        String executorProfile,
        String status,
        String currentTask,
        Instant lastSeenAt,
        Integer requestedWorld,
        Integer currentWorld,
        Boolean worldMatched,
        String scriptLifecycleState,
        Boolean scriptLoopAlive,
        String launchAttemptId,
        Integer processId,
        String accountHashScheme) {
      this(
          clientId,
          accountName,
          accountHash,
          advertisedBaseUrl,
          executorBindAddr,
          sourceClientType,
          executorProfile,
          status,
          currentTask,
          lastSeenAt,
          requestedWorld,
          currentWorld,
          worldMatched,
          scriptLifecycleState,
          scriptLoopAlive,
          null,
          null,
          null,
          null,
          null,
          launchAttemptId,
          processId,
          accountHashScheme,
          null);
    }

  }

  private static class ClientState {
    private String clientId;
    private String accountName;
    private String accountHash;
    private String requestedAccountHash;
    private String accountHashScheme;
    private String advertisedBaseUrl;
    private String executorBindAddr;
    private String sourceClientType;
    private String executorProfile;
    private String status;
    private String currentTask;
    private Integer requestedWorld;
    private Integer currentWorld;
    private Boolean worldMatched;
    private String scriptLifecycleState;
    private Boolean scriptLoopAlive;
    private Boolean dispatchHold;
    private String dispatchHoldReason;
    private String startupState;
    private String loginState;
    private Boolean pathfinderReady;
    private String launchAttemptId;
    private Integer processId;
    private Instant lastSeenAt;
  }
}
