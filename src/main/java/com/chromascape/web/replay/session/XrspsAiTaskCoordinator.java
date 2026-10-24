package com.chromascape.web.replay.session;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.*;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.*;
import com.chromascape.web.replay.error.*;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

/**
 * Configured decide-only provider -> explicit native Fishing adapter -> verified isolated outcome.
 */
@RestController
@RequestMapping("/api/xrsps/sessions")
public class XrspsAiTaskCoordinator {
  private final XrspsSessionRepository repository;
  private final XrspsDurableSessionService sessions;
  private final GameplayProviderRuntime runtime;
  private final ProviderRuntimeValidator validator;
  private final ReplayPrincipalService principals;
  private final ObjectMapper mapper;
  private final CanonicalGameplayJson json;
  private final CanonicalProjectionService domain = new CanonicalProjectionService();
  private final String coordinator = UUID.randomUUID().toString();
  private final Set<UUID> active = ConcurrentHashMap.newKeySet();

  public XrspsAiTaskCoordinator(
      XrspsSessionRepository repository,
      XrspsDurableSessionService sessions,
      GameplayProviderRuntime runtime,
      ProviderRuntimeValidator validator,
      ReplayPrincipalService principals,
      ObjectMapper mapper,
      CanonicalGameplayJson json) {
    this.repository = repository;
    this.sessions = sessions;
    this.runtime = runtime;
    this.validator = validator;
    this.principals = principals;
    this.mapper = mapper;
    this.json = json;
  }

  public record FishingGoal(
      Provider provider, String modelId, int targetLevel, int maxTicks, int maxSeconds) {}

