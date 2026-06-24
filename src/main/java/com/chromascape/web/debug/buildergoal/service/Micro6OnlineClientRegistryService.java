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
    state.advertisedBaseUrl = normalizeBaseUrl(textValue(payload.path("advertised_base_url")));
    state.executorBindAddr = textValue(payload.path("executor_bind_addr"));
    state.sourceClientType = textValue(payload.path("source_client_type"));
    state.executorProfile = textValue(payload.path("executor_profile"));
    state.status = firstNonBlank(textValue(payload.path("status")), "UNKNOWN");
    state.currentTask = textValue(payload.path("current_task"));
    state.lastSeenAt = Instant.now();

    clients.put(clientId, state);
    return toSummary(state, false);
  }

  public List<Map<String, Object>> listClients() {
    pruneExpired();
    List<ClientState> activeClients = new ArrayList<>(clients.values());
    activeClients.sort(
        Comparator.comparing((ClientState state) -> !isRunnable(state))
            .thenComparing((ClientState state) -> isStale(state))
            .thenComparing((ClientState state) -> state.accountName == null ? "~" : state.accountName.toLowerCase(Locale.ENGLISH))
            .thenComparing((ClientState state) -> state.lastSeenAt, Comparator.reverseOrder()));
    return activeClients.stream().map(state -> toSummary(state, isStale(state))).toList();
  }

  public Optional<TargetClient> getRunnableClient(String clientId) {
    pruneExpired();
    ClientState state = clients.get(clientId);
    if (state == null || !isRunnable(state)) {
      return Optional.empty();
    }
    return Optional.of(
        new TargetClient(
            state.clientId,
            state.accountName,
            state.accountHash,
            state.advertisedBaseUrl,
            state.executorBindAddr,
            state.sourceClientType,
            state.executorProfile,
            normalizedStatus(state, false),
            state.currentTask,
            state.lastSeenAt));
  }

  private Map<String, Object> toSummary(ClientState state, boolean stale) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("clientId", state.clientId);
    summary.put("accountName", state.accountName);
    summary.put("accountHash", state.accountHash);
    summary.put("advertisedBaseUrl", state.advertisedBaseUrl);
    summary.put("executorBindAddr", state.executorBindAddr);
    summary.put("sourceClientType", state.sourceClientType);
    summary.put("executorProfile", state.executorProfile);
    summary.put("status", normalizedStatus(state, stale));
    summary.put("currentTask", state.currentTask);
    summary.put("lastSeenAt", state.lastSeenAt);
    summary.put("stale", stale);
    summary.put("runnable", !stale && state.advertisedBaseUrl != null);
    return summary;
  }

  private String normalizedStatus(ClientState state, boolean stale) {
    if (stale) {
      return "STALE";
    }
    return firstNonBlank(state.status, "UNKNOWN");
  }

  private boolean isRunnable(ClientState state) {
    return state != null && !isStale(state) && state.advertisedBaseUrl != null;
  }

  private boolean isStale(ClientState state) {
    return state == null
        || state.lastSeenAt == null
        || Instant.now().isAfter(state.lastSeenAt.plus(STALE_AFTER));
  }

  private void pruneExpired() {
    Instant now = Instant.now();
    clients.entrySet().removeIf(
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
      Instant lastSeenAt) {}

  private static class ClientState {
    private String clientId;
    private String accountName;
    private String accountHash;
    private String advertisedBaseUrl;
    private String executorBindAddr;
    private String sourceClientType;
    private String executorProfile;
    private String status;
    private String currentTask;
    private Instant lastSeenAt;
  }
}
