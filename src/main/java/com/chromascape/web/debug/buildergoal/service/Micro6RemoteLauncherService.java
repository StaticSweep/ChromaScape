package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Explicitly configured remote destination; heartbeat payloads can never choose an HTTP target. */
@Service
public class Micro6RemoteLauncherService {
  public static final String CAPABILITY = "builder-goal-launch-v2";
  private final ObjectMapper mapper;
  private final Micro6OnlineClientRegistryService registry;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String machineUuid;
  private final String endpoint;
  private final String executorUrl;
  private final String token;
  private final long timeoutSeconds;
  private volatile Instant lastHeartbeat = Instant.EPOCH;
  private volatile String agentVersion = "unknown";

  public Micro6RemoteLauncherService(
      ObjectMapper mapper, Micro6OnlineClientRegistryService registry,
      @Value("${builder-goal.remote.machine-uuid:}") String machineUuid,
      @Value("${builder-goal.remote.endpoint:}") String endpoint,
      @Value("${builder-goal.remote.executor-url:}") String executorUrl,
      @Value("${builder-goal.remote.token:}") String token,
      @Value("${builder-goal.remote.timeout-seconds:1800}") long timeoutSeconds) {
    this.mapper = mapper;
    this.registry = registry;
    this.machineUuid = machineUuid.trim();
    this.endpoint = endpoint.replaceAll("/+$", "");
    this.executorUrl = executorUrl.replaceAll("/+$", "");
    this.token = token;
    this.timeoutSeconds = Math.max(30, timeoutSeconds);
    if (!this.machineUuid.isBlank()) {
      java.util.UUID.fromString(this.machineUuid);
      validateUrl(this.endpoint);
      validateUrl(this.executorUrl);
      if (token.length() < 32) {
        throw new IllegalArgumentException("Remote launcher token must contain at least 32 characters");
      }
    }
  }

