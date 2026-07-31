package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.integration.adapter.RuntimeAdapterRegistry;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class BuilderGoalCodexOrchestrationService {

  private final BuilderGoalRunnerService builderGoalRunnerService;
  private final RuntimeAdapterRegistry runtimeAdapterRegistry;
  private final DebugSessionRepository debugSessionRepository;
  private final TickSnapshotRepository tickSnapshotRepository;
  private final TickLogRepository tickLogRepository;
  private final TickOverlayRepository tickOverlayRepository;
  private final RecordingSessionRepository recordingSessionRepository;
  private final ObjectMapper objectMapper;
  private final Map<String, CodexSession> sessions = new ConcurrentHashMap<>();

  public BuilderGoalCodexOrchestrationService(
      BuilderGoalRunnerService builderGoalRunnerService,
      RuntimeAdapterRegistry runtimeAdapterRegistry,
      DebugSessionRepository debugSessionRepository,
      TickSnapshotRepository tickSnapshotRepository,
      TickLogRepository tickLogRepository,
      TickOverlayRepository tickOverlayRepository,
      RecordingSessionRepository recordingSessionRepository,
      ObjectMapper objectMapper) {
    this.builderGoalRunnerService = builderGoalRunnerService;
    this.runtimeAdapterRegistry = runtimeAdapterRegistry;
    this.debugSessionRepository = debugSessionRepository;
    this.tickSnapshotRepository = tickSnapshotRepository;
    this.tickLogRepository = tickLogRepository;
    this.tickOverlayRepository = tickOverlayRepository;
    this.recordingSessionRepository = recordingSessionRepository;
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
            accountId, launcherMachineUuid, taskKey, settings, displayName, world, null);
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

  public Map<String, Object> getRunLogs(String sessionId, String iterationId) {
    String resolvedIterationId = resolveIterationId(sessionId, iterationId);
    return builderGoalRunnerService.getRunLogs(resolvedIterationId);
  }

  public Map<String, Object> getReplayContext(String sessionId, String iterationId) {
    String resolvedIterationId = resolveIterationId(sessionId, iterationId);
    Map<String, Object> run = builderGoalRunnerService.getRunSummary(resolvedIterationId);

    String runRecordingId = stringValue(run.get("runRecordingId"));
    String runSessionId = stringValue(run.get("runSessionId"));
    Optional<DebugSession> debugSession =
        firstPresent(
            runSessionId != null
                ? debugSessionRepository.findBySessionId(runSessionId)
                : Optional.empty(),
            runRecordingId != null
                ? debugSessionRepository.findByRecordingId(runRecordingId)
                : Optional.empty());
    String debugSessionId =
        debugSession.map(DebugSession::getSessionId).orElse(runSessionId);
    String recordingId =
        firstNonBlank(
            debugSession.map(DebugSession::getRecordingId).orElse(null),
            runRecordingId);
    Optional<RecordingSession> recordingSession =
        recordingId != null ? recordingSessionRepository.findByRecordingId(recordingId) : Optional.empty();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", resolvedIterationId);
    response.put("run", run);
    response.put("runRecordingId", runRecordingId);
    response.put("runSessionId", runSessionId);
    response.put("recordingId", recordingId);
    response.put("debugSessionId", debugSessionId);
    response.put("recordingAvailable", recordingSession.isPresent());
    response.put("debugSessionAvailable", debugSession.isPresent());
    response.put("recording", recordingSession.map(this::toRecordingSummary).orElse(null));
    response.put("debugSession", debugSession.map(this::toDebugSessionSummary).orElse(null));
    if (debugSessionId != null) {
      response.put("replaySessionInfo", buildReplaySessionInfo(debugSessionId));
    } else {
      response.put("replaySessionInfo", null);
    }
    return response;
  }

  public Map<String, Object> getReplaySessionInfo(String sessionId, String debugSessionId) {
    String resolvedDebugSessionId = resolveDebugSessionId(sessionId, null, debugSessionId);
    return buildReplaySessionInfo(resolvedDebugSessionId);
  }

  public Map<String, Object> getReplayTickRange(
      String sessionId, String debugSessionId, Long startTick, Long endTick) {
    String resolvedDebugSessionId = resolveDebugSessionId(sessionId, null, debugSessionId);
    long resolvedStart = startTick != null ? startTick : 0L;
    Long resolvedEnd = endTick;
    if (resolvedEnd == null) {
      long maxTick = Optional.ofNullable(tickLogRepository.findMaxTickBySessionId(resolvedDebugSessionId))
          .orElseGet(() -> tickSnapshotRepository.findMaxTickBySessionId(resolvedDebugSessionId).orElse(0L));
      resolvedEnd = Math.min(maxTick, resolvedStart + 25);
      if (resolvedEnd < resolvedStart) {
        resolvedEnd = resolvedStart;
      }
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("debugSessionId", resolvedDebugSessionId);
    response.put("startTick", resolvedStart);
    response.put("endTick", resolvedEnd);
    response.put(
        "snapshots",
        tickSnapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            resolvedDebugSessionId, resolvedStart, resolvedEnd));
    response.put(
        "logs",
        tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            resolvedDebugSessionId, resolvedStart, resolvedEnd));
    response.put(
        "overlays",
        tickOverlayRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            resolvedDebugSessionId, resolvedStart, resolvedEnd));
    return response;
  }

  public Map<String, Object> getReplayTick(String sessionId, String debugSessionId, Long tick) {
    if (tick == null) {
      throw new IllegalArgumentException("tick is required");
    }
    String resolvedDebugSessionId = resolveDebugSessionId(sessionId, null, debugSessionId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("debugSessionId", resolvedDebugSessionId);
    response.put("tick", tick);
    response.put(
        "snapshot",
        tickSnapshotRepository.findBySessionIdAndTick(resolvedDebugSessionId, tick).orElse(null));
    response.put("logs", tickLogRepository.findBySessionIdAndTick(resolvedDebugSessionId, tick));
    response.put(
        "overlays",
        tickOverlayRepository.findBySessionIdAndTick(resolvedDebugSessionId, tick));
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

  private Map<String, Object> buildReplaySessionInfo(String debugSessionId) {
    DebugSession session =
        debugSessionRepository
            .findBySessionId(debugSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Debug session not found: " + debugSessionId));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sessionId", session.getSessionId());
    response.put("scriptName", session.getScriptName());
    response.put("accountName", session.getAccountName());
    response.put("recordingId", session.getRecordingId());
    response.put("startTick", session.getStartTick());
    response.put("endTick", session.getEndTick());
    response.put("startTime", session.getStartTime());
    response.put("endTime", session.getEndTime());
    response.put("lifecycleState", session.getLifecycleStateOrDefault().name());
    response.put("failureCode", session.getFailureCode());
    response.put("failureReason", session.getFailureReason());
    response.put("minTick", tickSnapshotRepository.findMinTickBySessionId(debugSessionId).orElse(null));
    response.put("maxTick", tickSnapshotRepository.findMaxTickBySessionId(debugSessionId).orElse(null));
    return response;
  }

  private Map<String, Object> toRecordingSummary(RecordingSession recordingSession) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("recordingId", recordingSession.getRecordingId());
    response.put("accountName", recordingSession.getAccountName());
    response.put("scriptName", recordingSession.getScriptName());
    response.put("startTime", recordingSession.getStartTime());
    response.put("endTime", recordingSession.getEndTime());
    response.put("durationMs", recordingSession.getDurationMs());
    response.put("frameCount", recordingSession.getFrameCount());
    response.put("fps", recordingSession.getFps());
    response.put("videoCodec", recordingSession.getVideoCodec());
    response.put("videoPath", recordingSession.getVideoPath());
    return response;
  }

  private Map<String, Object> toDebugSessionSummary(DebugSession session) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sessionId", session.getSessionId());
    response.put("accountName", session.getAccountName());
    response.put("scriptName", session.getScriptName());
    response.put("recordingId", session.getRecordingId());
    response.put("startTick", session.getStartTick());
    response.put("endTick", session.getEndTick());
    response.put("startTime", session.getStartTime());
    response.put("endTime", session.getEndTime());
    response.put("lifecycleState", session.getLifecycleStateOrDefault().name());
    response.put("failureCode", session.getFailureCode());
    response.put("failureReason", session.getFailureReason());
    return response;
  }

  private String resolveIterationId(String sessionId, String iterationId) {
    if (iterationId != null && !iterationId.isBlank()) {
      return iterationId.trim();
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId or iterationId is required");
    }
    return requireActiveIterationId(requireSession(sessionId));
  }

  private String resolveDebugSessionId(String sessionId, String iterationId, String debugSessionId) {
    if (debugSessionId != null && !debugSessionId.isBlank()) {
      return debugSessionId.trim();
    }
    Map<String, Object> replayContext = getReplayContext(sessionId, iterationId);
    String resolved = stringValue(replayContext.get("debugSessionId"));
    if (resolved == null) {
      throw new IllegalStateException("No debug session is available for this Builder Goal run");
    }
    return resolved;
  }

  @SafeVarargs
  private final <T> Optional<T> firstPresent(Optional<T>... optionals) {
    for (Optional<T> optional : optionals) {
      if (optional != null && optional.isPresent()) {
        return optional;
      }
    }
    return Optional.empty();
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
