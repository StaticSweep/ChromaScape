package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class BuilderGoalCodexOrchestrationService {

  private final BuilderGoalRunnerService builderGoalRunnerService;
  private final RuntimeAdapterRegistry runtimeAdapterRegistry;
  private final ObjectMapper objectMapper;
  private final Map<String, CodexSession> sessions = new ConcurrentHashMap<>();

  public BuilderGoalCodexOrchestrationService(
      BuilderGoalRunnerService builderGoalRunnerService,
      RuntimeAdapterRegistry runtimeAdapterRegistry,
      ObjectMapper objectMapper) {
    this.builderGoalRunnerService = builderGoalRunnerService;
    this.runtimeAdapterRegistry = runtimeAdapterRegistry;
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> createSession() {
    CodexSession session = new CodexSession();
    session.sessionId = "codex_bg_" + UUID.randomUUID().toString().replace("-", "");
    session.createdAt = LocalDateTime.now().toString();
    addMessage(
        session,
        "assistant",
        "Builder Goal Codex session ready. Tell me to run a task, stop the active run, describe the issue, or generate a patch plan.",
        null);
    sessions.put(session.sessionId, session);
    return toSessionResponse(session, true);
  }

  public Map<String, Object> getSession(String sessionId) {
    return toSessionResponse(requireSession(sessionId), true);
  }

  public Map<String, Object> bindRun(String sessionId, String iterationId) {
    CodexSession session = requireSession(sessionId);
    session.activeIterationId = iterationId;
    Map<String, Object> run = builderGoalRunnerService.getRunSummary(iterationId);
    syncSessionFromRun(session, run);
    addMessage(
        session,
        "assistant",
        "Bound this Codex session to Builder Goal run " + iterationId + ".",
        Map.of("iterationId", iterationId, "run", run));
    return toSessionResponse(session, false);
  }

  public Map<String, Object> setIssueStatement(String sessionId, String issueStatement, String context) {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    builderGoalRunnerService.openRecoveryWorkspace(iterationId);
    Map<String, Object> result =
        builderGoalRunnerService.setRecoveryIssueStatement(iterationId, issueStatement, context);
    session.issueStatement = stringValue(result.get("prompt"));
    addMessage(
        session,
        "assistant",
        "Saved the issue statement on the recovery workspace. Mark relevant replay ranges or ask me to generate a patch plan.",
        Map.of("iterationId", iterationId, "issueStatement", session.issueStatement));
    return toSessionResponse(session, false);
  }

  public Map<String, Object> startRun(
      String sessionId,
      String accountId,
      String launcherMachineUuid,
      String taskKey,
      Integer world,
      String displayName,
      Map<String, Object> settings) {
    CodexSession session = requireSession(sessionId);
    Map<String, Object> result =
        builderGoalRunnerService.startCatalogTaskRun(
            accountId, launcherMachineUuid, taskKey, settings, displayName, world);
    @SuppressWarnings("unchecked")
    Map<String, Object> run = (Map<String, Object>) result.get("run");
    if (run != null) {
      syncSessionFromRun(session, run);
    }
    session.selectedAccountId = accountId;
    session.selectedMachineUuid = launcherMachineUuid;
    session.selectedTaskKey = taskKey;
    session.world = world;
    addMessage(
        session,
        "assistant",
        "Started the Builder Goal run and bound this Codex session to it.",
        result);
    return toSessionResponse(session, false);
  }

  public Map<String, Object> getActiveRun(String sessionId) {
    CodexSession session = requireSession(sessionId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sessionId", session.sessionId);
    response.put("activeIterationId", session.activeIterationId);
    if (session.activeIterationId != null) {
      response.put("run", builderGoalRunnerService.getRunSummary(session.activeIterationId));
    } else {
      response.put("run", null);
    }
    response.put("agentState", readAgentState());
    return response;
  }

  public Map<String, Object> stopRun(String sessionId, String reason) {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    JsonNode payload =
        objectMapper.valueToTree(
            Map.of("reason", firstNonBlank(reason, "Stopped from Codex orchestration")));
    Map<String, Object> result = builderGoalRunnerService.stopRun(iterationId, payload);
    addMessage(
        session,
        "assistant",
        "Stop requested for the active Builder Goal run.",
        Map.of("iterationId", iterationId, "result", result));
    return toSessionResponse(session, false);
  }

  public Map<String, Object> openRecoveryWorkspace(String sessionId) {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    Map<String, Object> result = builderGoalRunnerService.openRecoveryWorkspace(iterationId);
    syncSessionFromWorkspace(session, result);
    addMessage(
        session,
        "assistant",
        "Opened the recovery workspace for the active run.",
        Map.of("iterationId", iterationId));
    return result;
  }

  public Map<String, Object> getRecoveryState(String sessionId) {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    return builderGoalRunnerService.getRecoveryState(iterationId);
  }

  public Map<String, Object> refreshEvidence(String sessionId, JsonNode payload) {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    return builderGoalRunnerService.resolveRecoveryEvidence(iterationId, payload);
  }

  public Map<String, Object> generatePatchPlan(String sessionId, JsonNode payload) throws Exception {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    JsonNode effectivePayload = payload;
    if (payload == null || payload.isNull() || !payload.isObject()) {
      effectivePayload =
          objectMapper.valueToTree(
              Map.of(
                  "prompt",
                  firstNonBlank(session.issueStatement, "Create a Micro6 patch plan from the selected evidence."),
                  "context",
                  ""));
    }
    Map<String, Object> result = builderGoalRunnerService.analyzeFailure(iterationId, effectivePayload);
    session.lastPatchPlanStatus = stringValue(result.get("status"));
    addMessage(
        session,
        "assistant",
        stringValue(result.get("patchId")) != null
            ? "Generated a patch plan for the active run."
            : "Analysis completed, but no patch draft was produced.",
        Map.of("iterationId", iterationId, "analysis", result));
    return result;
  }

  public Map<String, Object> approveRecovery(String sessionId) throws Exception {
    CodexSession session = requireSession(sessionId);
    String iterationId = requireActiveIterationId(session);
    Map<String, Object> result = builderGoalRunnerService.approveRecovery(iterationId);
    session.lastChildIterationId = stringValue(result.get("childIterationId"));
    addMessage(
        session,
        "assistant",
        "Approved patch application and relaunch for the active Builder Goal run.",
        result);
    return result;
  }

  public Map<String, Object> processMessage(String sessionId, String message, JsonNode payload)
      throws Exception {
    CodexSession session = requireSession(sessionId);
    String trimmed = message == null ? "" : message.trim();
    addMessage(session, "user", trimmed, null);
    String normalized = trimmed.toLowerCase();

    if (normalized.contains("stop")) {
      stopRun(sessionId, "Stopped from Codex chat");
      return toSessionResponse(session, false);
    }
    if (normalized.startsWith("run") || normalized.startsWith("start")) {
      Map<String, Object> startContext =
          payload != null && payload.isObject()
              ? objectMapper.convertValue(payload, Map.class)
              : Map.of();
      startRun(
          sessionId,
          stringValue(startContext.get("accountId")),
          stringValue(startContext.get("launcherMachineUuid")),
          stringValue(startContext.get("taskKey")),
          intValue(startContext.get("world")),
          stringValue(startContext.get("displayName")),
          mapValue(startContext.get("settings")));
      return toSessionResponse(session, false);
    }
    if (normalized.contains("generate") && normalized.contains("patch")) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("prompt", firstNonBlank(session.issueStatement, trimmed));
      if (payload != null && payload.isObject()) {
        body.putAll(objectMapper.convertValue(payload, Map.class));
      }
      generatePatchPlan(sessionId, objectMapper.valueToTree(body));
      return toSessionResponse(session, false);
    }
    if (normalized.contains("approve") || normalized.contains("apply patch")) {
      approveRecovery(sessionId);
      return toSessionResponse(session, false);
    }
    if (normalized.contains("evidence") || normalized.contains("recovery")) {
      openRecoveryWorkspace(sessionId);
      return toSessionResponse(session, false);
    }

    setIssueStatement(sessionId, trimmed, payload != null ? textValue(payload, "context") : null);
    return toSessionResponse(session, false);
  }

  public Map<String, Object> getContext(String sessionId) {
    CodexSession session = requireSession(sessionId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sessionId", session.sessionId);
    response.put("activeIterationId", session.activeIterationId);
    response.put("selectedAccountId", session.selectedAccountId);
    response.put("selectedMachineUuid", session.selectedMachineUuid);
    response.put("selectedTaskKey", session.selectedTaskKey);
    response.put("world", session.world);
    response.put("activeRecordingId", session.activeRecordingId);
    response.put("activeDebugSessionId", session.activeDebugSessionId);
    response.put("issueStatement", session.issueStatement);
    response.put("lastPatchPlanStatus", session.lastPatchPlanStatus);
    response.put("lastChildIterationId", session.lastChildIterationId);
    response.put("agentState", readAgentState());
    if (session.activeIterationId != null) {
      response.put("run", builderGoalRunnerService.getRunSummary(session.activeIterationId));
    }
    return response;
  }

  private Map<String, Object> toSessionResponse(CodexSession session, boolean includeMessages) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sessionId", session.sessionId);
    response.put("createdAt", session.createdAt);
    response.put("activeIterationId", session.activeIterationId);
    response.put("selectedAccountId", session.selectedAccountId);
    response.put("selectedMachineUuid", session.selectedMachineUuid);
    response.put("selectedTaskKey", session.selectedTaskKey);
    response.put("world", session.world);
    response.put("activeRecordingId", session.activeRecordingId);
    response.put("activeDebugSessionId", session.activeDebugSessionId);
    response.put("issueStatement", session.issueStatement);
    response.put("lastPatchPlanStatus", session.lastPatchPlanStatus);
    response.put("lastChildIterationId", session.lastChildIterationId);
    response.put("agentState", readAgentState());
    if (includeMessages) {
      response.put("messages", session.messages);
    } else {
      response.put("messages", List.of(session.messages.get(session.messages.size() - 1)));
    }
    if (session.activeIterationId != null) {
      response.put("run", builderGoalRunnerService.getRunSummary(session.activeIterationId));
    } else {
      response.put("run", null);
    }
    return response;
  }

  private void syncSessionFromRun(CodexSession session, Map<String, Object> run) {
    if (run == null) {
      return;
    }
    session.activeIterationId = stringValue(run.get("iterationId"));
    session.activeRecordingId = stringValue(run.get("runRecordingId"));
    session.activeDebugSessionId = stringValue(run.get("runSessionId"));
  }

  private void syncSessionFromWorkspace(CodexSession session, Map<String, Object> workspace) {
    if (workspace == null) {
      return;
    }
    session.activeRecordingId = firstNonBlank(stringValue(workspace.get("recordingId")), session.activeRecordingId);
    session.activeDebugSessionId =
        firstNonBlank(stringValue(workspace.get("debugSessionId")), session.activeDebugSessionId);
    session.issueStatement =
        firstNonBlank(stringValue(workspace.get("prompt")), session.issueStatement);
  }

  private Map<String, Object> readAgentState() {
    Map<String, Object> response = new LinkedHashMap<>();
    var adapter = runtimeAdapterRegistry.getAdapter("microbot-agent");
    if (adapter.isEmpty()) {
      response.put("available", false);
      response.put("adapterId", "microbot-agent");
      return response;
    }
    response.put("available", adapter.get().isAvailable());
    response.put("adapterId", adapter.get().getAdapterId());
    response.put("displayName", adapter.get().getDisplayName());
    try {
      response.put("snapshot", adapter.get().fetchStateSnapshot());
    } catch (Exception e) {
      response.put("error", e.getMessage());
    }
    return response;
  }

  private void addMessage(
      CodexSession session, String role, String content, Map<String, Object> actionResult) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("id", UUID.randomUUID().toString());
    message.put("role", role);
    message.put("content", content);
    message.put("timestamp", LocalDateTime.now().toString());
    if (actionResult != null && !actionResult.isEmpty()) {
      message.put("actionResult", actionResult);
    }
    session.messages.add(message);
  }

  private CodexSession requireSession(String sessionId) {
    CodexSession session = sessions.get(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("Codex session not found: " + sessionId);
    }
    return session;
  }

  private String requireActiveIterationId(CodexSession session) {
    if (session.activeIterationId == null || session.activeIterationId.isBlank()) {
      throw new IllegalStateException("This Codex session is not bound to an active Builder Goal run");
    }
    return session.activeIterationId;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text.isBlank() ? null : text.trim();
  }

  private Integer intValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mapValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return new LinkedHashMap<>((Map<String, Object>) map);
    }
    return Map.of();
  }

  private String textValue(JsonNode payload, String field) {
    if (payload == null || payload.isNull()) {
      return null;
    }
    JsonNode node = payload.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private static class CodexSession {
    private String sessionId;
    private String createdAt;
    private String activeIterationId;
    private String selectedAccountId;
    private String selectedMachineUuid;
    private String selectedTaskKey;
    private Integer world;
    private String activeRecordingId;
    private String activeDebugSessionId;
    private String issueStatement;
    private String lastPatchPlanStatus;
    private String lastChildIterationId;
    private final List<Map<String, Object>> messages = new ArrayList<>();
  }
}
