package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryStates;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalCandidateRerun;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6CandidateArtifact;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalCandidateRerunRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6CandidateArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate-bound rerun: creates exactly one child Builder Goal run using the exact verified
 * candidate digest after an explicit operator action. Account, task, settings, world, machine, and
 * evidence lineage are copied server-side from the parent run; the browser cannot substitute them.
 * The parent failure and its artifacts are never modified.
 */
@Service
public class BuilderGoalCandidateRerunService {

  private static final Logger logger =
      LoggerFactory.getLogger(BuilderGoalCandidateRerunService.class);

  private final BuilderGoalCandidateRerunRepository rerunRepository;
  private final Micro6CandidateArtifactRepository candidateRepository;
  private final PatchLoopIterationRepository iterationRepository;
  private final com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService
      runnerService;
  private final BuilderGoalRecoveryProperties properties;
  private final ObjectMapper objectMapper;

  public BuilderGoalCandidateRerunService(
      BuilderGoalCandidateRerunRepository rerunRepository,
      Micro6CandidateArtifactRepository candidateRepository,
      PatchLoopIterationRepository iterationRepository,
      com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService runnerService,
      BuilderGoalRecoveryProperties properties,
      ObjectMapper objectMapper) {
    this.rerunRepository = rerunRepository;
    this.candidateRepository = candidateRepository;
    this.iterationRepository = iterationRepository;
    this.runnerService = runnerService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public void requireEnabled() {
    if (!properties.isCandidateRerunEnabled() || !properties.isExactDigestActivationEnabled()) {
      throw RecoveryException.forbidden(
          RecoveryFailureCodes.CANDIDATE_RERUN_DISABLED,
          "Candidate rerun requires the candidate-rerun and exact-digest-activation capabilities");
    }
  }

  @Transactional
  public Map<String, Object> requestRerun(
      String incidentId,
      String candidateId,
      String claimedDigest,
      String parentIterationId,
      String idempotencyKey,
      String authorityId) {
    requireEnabled();
    Micro6CandidateArtifact candidate =
        candidateRepository
            .findByCandidateIdAndIncidentId(candidateId, incidentId)
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "CANDIDATE_NOT_FOUND", "No such candidate under the requested run"));
    if (!"BUILD_VALIDATED".equals(candidate.getEligibilityState())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.CANDIDATE_NOT_BUILD_VALIDATED,
          "Only a BUILD_VALIDATED candidate can be rerun");
    }
    if (claimedDigest == null || claimedDigest.isBlank()) {
      throw RecoveryException.badRequest(
          "CANDIDATE_DIGEST_REQUIRED",
          "A candidate digest is required for a candidate-bound rerun");
    }
    if (!claimedDigest.equals(candidate.getCandidateDigest())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.CANDIDATE_DIGEST_MISMATCH,
          "The requested candidate digest does not match the validated candidate");
    }
    PatchLoopIteration parent =
        iterationRepository
            .findByIterationId(parentIterationId)
            .orElseThrow(
                () -> RecoveryException.notFound("RUN_NOT_FOUND", "Parent run is unavailable"));
    requireNonBlankIdempotencyKey(idempotencyKey);
    String accountHash = readAccountHash(parent);
    String machineUuid = readMachineUuid(parent);
    if (accountHash == null || machineUuid == null) {
      throw RecoveryException.conflict(
          "PARENT_RUN_METADATA_INCOMPLETE",
          "The parent run lacks required account or machine metadata");
    }

    BuilderGoalCandidateRerun existing =
        rerunRepository
            .findByParentIterationIdAndCandidateDigestAndIdempotencyKey(
                parentIterationId, candidate.getCandidateDigest(), idempotencyKey)
            .orElse(null);
    if (existing != null) {
      return toResponse(existing);
    }

    BuilderGoalCandidateRerun rerun = new BuilderGoalCandidateRerun();
    rerun.setRerunRequestId(RecoveryHashes.newId("rerun"));
    rerun.setIncidentId(incidentId);
    rerun.setCandidateId(candidateId);
    rerun.setParentIterationId(parentIterationId);
    rerun.setCandidateDigest(candidate.getCandidateDigest());
    rerun.setScenarioHash(
        RecoveryHashes.sha256(
            String.valueOf(parent.getScriptName()) + ":" + parent.getIterationId()));
    rerun.setAccountHash(accountHash);
    rerun.setMachineUuid(machineUuid);
    rerun.setAuthorityId(authorityId);
    rerun.setIdempotencyKey(idempotencyKey);
    rerun.setState(RecoveryStates.RERUN_REQUESTED);
    rerun.setExecutorRequestId(RecoveryHashes.newId("execreq"));
    rerun.setCreatedAt(Instant.now());
    rerun.setUpdatedAt(Instant.now());
    BuilderGoalCandidateRerun saved = rerunRepository.save(rerun);

    return toResponse(bindChild(saved, parent));
  }

  /** Drives a requested rerun to a bound child run; idempotent per request identity. */
  @Transactional
  public BuilderGoalCandidateRerun bindChild(
      BuilderGoalCandidateRerun rerun, PatchLoopIteration parent) {
    if (RecoveryStates.BOUND.equals(rerun.getState()) && rerun.getChildIterationId() != null) {
      return rerun;
    }
    try {
      JsonNode taskMetadata = parent.getTaskMetadataJson();
      Map<String, Object> metadata =
          taskMetadata == null
              ? Map.of()
              : objectMapper.convertValue(
                  taskMetadata, new TypeReference<Map<String, Object>>() {});
      String taskKey = firstNonBlank(asText(metadata.get("task_key")), parent.getScriptName());
      Map<String, Object> settings = asMap(metadata.get("settings"));
      Integer world = asInteger(metadata.get("world"));
      String displayName = asText(metadata.get("title"));
      Map<String, Object> result =
          runnerService.startCatalogTaskRun(
              rerun.getAccountHash(),
              rerun.getMachineUuid(),
              taskKey,
              settings,
              displayName,
              world,
              Map.of(
                  "initiated_by",
                  "candidate_rerun",
                  "parent_iteration_id",
                  rerun.getParentIterationId(),
                  "candidate_id",
                  rerun.getCandidateId()));
      Object run = result.get("run");
      String childId = null;
      if (run instanceof Map<?, ?> runMap) {
        Object iterationId = runMap.get("iterationId");
        childId = iterationId == null ? null : String.valueOf(iterationId);
      }
      rerun.setChildIterationId(childId);
      rerun.setState(childId == null ? RecoveryStates.NEEDS_RECONCILIATION : RecoveryStates.BOUND);
      rerun.setReconciliationJson(
          childId == null ? "{\"code\":\"" + RecoveryFailureCodes.UNCERTAIN_OUTCOME + "\"}" : null);
      rerun.setCompletedAt(childId == null ? null : Instant.now());
      rerun.setUpdatedAt(Instant.now());
      return rerunRepository.save(rerun);
    } catch (Exception e) {
      logger.warn(
          "Candidate rerun dispatch deferred: rerunRequestId={} (details omitted)",
          rerun.getRerunRequestId());
      rerun.setState(RecoveryStates.NEEDS_RECONCILIATION);
      rerun.setReconciliationJson(
          "{\"code\":\"" + RecoveryFailureCodes.NEEDS_RECONCILIATION + "\"}");
      rerun.setUpdatedAt(Instant.now());
      return rerunRepository.save(rerun);
    }
  }

  @Scheduled(fixedDelay = 120_000L)
  @Transactional
  public void reconcile() {
    if (!properties.isCandidateRerunEnabled()) {
      return;
    }
    for (BuilderGoalCandidateRerun rerun :
        rerunRepository.findByStateIn(
            List.of(
                RecoveryStates.RERUN_REQUESTED,
                RecoveryStates.ACTIVATING_CANDIDATE,
                RecoveryStates.STARTING_CHILD,
                RecoveryStates.NEEDS_RECONCILIATION))) {
      iterationRepository
          .findByIterationId(rerun.getParentIterationId())
          .ifPresent(parent -> bindChild(rerun, parent));
    }
  }

  private Map<String, Object> toResponse(BuilderGoalCandidateRerun rerun) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("rerunRequestId", rerun.getRerunRequestId());
    response.put("state", rerun.getState());
    response.put("candidateId", rerun.getCandidateId());
    response.put("candidateDigest", rerun.getCandidateDigest());
    response.put("parentIterationId", rerun.getParentIterationId());
    response.put("childIterationId", rerun.getChildIterationId());
    response.put(
        "statusUrl",
        "/api/debug/builder-goals/runs/"
            + rerun.getParentIterationId()
            + "/compile-recovery/reruns/"
            + rerun.getRerunRequestId());
    return response;
  }

  private String readAccountHash(PatchLoopIteration parent) {
    JsonNode metadata = parent.getTaskMetadataJson();
    if (metadata == null) {
      return null;
    }
    JsonNode account = metadata.path("mule_account");
    String hash =
        firstNonBlank(asText(account.path("account_hash")), asText(account.path("account_id")));
    if (hash == null) {
      hash =
          firstNonBlank(asText(metadata.path("account_hash")), asText(metadata.path("account_id")));
    }
    return hash;
  }

  private String readMachineUuid(PatchLoopIteration parent) {
    JsonNode metadata = parent.getTaskMetadataJson();
    if (metadata == null) {
      return null;
    }
    JsonNode account = metadata.path("mule_account");
    String uuid = asText(account.path("launcher_machine_uuid"));
    if (uuid == null) {
      uuid = asText(metadata.path("launcher_machine_uuid"));
    }
    return uuid;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static void requireNonBlankIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw RecoveryException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "A nonblank idempotency key is required");
    }
  }

  private static String asText(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText(null);
  }

  private static String asText(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Integer asInteger(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && text.matches("\\d+")) {
      return Integer.valueOf(text);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return new LinkedHashMap<>((Map<String, Object>) map);
    }
    return Map.of();
  }
}
