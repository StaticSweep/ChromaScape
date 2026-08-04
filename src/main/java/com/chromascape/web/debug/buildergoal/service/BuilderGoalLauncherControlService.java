package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class BuilderGoalLauncherControlService {

  public static final String MANAGED_CLIENT_TYPE = "leeuwbot";

  private static final Logger log = LoggerFactory.getLogger(BuilderGoalLauncherControlService.class);
  private static final List<String> TERMINAL_STATUSES = List.of("succeeded", "failed", "expired");

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;
  private final String authToken;
  private final String defaultMachineUuid;
  private final String localMachineUuid;
  private final String localHostName;
  private final String accountsPath;
  private final String machinesPath;
  private final String startCommandPath;
  private final String commandStatusPath;
  private final long requestTimeoutMs;
  private final long pollIntervalMs;
  private final long commandTimeoutMs;

  public BuilderGoalLauncherControlService(
      ObjectMapper objectMapper,
      @Value("${builder-goal.launcher.base-url:http://192.168.1.6:8084}") String baseUrl,
      @Value("${builder-goal.launcher.api-token:${CHROMASCAPE_API_KEY:dev-token}}")
          String authToken,
      @Value("${builder-goal.launcher.default-machine-uuid:}") String defaultMachineUuid,
      @Value("${builder-goal.launcher.local-machine-uuid:}") String localMachineUuid,
      @Value("${builder-goal.launcher.accounts-path:/api/v1/accounts}") String accountsPath,
      @Value("${builder-goal.launcher.machines-path:/api/v1/launcher/machines}") String machinesPath,
      @Value("${builder-goal.launcher.start-command-path:/api/v1/launcher/commands/start}")
          String startCommandPath,
      @Value("${builder-goal.launcher.command-status-path:/api/v1/launcher/commands}")
          String commandStatusPath,
      @Value("${builder-goal.launcher.request-timeout-ms:10000}") long requestTimeoutMs,
      @Value("${builder-goal.launcher.poll-interval-ms:1200}") long pollIntervalMs,
      @Value("${builder-goal.launcher.command-timeout-ms:300000}") long commandTimeoutMs) {
    this.objectMapper = objectMapper;
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.authToken = normalizeOptional(authToken);
    this.defaultMachineUuid = normalizeOptional(defaultMachineUuid);
    this.localMachineUuid = normalizeOptional(localMachineUuid);
    this.localHostName = resolveLocalHostName();
    this.accountsPath = normalizePath(accountsPath);
    this.machinesPath = normalizePath(machinesPath);
    this.startCommandPath = normalizePath(startCommandPath);
    this.commandStatusPath = normalizePath(commandStatusPath);
    this.requestTimeoutMs = Math.max(1000L, requestTimeoutMs);
    this.pollIntervalMs = Math.max(500L, pollIntervalMs);
    this.commandTimeoutMs = Math.max(10_000L, commandTimeoutMs);
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.requestTimeoutMs)).build();
  }

  public List<LauncherAccount> listAccounts() {
    ensureBaseUrlConfigured();
    try {
      JsonNode root = sendGet(withOfflineAccounts(accountsPath));
      JsonNode items = root;
      if (root != null && root.isObject()) {
        if (root.path("accounts").isArray()) {
          items = root.path("accounts");
        } else if (root.path("items").isArray()) {
          items = root.path("items");
        }
      }
      if (items == null || !items.isArray()) {
        return List.of();
      }
      List<LauncherAccount> accounts = new ArrayList<>();
      for (JsonNode item : items) {
        LauncherAccount account = parseAccount(item);
        if (account != null) {
          accounts.add(account);
        }
      }
      return accounts;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to load launcher accounts: "
              + sanitizeUpstreamError(sanitizedCause(e)),
          e);
    }
  }

  public List<Map<String, Object>> listMachineSummaries() {
    return listMachines().stream().map(this::toMachineSummary).toList();
  }

  public Optional<LauncherMachine> findMachineByUuid(String machineUuid) {
    String normalized = normalizeOptional(machineUuid);
    if (normalized == null) {
      return Optional.empty();
    }
    return listMachines().stream()
        .filter(machine -> normalized.equals(machine.machineUuid()))
        .findFirst();
  }

  public boolean isLocalMachine(LauncherMachine machine) {
    if (machine == null) {
      return false;
    }
    if (localMachineUuid != null) {
      return localMachineUuid.equals(machine.machineUuid());
    }
    String machineHostName =
        firstNonBlank(
            metadataText(machine.metadata(), "hostname", "hostName"),
            extractHostPrefix(machine.displayName()));
    if (machineHostName == null || localHostName == null) {
      return false;
    }
    return machineHostName.equalsIgnoreCase(localHostName);
  }

  public boolean isBuilderGoalRunnableMachine(LauncherMachine machine) {
    return machine != null
        && machine.enabled()
        && machine.online()
        && isLocalMachine(machine);
  }

  public String getBuilderGoalMachineDisabledReason(LauncherMachine machine) {
    if (machine == null) {
      return "Selected launcher machine was not found";
    }
    if (!machine.enabled()) {
      return "Launcher machine is disabled";
    }
    if (!machine.online()) {
      return "Launcher machine is offline";
    }
    if (!isLocalMachine(machine)) {
      return "Builder Goal local dev launches only run on the local ChromaScape host";
    }
    return null;
  }

  public List<LauncherMachine> listMachines() {
    ensureBaseUrlConfigured();
    try {
      JsonNode root = sendGet(machinesPath);
      JsonNode items = root;
      if (root != null && root.isObject()) {
        if (root.path("machines").isArray()) {
          items = root.path("machines");
        } else if (root.path("items").isArray()) {
          items = root.path("items");
        } else if (root.path("results").isArray()) {
          items = root.path("results");
        } else if (root.path("data").isArray()) {
          items = root.path("data");
        }
      }
      if (items == null || !items.isArray()) {
        return List.of();
      }
      List<LauncherMachine> machines = new ArrayList<>();
      for (JsonNode item : items) {
        LauncherMachine machine = parseMachine(item);
        if (machine != null) {
          machines.add(machine);
        }
      }
      return machines;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to load launcher machines: " + sanitizeUpstreamError(sanitizedCause(e)), e);
    }
  }

  public StartCommandResult startAccount(String accountIdentifier, String machineUuid) {
    ensureBaseUrlConfigured();
    String resolvedIdentifier = normalizeRequired(accountIdentifier, "account identifier");
    String resolvedMachineUuid = firstNonBlank(normalizeOptional(machineUuid), defaultMachineUuid);
    if (resolvedMachineUuid == null) {
      throw new IllegalStateException("Builder Goal launcher machine UUID is not configured");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("machine_uuid", resolvedMachineUuid);
    body.put("account_hash", resolvedIdentifier);
    body.put("client_type", MANAGED_CLIENT_TYPE);
    try {
      JsonNode root = sendPost(startCommandPath, objectMapper.writeValueAsString(body));
      String commandId = textValue(root.path("command_id"));
      if (commandId == null) {
        throw new IllegalStateException("Launcher start response did not include command_id");
      }
      return new StartCommandResult(
          commandId,
          firstNonBlank(textValue(root.path("status")), "queued"),
          firstNonBlank(textValue(root.path("resolved_account_hash")), resolvedIdentifier),
          textValue(root.path("resolved_from")),
          textValue(root.path("resolved_path_source")));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to submit launcher start command: " + e.getMessage(), e);
    }
  }

  public CommandStatus awaitCommandSuccess(String commandId) {
    String normalizedCommandId = normalizeRequired(commandId, "launcher command id");
    Instant deadline = Instant.now().plusMillis(commandTimeoutMs);
    CommandStatus latest = null;
    while (Instant.now().isBefore(deadline)) {
      latest = getCommandStatus(normalizedCommandId);
      if (latest == null) {
        throw new IllegalStateException("Launcher command lookup returned no status");
      }
      if (latest.terminal()) {
        if (!"succeeded".equalsIgnoreCase(latest.status())) {
          throw new IllegalStateException(
              firstNonBlank(
                  latest.lastError(),
                  "Launcher command ended with status " + latest.status()));
        }
        return latest;
      }
      try {
        Thread.sleep(pollIntervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while polling launcher command status", e);
      }
    }
    throw new IllegalStateException(
        "Launcher command polling timed out before launch completed for command " + normalizedCommandId);
  }

  public CommandStatus getCommandStatus(String commandId) {
    ensureBaseUrlConfigured();
    String normalizedCommandId = normalizeRequired(commandId, "launcher command id");
    try {
      JsonNode root = sendGet(commandStatusPath + "/" + normalizedCommandId);
      String status = firstNonBlank(textValue(root.path("status")), "unknown");
      JsonNode metadataNode = root.path("metadata");
      Map<String, Object> metadata =
          metadataNode != null && metadataNode.isObject()
              ? objectMapper.convertValue(
                  metadataNode, new TypeReference<LinkedHashMap<String, Object>>() {})
              : Map.of();
      return new CommandStatus(
          normalizedCommandId,
          status,
          textValue(root.path("last_error")),
          metadata,
          TERMINAL_STATUSES.contains(status.toLowerCase(Locale.ENGLISH)));
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to read launcher command status for " + normalizedCommandId + ": " + e.getMessage(),
          e);
    }
  }

  public String getDefaultMachineUuid() {
    return defaultMachineUuid;
  }

  public String getAuthToken() {
    return authToken;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public boolean isConfigured() {
    return baseUrl != null;
  }

  private void ensureBaseUrlConfigured() {
    if (baseUrl == null) {
      throw new IllegalStateException("Builder Goal launcher base URL is not configured");
    }
  }

  private String withOfflineAccounts(String path) {
    if (path == null || path.isBlank()) {
      return "/api/v1/accounts?include_offline=true";
    }
    if (path.contains("include_offline=")) {
      return path;
    }
    return path + (path.contains("?") ? "&" : "?") + "include_offline=true";
  }

  private LauncherAccount parseAccount(JsonNode item) {
    if (item == null || item.isNull() || !item.isObject()) {
      return null;
    }
    String accountHash =
        firstNonBlank(
            textValue(item.path("account_hash")),
            textValue(item.path("accountHash")),
            textValue(item.path("hash")));
    String displayName =
        firstNonBlank(
            textValue(item.path("display_name")),
            textValue(item.path("displayName")),
            textValue(item.path("account_name")),
            textValue(item.path("accountName")),
            textValue(item.path("username")),
            textValue(item.path("email")),
            accountHash);
    if (displayName == null && accountHash == null) {
      return null;
    }
    String status =
        firstNonBlank(
            textValue(item.path("status")),
            textValue(item.path("account_status")),
            "unknown");
    boolean enabled = booleanValue(item.path("enabled"), true);
    String email = firstNonBlank(textValue(item.path("email")), textValue(item.path("login_name")));
    return new LauncherAccount(
        firstNonBlank(accountHash, displayName),
        accountHash,
        displayName,
        email,
        status,
        enabled,
        objectMapper.convertValue(item, Map.class));
  }

  private LauncherMachine parseMachine(JsonNode item) {
    if (item == null || item.isNull() || !item.isObject()) {
      return null;
    }
    String machineUuid =
        firstNonBlank(
            textValue(item.path("machine_uuid")),
            textValue(item.path("machineUuid")),
            textValue(item.path("uuid")),
            textValue(item.path("id")));
    String displayName =
        firstNonBlank(
            textValue(item.path("display_name")),
            textValue(item.path("displayName")),
            textValue(item.path("name")),
            textValue(item.path("hostname")),
            machineUuid);
    if (machineUuid == null && displayName == null) {
      return null;
    }
    String status =
        firstNonBlank(
            textValue(item.path("status")),
            textValue(item.path("machine_status")),
            textValue(item.path("state")),
            "unknown");
    boolean enabled = booleanValue(item.path("enabled"), true);
    boolean online =
        booleanValue(
            item.path("online"),
            booleanValue(item.path("is_online"), !isOfflineStatus(status)));
    return new LauncherMachine(
        firstNonBlank(machineUuid, displayName),
        displayName,
        status,
        enabled,
        online,
        objectMapper.convertValue(item, Map.class));
  }

  private Map<String, Object> toMachineSummary(LauncherMachine machine) {
    boolean runnable = machine.enabled() && machine.online();
    boolean localMachine = isLocalMachine(machine);
    boolean builderGoalRunnable = isBuilderGoalRunnableMachine(machine);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("machineUuid", machine.machineUuid());
    summary.put("displayName", machine.displayName());
    summary.put("status", machine.status());
    summary.put("enabled", machine.enabled());
    summary.put("online", machine.online());
    summary.put("runnable", runnable);
    summary.put("localMachine", localMachine);
    summary.put("builderGoalRunnable", builderGoalRunnable);
    summary.put("defaultSelected", machine.machineUuid().equals(defaultMachineUuid));
    summary.put("disabledReason", determineMachineDisabledReason(machine));
    summary.put("builderGoalDisabledReason", getBuilderGoalMachineDisabledReason(machine));
    summary.put("metadata", machine.metadata());
    return summary;
  }

  private String resolveLocalHostName() {
    String envHost =
        firstNonBlank(
            normalizeOptional(System.getenv("COMPUTERNAME")),
            normalizeOptional(System.getenv("HOSTNAME")));
    if (envHost != null) {
      return envHost;
    }
    try {
      return normalizeOptional(InetAddress.getLocalHost().getHostName());
    } catch (Exception ignored) {
      return null;
    }
  }

  private String metadataText(Map<String, Object> metadata, String... keys) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    for (String key : keys) {
      Object value = metadata.get(key);
      String normalized = normalizeOptional(value != null ? value.toString() : null);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private String extractHostPrefix(String displayName) {
    String normalized = normalizeOptional(displayName);
    if (normalized == null) {
      return null;
    }
    int spaceIndex = normalized.indexOf(' ');
    int parenIndex = normalized.indexOf('(');
    int cutIndex = -1;
    if (spaceIndex > 0 && parenIndex > 0) {
      cutIndex = Math.min(spaceIndex, parenIndex);
    } else if (spaceIndex > 0) {
      cutIndex = spaceIndex;
    } else if (parenIndex > 0) {
      cutIndex = parenIndex;
    }
    String prefix = cutIndex > 0 ? normalized.substring(0, cutIndex) : normalized;
    return normalizeOptional(prefix);
  }

  private String determineMachineDisabledReason(LauncherMachine machine) {
    if (!machine.enabled()) {
      return "Launcher machine is disabled";
    }
    if (!machine.online()) {
      return "Launcher machine is offline";
    }
    return null;
  }

  private boolean isOfflineStatus(String status) {
    String normalized = normalizeOptional(status);
    if (normalized == null) {
      return false;
    }
    String lowered = normalized.toLowerCase(Locale.ENGLISH);
    return lowered.contains("offline")
        || lowered.contains("disconnected")
        || lowered.contains("unreachable")
        || lowered.contains("down");
  }

  private JsonNode sendGet(String path) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET()
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    applyAuth(builder);
    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(
          "HTTP " + response.statusCode() + ": " + sanitizeUpstreamError(response.body()));
    }
    return objectMapper.readTree(response.body());
  }

  private JsonNode sendPost(String path, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    applyAuth(builder);
    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(
          "HTTP " + response.statusCode() + ": " + sanitizeUpstreamError(response.body()));
    }
    return objectMapper.readTree(response.body());
  }

  /**
   * Builds a non-null, actionable cause string from a parsing/transport failure: the exception
   * type plus its message, falling back to the class name so callers never see a bare {@code null}.
   */
  private static String sanitizedCause(Exception e) {
    if (e == null) {
      return "unknown cause";
    }
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return e.getClass().getSimpleName() + ": " + message.trim();
  }

  /**
   * Sanitizes an upstream error body before it surfaces in exception messages or logs. The
   * configured launcher bearer token and the request body are never disclosed; only a bounded
   * prefix of the response text is retained so actionable status details survive.
   */
  private String sanitizeUpstreamError(String responseBody) {
    String text = responseBody == null ? "" : responseBody.trim();
    if (text.length() > 500) {
      text = text.substring(0, 500) + "…(truncated)";
    }
    if (authToken != null && !authToken.isBlank() && text.contains(authToken)) {
      text = text.replace(authToken, "***");
    }
    return text;
  }

  private void applyAuth(HttpRequest.Builder builder) {
    if (authToken != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
    }
  }

  private boolean booleanValue(JsonNode node, boolean defaultValue) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return defaultValue;
    }
    return node.asBoolean(defaultValue);
  }

  private String normalizeBaseUrl(String value) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private String normalizePath(String value) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return "/";
    }
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String normalizeRequired(String value, String fieldName) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return normalized;
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  /**
   * Copies launcher metadata into an immutable, insertion-ordered snapshot that tolerates null
   * values. {@link Map#copyOf} rejects null entries, but launcher-control legitimately reports
   * null-valued fields such as {@code runtime}.
   */
  private static Map<String, Object> immutableMetadata(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  public record LauncherAccount(
      String accountId,
      String accountHash,
      String displayName,
      String email,
      String status,
      boolean enabled,
      Map<String, Object> metadata) {
    public LauncherAccount {
      metadata = immutableMetadata(metadata);
    }
  }

  public record LauncherMachine(
      String machineUuid,
      String displayName,
      String status,
      boolean enabled,
      boolean online,
      Map<String, Object> metadata) {
    public LauncherMachine {
      metadata = immutableMetadata(metadata);
    }
  }

  public record StartCommandResult(
      String commandId,
      String status,
      String resolvedAccountHash,
      String resolvedFrom,
      String resolvedPathSource) {}

  public record CommandStatus(
      String commandId,
      String status,
      String lastError,
      Map<String, Object> metadata,
      boolean terminal) {
    public CommandStatus {
      metadata = immutableMetadata(metadata);
    }
  }
}