  @PostMapping("/{id}/ai-recover")
  public XrspsSessionsController.SessionView recover(@PathVariable UUID id, Principal principal) {
    var current = sessions.getOwned(id, principals.requirePrincipal(principal));
    if (current.getMode() != XrspsSessionMode.AI_TASK)
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID, "This session does not own an AI task");
    var recovered = sessions.recover(id, current.getLeaseId());
    repository.update(
        id,
        doc -> {
          if (!doc.lifecycle.leaseId().equals(recovered.getLeaseId()))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID, "Recovered lease was superseded");
          doc.aiCoordinator = coordinator;
          return null;
        });
    active.add(id);
    return XrspsSessionsController.SessionView.of(recovered);
  }

  @PostMapping("/{id}/ai-task")
  public Map<String, Object> start(
      @PathVariable UUID id, @RequestBody FishingGoal goal, Principal principal) {
    sessions.getOwned(id, principals.requirePrincipal(principal));
    var readiness = runtime.readiness();
    if (!readiness.enabled()
        || goal.provider() == null
        || !readiness.providers().containsKey(goal.provider())
        || !readiness.providers().get(goal.provider()).available())
      throw ReplayException.unavailable(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          "Configured AI provider is unavailable",
          "Configure the existing provider runtime and an allowlisted model");
    repository.update(
        id,
        doc -> {
          prepare(doc, goal);
          return null;
        });
    active.add(id);
    return Map.of("sessionId", id, "adapter", "native-fishing-v1", "taskVerified", false);
  }

  @GetMapping("/{id}/ai-task")
  public JsonNode status(@PathVariable UUID id, Principal principal) {
    var doc = repository.getOwned(id, principals.requirePrincipal(principal));
    ObjectNode result = mapper.createObjectNode();
    result.set("goal", doc.aiTask);
    result.set("decision", doc.aiDecision);
    result.set("verification", doc.aiVerification);
    result.put("status", doc.lifecycle.status().name());
    result.put("active", active.contains(id));
    return result;
  }

  void prepare(XrspsSessionDocument doc, FishingGoal goal) {
    if (doc.lifecycle.mode() != XrspsSessionMode.AI_TASK
        || doc.lifecycle.status() != XrspsSessionStatus.READY
        || present(doc.aiTask))
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID, "Configure a fresh, ready AI session");
    int current =
        doc.observation.path("player").path("skills").path("fishing").path("level").asInt();
    long end =
        doc.manifest.path("recording").path("endTick").asLong()
            - doc.manifest.path("recording").path("startTick").asLong();
    if (goal.targetLevel() <= current
        || goal.targetLevel() > 99
        || goal.maxTicks() < 1
        || goal.maxTicks() > Math.min(10000, end)
        || goal.maxSeconds() < 30
        || goal.maxSeconds() > 3600)
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR,
          "Fishing goal must exceed the initial level and fit bounded scenario ticks and 30-3600"
              + " seconds");
    JsonNode action = null;
    for (JsonNode candidate : doc.manifest.path("actions")) {
      if ("INTERACT_NPC".equals(candidate.path("type").asText())
          && "net".equalsIgnoreCase(candidate.path("option").asText())) {
        action = candidate.deepCopy();
        break;
      }
    }
    if (action == null || !doc.legalActions.contains("INTERACT_NPC"))
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "Native Fishing requires a pinned net interaction in this scenario");
    JsonNode spot = null;
    for (JsonNode candidate : doc.manifest.path("initialState").path("npcs"))
      if (candidate.path("stableId").asText().equals(action.path("targetStableId").asText())
          && "sea_small_net".equals(candidate.path("spotId").asText())) spot = candidate;
    if (spot == null)
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED,
          "The task adapter requires a pinned production sea small-net spot");
    JsonNode nativeTarget = null;
    for (JsonNode candidate : doc.observation.path("npcs"))
      if (candidate.path("stableId").asText().equals(action.path("targetStableId").asText()))
        nativeTarget = candidate;
    var position = doc.observation.path("player").path("position");
    boolean net = false;
    for (JsonNode item : doc.observation.path("player").path("inventory"))
      if (item.path("itemId").asInt() == 303 && item.path("quantity").asInt() > 0) net = true;
    if (nativeTarget == null
        || nativeTarget.path("position").path("plane").asInt() != position.path("plane").asInt()
        || Math.max(
                Math.abs(
                    nativeTarget.path("position").path("x").asInt() - position.path("x").asInt()),
                Math.abs(
                    nativeTarget.path("position").path("y").asInt() - position.path("y").asInt()))
            > 1
        || !net
        || doc.observation.path("player").path("inventory").size() >= 28)
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_PRECONDITION_FAILED,
          "The native Fishing task requires an adjacent pinned spot, a net and inventory space");
    String area = spot.path("position").path("x").asInt() < 3100 ? "DRAYNOR" : "LUMBRIDGE";
    // The production service validates the exact target, adjacency, equipment and inventory again.
    ObjectNode task = mapper.valueToTree(goal);
    task.set("nativeAction", action);
    task.put(
        "initialXp",
        doc.observation.path("player").path("skills").path("fishing").path("xp").asLong());
    task.put("initialItems", itemCount(doc.observation));
    task.put("epoch", doc.lifecycle.epoch());
    task.put("lease", doc.lifecycle.leaseId());
    task.put("revision", doc.lifecycle.observationRevision());
    task.put("domainHash", domain.stateHash(doc.observation));
    task.put("deadline", Instant.now().plusSeconds(goal.maxSeconds()).toString());
    String providerId = "xrsps-" + doc.lifecycle.id(),
        turnId = "turn-1",
        obsId = "obs-" + doc.lifecycle.id();
    JsonNode observation = providerObservation(doc, obsId);
    ObjectNode semantic = mapper.createObjectNode();
    semantic.put("kind", "START_FISHING_TASK").put("taskKey", "FISHING_TRAINING");
    semantic.putObject("settings").put("targetLevel", goal.targetLevel()).put("areaPolicy", area);
    semantic
        .put("target", "FISHING")
        .put("expectedTransition", "TARGET_LEVEL_REACHED")
        .put("verifierId", "xrsps-fishing-domain-v1")
        .put("decisionId", "decision-" + doc.lifecycle.id());
    semantic.putArray("preconditions").add("OBSERVATION_CURRENT").add("ISOLATED_WORLD");
    semantic.putArray("evidenceRefs").add("observation:" + obsId);
    var eligible = mapper.createArrayNode().add(semantic);
    eligible
        .addObject()
        .put("kind", "ABSTAIN")
        .put("decisionId", "abstain-" + doc.lifecycle.id())
        .put("reason", "LOW_CONFIDENCE");
    Instant providerDeadline = Instant.now().plusSeconds(Math.min(120, goal.maxSeconds()));
    String adapterDigest =
        json.digest(
            Map.of(
                "adapter",
                "native-fishing-v1",
                "nativeAction",
                action,
                "runtime",
                doc.manifest.path("runtime")));
    var request =
        new SessionRequest(
            providerId,
            goal.provider(),
            goal.modelId(),
            "FISHING_TRAINING",
            doc.lifecycle.id().toString(),
            doc.manifest.path("recording").path("recordingId").asText(),
            json.digest(semantic),
            json.digest(eligible),
            adapterDigest,
            json.digest(task),
            List.of(adapterDigest),
            providerDeadline,
            new BudgetLimits(16000, 4000, 1, 2, 1, 120000, 120000),
            true);
    var preliminary =
        new TurnRequest(
            turnId,
            "request-" + doc.lifecycle.id(),
            "decision-" + doc.lifecycle.id(),
            null,
            observation,
            json.digest(observation),
            eligible,
            json.digest(eligible),
            observation.path("identities"),
            "xrsps-offline",
            12000,
            2000,
            providerDeadline,
            true);
    var turn =
        new TurnRequest(
            preliminary.turnId(),
            preliminary.requestId(),
            preliminary.idempotencyKey(),
            validator.fingerprint(request, preliminary),
            observation,
            preliminary.observationDigest(),
            eligible,
            preliminary.eligibilityDigest(),
            preliminary.identities(),
            preliminary.sourceId(),
            preliminary.estimatedInputTokens(),
            preliminary.estimatedOutputTokens(),
            providerDeadline,
            true);
    validator.validateSession(request);
    validator.validateTurn(request, turn);
    doc.aiTask = task;
    doc.aiProviderSession = mapper.valueToTree(request);
    ((ObjectNode) doc.aiProviderSession).put("deadline", providerDeadline.toString());
    doc.aiProviderTurn = mapper.valueToTree(turn);
    ((ObjectNode) doc.aiProviderTurn).put("deadline", providerDeadline.toString());
    doc.aiCoordinator = coordinator;
    var session = XrspsSession.restore(doc.lifecycle);
    session.start();
    doc.lifecycle = session.snapshot();
  }

  JsonNode providerObservation(XrspsSessionDocument doc, String observationId) {
    var source = doc.observation;
    var player = source.path("player");
    var position = player.path("position");
    ObjectNode result = mapper.createObjectNode();
    result
        .put("contractVersion", "sc-1429/v1")
        .put("observationId", observationId)
        .put("revision", doc.lifecycle.observationRevision())
        .put("capturedAt", Instant.now().toString())
        .put("capturedTick", source.path("tick").asLong());
    var ids = result.putObject("identities");
    String id = doc.lifecycle.id().toString();
    ids.put("accountId", "isolated-" + id)
        .put("clientId", "xrsps-" + id)
        .put("environmentId", "xrsps-offline")
        .put("runId", id)
        .put("taskId", "FISHING_TRAINING")
        .put("sessionId", id)
        .put("recordingId", doc.manifest.path("recording").path("recordingId").asText())
        .put("sourceId", "xrsps-offline");
    result
        .put("catalogDigest", json.digest(doc.legalActions))
        .put("buildId", doc.manifest.path("runtime").path("xrspsBuildCommit").asText());
    var projected = result.putObject("player");
    projected.put(
        "worldPoint",
        position.path("x").asInt()
            + ","
            + position.path("y").asInt()
            + ","
            + position.path("plane").asInt());
    projected.put("animation", player.path("animation").asInt(-1));
    if (player.path("interaction").isTextual())
      projected.put("interactingTarget", player.path("interaction").asText());
    else projected.putNull("interactingTarget");
    var inventory = result.putArray("inventory");
    for (JsonNode item : player.path("inventory"))
      inventory
          .addObject()
          .put("itemId", item.path("itemId").asInt())
          .put("quantity", item.path("quantity").asInt());
    var skill = player.path("skills").path("fishing");
    result
        .putObject("skills")
        .put("fishingLevel", skill.path("level").asInt())
        .put("fishingXp", skill.path("xp").asLong());
    result.putArray("eligibleTargets").add("FISHING_TRAINING");
    result
        .putObject("taskState")
        .put("revision", doc.lifecycle.observationRevision())
        .put("status", "IDLE")
        .putNull("taskKey");
    result
        .putObject("confidence")
        .put("adapterObservationConfidence", 1)
        .put("knowledgeFreshnessConfidence", 1)
        .put("complete", true);
    return result;
  }

  @Scheduled(fixedDelay = 600)
  public void tick() {
    for (UUID id : active)
      try {
        var doc = repository.get(id);
        if (!coordinator.equals(doc.aiCoordinator)
            || doc.lifecycle.status().isTerminal()
            || doc.lifecycle.status() == XrspsSessionStatus.RECONCILING) {
          if (doc.lifecycle.status().isTerminal()
              && present(doc.aiProviderTurn)
              && !present(doc.aiDecision))
            try {
              runtime.cancel(
                  doc.aiProviderSession.path("sessionId").asText(),
                  doc.aiProviderTurn.path("turnId").asText(),
                  "Isolated native session stopped");
            } catch (ProviderRuntimeException ignored) {
              /* A turn may not have been submitted yet. */
            }
          active.remove(id);
          continue;
        }
        if (doc.lifecycle.status() != XrspsSessionStatus.RUNNING) continue;
        if (Instant.now().isAfter(Instant.parse(doc.aiTask.path("deadline").asText())))
          throw new IllegalStateException("AI task deadline expired");
        if (!present(doc.aiDecision)) {
          var request = mapper.convertValue(doc.aiProviderSession, SessionRequest.class);
          var turn = mapper.convertValue(doc.aiProviderTurn, TurnRequest.class);
          runtime.createSession(request);
          runtime.submitTurn(request.sessionId(), turn);
          var result = runtime.turn(request.sessionId(), turn.turnId());
          if (!result.state().terminal()) continue;
          if (result.state() == TurnState.ABSTAINED) {
            repository.update(
                id,
                current -> {
                  var stopped = XrspsSession.restore(current.lifecycle);
                  stopped.cancel("Configured provider abstained before native dispatch");
                  current.lifecycle = stopped.snapshot();
                  return null;
                });
            active.remove(id);
            continue;
          }
          var decision =
              runtime
                  .findValidatedDecision(request.sessionId(), turn.turnId())
                  .orElseThrow(
                      () ->
                          new ProviderRuntimeException(
                              result.failureCode().name(),
                              "Provider did not persist a validated decision: " + result.state()));
          repository.update(
              id,
              current -> {
                accept(current, decision);
                return null;
              });
        } else
          repository.update(
              id,
              current -> {
                advanceTask(current);
                return null;
              });
      } catch (RuntimeException failure) {
        active.remove(id);
        try {
          repository.update(
              id,
              doc -> {
                if (coordinator.equals(doc.aiCoordinator)
                    && doc.lifecycle.status() == XrspsSessionStatus.RUNNING) {
                  var session = XrspsSession.restore(doc.lifecycle);
                  String code =
                      failure instanceof ProviderRuntimeException providerFailure
                          ? providerFailure.failureCode()
                          : failure instanceof ReplayException replayFailure
                              ? replayFailure.getErrorCode().name()
                              : failure.getClass().getSimpleName();
                  session.fail(
                      "Configured AI execution failed ("
                          + code
                          + "); review the durable provider turn and checkpoint");
                  doc.lifecycle = session.snapshot();
                }
                return null;
              });
        } catch (RuntimeException ignored) {
        }
      }
  }

  void accept(XrspsSessionDocument doc, ProviderDecisionHandoff.PersistedDecision decision) {
    var task = doc.aiTask;
    var turn = doc.aiProviderTurn;
    if (!coordinator.equals(doc.aiCoordinator)
        || doc.lifecycle.status() != XrspsSessionStatus.RUNNING
        || !decision.bindable()
        || !decision.sessionId().equals(doc.aiProviderSession.path("sessionId").asText())
        || !decision.turnId().equals(turn.path("turnId").asText())
        || !decision.requestId().equals(turn.path("requestId").asText())
        || !decision.provider().name().equals(doc.aiProviderSession.path("provider").asText())
        || !decision.modelId().equals(doc.aiProviderSession.path("modelId").asText())
        || !decision.promptDigest().equals(doc.aiProviderSession.path("promptDigest").asText())
        || !decision.toolsetDigest().equals(doc.aiProviderSession.path("toolsetDigest").asText())
        || !decision
            .knowledgeDigest()
            .equals(doc.aiProviderSession.path("knowledgeDigest").asText())
        || !decision.goalPlanDigest().equals(doc.aiProviderSession.path("goalPlanDigest").asText())
        || !mapper
            .valueToTree(decision.taskSkillDigests())
            .equals(doc.aiProviderSession.path("taskSkillDigests"))
        || !decision.observationDigest().equals(turn.path("observationDigest").asText())
        || task.path("epoch").asLong() != doc.lifecycle.epoch()
        || !task.path("lease").asText().equals(doc.lifecycle.leaseId())
        || task.path("revision").asLong() != doc.lifecycle.observationRevision()
        || !task.path("domainHash").asText().equals(domain.stateHash(doc.observation))
        || !decision.decision().semanticAction().equals(turn.path("eligibleActions").get(0)))
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID,
          "AI decision is stale or differs from the pinned task scope");
    if (present(doc.aiDecision)) return;
    var payload = task.path("nativeAction").deepCopy();
    var action =
        new XrspsRecordedAction(
            doc.lifecycle.tick(),
            UUID.nameUUIDFromBytes(
                    (doc.lifecycle.id() + ":ai:1")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString(),
            payload.path("type").asText(),
            domain.actionDigest(payload),
            doc.lifecycle.observationRevision(),
            payload);
    doc.journal.add(action);
    var receipt = sessions.apply(doc, action, false);
    if (receipt == null || receipt.outcome() != XrspsReceiptOutcome.ACCEPTED)
      throw ReplayException.conflict(
          ReplayErrorCode.ACTION_PRECONDITION_FAILED, "Native task initiation failed");
    doc.aiDecision = mapper.valueToTree(decision);
  }

  void advanceTask(XrspsSessionDocument doc) {
    if (!coordinator.equals(doc.aiCoordinator)
        || doc.lifecycle.status() != XrspsSessionStatus.RUNNING) return;
    var task = doc.aiTask;
    var session = XrspsSession.restore(doc.lifecycle);
    if (!task.path("lease").asText().equals(session.getLeaseId())
        || task.path("epoch").asLong() != session.getEpoch())
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID,
          "AI execution lease was fenced; start a new bounded task");
    var skill = doc.observation.path("player").path("skills").path("fishing");
    if (skill.path("level").asInt() >= task.path("targetLevel").asInt()
        && skill.path("xp").asLong() > task.path("initialXp").asLong()
        && itemCount(doc.observation) > task.path("initialItems").asLong()) {
      String replayHash = sessions.verifyAiJournal(doc);
      ObjectNode proof = mapper.createObjectNode();
      proof
          .put("taskVerified", true)
          .put("journalReproduced", true)
          .put("domainStateHash", replayHash)
          .put("tick", session.getTick())
          .put("fishingLevel", skill.path("level").asInt())
          .put("fishingXp", skill.path("xp").asLong());
      doc.aiVerification = proof;
      session.complete(
          "Native Fishing goal verified and journal reproduced without another model call");
      doc.lifecycle = session.snapshot();
      return;
    }
    if (session.getTick() >= task.path("maxTicks").asInt()
        || Instant.now().isAfter(Instant.parse(task.path("deadline").asText()))) {
      session.fail("AI task budget exhausted before native outcome verification");
      doc.lifecycle = session.snapshot();
      return;
    }
    if (!doc.observation.path("taskState").path("fishingActive").asBoolean()) {
      session.fail(
          "Native Fishing stopped before the declared goal; no synthetic outcome or unsupported"
              + " banking fallback");
      doc.lifecycle = session.snapshot();
      return;
    }
    sessions.advance(doc, session.getTick() + 1, false);
  }

  private static boolean present(JsonNode value) {
    return value != null && !value.isNull();
  }

  private long itemCount(JsonNode observation) {
    long count = 0;
    for (JsonNode item : observation.path("player").path("inventory"))
      if (item.path("itemId").asInt() != 303) count += item.path("quantity").asLong();
    return count;
  }
}