  private static void validateUrl(String value) {
    URI uri = URI.create(value);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
        || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("Remote launcher URLs must be configured HTTP(S) origins");
    }
  }

  public boolean authenticate(String authorization) {
    return !machineUuid.isBlank() && token.length() >= 32 && authorization != null
        && MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8));
  }

  public synchronized void heartbeat(JsonNode payload) {
    if (!machineUuid.equals(payload.path("machine_uuid").asText())
        || payload.path("contract_version").asInt() != 2
        || !payload.path("capabilities").isArray()
        || !mapper.convertValue(payload.path("capabilities"), List.class).contains(CAPABILITY)
        || !endpoint.equals(payload.path("launcher_endpoint").asText())
        || !executorUrl.equals(payload.path("executor_url").asText())) {
      throw new IllegalArgumentException("Remote agent identity or capability does not match configuration");
    }
    String version = payload.path("agent_version").asText();
    if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
      throw new IllegalArgumentException("Invalid remote agent version");
    }
    agentVersion = version;
    lastHeartbeat = Instant.now();
  }

  public boolean isConfiguredMachine(BuilderGoalLauncherControlService.LauncherMachine machine) {
    return !machineUuid.isBlank() && machineUuid.equals(machine.machineUuid());
  }

  public boolean supports(BuilderGoalLauncherControlService.LauncherMachine machine) {
    return isConfiguredMachine(machine) && online();
  }

  private boolean online() {
    return lastHeartbeat.isAfter(Instant.now().minusSeconds(35));
  }

  public List<BuilderGoalLauncherControlService.LauncherMachine> machines() {
    if (machineUuid.isBlank()) {
      return List.of();
    }
    return List.of(new BuilderGoalLauncherControlService.LauncherMachine(
        machineUuid, "Fedora Micro6", online() ? "online" : "offline", true, online(),
        Map.of("launcher_endpoint", endpoint, "agent_version", agentVersion,
            "capabilities", List.of(CAPABILITY), "contract_version", 2)));
  }

  public Micro6TargetPreparationService.PreparationResult ensureReady(
      Micro6TargetPreparationService.PreparationRequest request,
      BuilderGoalLauncherControlService.LauncherMachine machine, String commandId,
      BuilderGoalLaunchCancellation cancellation,
      Consumer<Micro6TargetRelaunchService.RelaunchProgress> listener) {
    if (!supports(machine)) {
      throw new IllegalStateException("Remote launcher is offline or unsupported");
    }
    if (request.world() == null || !AccountIdentityContract.isCanonicalHash(request.accountHash())) {
      throw new IllegalArgumentException("Remote launch requires a canonical account hash and world");
    }
    Instant started = Instant.now();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("command_id", commandId);
    payload.put("machine_uuid", machineUuid);
    payload.put("account_hash", request.accountHash());
    payload.put("world", request.world());
    payload.put("force", request.forceRelaunch());
    payload.put("clientArgs", List.of("--clean-jagex-launcher", "--auto-enable-accountbuilder",
        "--profile=" + request.accountName(), "--expected-account-hash=" + request.accountHash(),
        "--launch-attempt-id=" + commandId,
        "--profile-email=" + request.accountEmail() + ":" + request.accountPassword(),
        "--world=" + request.world()));
    String logPath = null;
    boolean submitted = false;
    try {
      cancellation.throwIfCancelled();
      // A lost response can still mean the process was accepted. Cleanup always uses this same ID.
      emit(listener, "preparing", null, commandId);
      submitted = true;
      JsonNode status;
      int conflicts = 0;
      while (true) {
        cancellation.throwIfCancelled();
        try {
          status = exchange("POST", "/v1/launches", payload);
          break;
        } catch (RemoteConflict conflict) {
          if (!request.forceRelaunch() || ++conflicts >= 15) {
            throw conflict;
          }
          Thread.sleep(1000);
        }
      }
      Instant deadline = started.plusSeconds(timeoutSeconds);
      while (Instant.now().isBefore(deadline)) {
        cancellation.throwIfCancelled();
        if (!online()) {
          throw new IllegalStateException("Remote launcher heartbeat expired; relaunch on the same host");
        }
        if (!commandId.equals(status.path("command_id").asText())
            || !machineUuid.equals(status.path("machine_uuid").asText())) {
          throw new IllegalStateException("Remote launch status identity mismatch");
        }
        logPath = status.path("log_path").asText(null);
        String state = status.path("status").asText();
        if (List.of("failed", "exited", "stopped", "interrupted").contains(state)) {
          throw new IllegalStateException("Remote launch ended: " + state + "; relaunch on the same host");
        }
        emit(listener, state, logPath, commandId);
        var ready = registry.findRemoteReadyClient(machineUuid, request.accountHash(), request.world(),
            commandId, started);
        if (ready.isPresent() && "running".equals(state)) {
          var target = ready.get();
          if (!executorUrl.equals(target.advertisedBaseUrl())) {
            throw new IllegalStateException("Remote executor advertised an unexpected endpoint");
          }
          cancellation.throwIfCancelled();
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("state", Micro6TargetPreparationService.PHASE_READY_TO_PUBLISH);
          metadata.put("launch_mode", "remote_micro6_agent");
          metadata.put("launcher_machine_uuid", machineUuid);
          metadata.put("launcher_endpoint", endpoint);
          metadata.put("agent_version", agentVersion);
          metadata.put("agent_capability", CAPABILITY);
          metadata.put("launcher_command_id", commandId);
          metadata.put("attempt_id", commandId);
          metadata.put("launcher_log_file", logPath);
          metadata.put("local_executor_base_url", executorUrl);
          metadata.put("readiness_phase", "READY");
          metadata.put("reused_executor", false);
          metadata.put("world", request.world());
          return new Micro6TargetPreparationService.PreparationResult(target, metadata, null, null);
        }
        Thread.sleep(1000);
        status = exchange("GET", "/v1/launches/" + commandId, null);
      }
      throw new IllegalStateException("Remote executor readiness timed out; relaunch on the same host");
    } catch (Exception failure) {
      // Clear interruption only for the bounded stop request, then restore it for the caller.
      boolean interrupted = Thread.interrupted() || failure instanceof InterruptedException;
      if (submitted) {
        try {
          exchange("POST", "/v1/launches/" + commandId + "/stop", Map.of());
        } catch (Exception ignored) {
          // Agent loss remains recoverable; its systemd cgroup owns the process tree.
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      if (failure instanceof BuilderGoalLaunchCancelledException cancelled) {
        throw cancelled;
      }
      cancellation.throwIfCancelled();
      throw new Micro6TargetPreparationService.PreparationException(
          "REMOTE_LAUNCH_RECOVERABLE",
          "Remote launch did not become ready; relaunch on the same Fedora host. Command " + commandId,
          null, false);
    }
  }

  private void emit(Consumer<Micro6TargetRelaunchService.RelaunchProgress> listener,
      String state, String logPath, String commandId) {
    if (listener != null) {
      listener.accept(new Micro6TargetRelaunchService.RelaunchProgress(
          "preparing".equals(state) ? "BUILDING_MICRO6" : "WAITING_FOR_DISPATCH",
          "Remote Fedora agent: " + state, executorUrl, null, null, null, null, null, null,
          null, null, null, null, null, null, ":client:runBuilderGoal", null, false,
          logPath, null, null, commandId));
    }
  }

  private static class RemoteConflict extends IllegalStateException {}

  private JsonNode exchange(String method, String path, Object body) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json");
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 409) {
      throw new RemoteConflict();
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("Remote launcher HTTP " + response.statusCode());
    }
    return mapper.readTree(response.body());
  }
}
