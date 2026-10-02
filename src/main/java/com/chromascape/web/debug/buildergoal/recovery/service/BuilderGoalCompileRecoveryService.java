package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryStates;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryArtifact;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryIncident;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchApproval;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryIncidentRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coordinates the compile recovery workflow: incident capture, prompt construction, run-bound
 * proposal generation, exact approval, validation enqueue, review actions, and candidate rerun
 * dispatch. It never edits source, starts Gradle, restarts Micro6, or publishes a task itself.
 */
@Service
public class BuilderGoalCompileRecoveryService {

  private final BuilderGoalCompileRecoveryQueryService query;
  private final BuilderGoalRecoveryIncidentRepository incidentRepository;
  private final com.chromascape.web.debug.buildergoal.recovery.repository
          .BuilderGoalRecoveryArtifactRepository
      artifactRepository;
  private final com.chromascape.web.debug.buildergoal.recovery.repository
          .Micro6PatchValidationJobRepository
      validationJobRepository;
  private final BuilderGoalCompileArtifactService artifactService;
  private final BuilderGoalCompilePromptFactory promptFactory;
  private final PatchProposalProvider proposalProvider;
  private final PatchProposalService proposalService;
  private final PatchApprovalService approvalService;
  private final PatchValidationJobService validationJobService;
  private final BuilderGoalCandidateRerunService rerunService;
  private final RecoveryAuditService auditService;
  private final BuilderGoalRecoveryProperties properties;
  private final com.chromascape.web.debug.buildergoal.service.BuilderGoalCodexOrchestrationService
      orchestrationService;
  private final ObjectMapper objectMapper;

  public BuilderGoalCompileRecoveryService(
      BuilderGoalCompileRecoveryQueryService query,
      BuilderGoalRecoveryIncidentRepository incidentRepository,
      com.chromascape.web.debug.buildergoal.recovery.repository
              .BuilderGoalRecoveryArtifactRepository
          artifactRepository,
      com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchValidationJobRepository
          validationJobRepository,
      BuilderGoalCompileArtifactService artifactService,
      BuilderGoalCompilePromptFactory promptFactory,
      PatchProposalProvider proposalProvider,
      PatchProposalService proposalService,
      PatchApprovalService approvalService,
      PatchValidationJobService validationJobService,
      BuilderGoalCandidateRerunService rerunService,
      RecoveryAuditService auditService,
      BuilderGoalRecoveryProperties properties,
      com.chromascape.web.debug.buildergoal.service.BuilderGoalCodexOrchestrationService
          orchestrationService,
      ObjectMapper objectMapper) {
    this.query = query;
    this.incidentRepository = incidentRepository;
    this.artifactRepository = artifactRepository;
    this.validationJobRepository = validationJobRepository;
    this.artifactService = artifactService;
    this.promptFactory = promptFactory;
    this.proposalProvider = proposalProvider;
    this.proposalService = proposalService;
    this.approvalService = approvalService;
    this.validationJobService = validationJobService;
    this.rerunService = rerunService;
    this.auditService = auditService;
    this.properties = properties;
    this.orchestrationService = orchestrationService;
    this.objectMapper = objectMapper;
  }

  public void requireMutationsEnabled() {
    if (!properties.isMutationsEnabled()) {
      throw RecoveryException.forbidden(
          RecoveryFailureCodes.RECOVERY_MUTATIONS_DISABLED,
          "Compile recovery mutations are disabled"
              + " (builder-goal.recovery.mutations-enabled=false)");
    }
  }

  @Transactional
  public Map<String, Object> startAnalysis(
      String iterationId,
      String codexSessionId,
      String idempotencyKey,
      String operatorContext,
      String actorId) {
    requireMutationsEnabled();
    if (codexSessionId == null || codexSessionId.isBlank()) {
      throw RecoveryException.badRequest(
          RecoveryFailureCodes.CODEX_SESSION_NOT_BOUND, "A run-bound Codex session id is required");
    }
    PatchLoopIteration run = query.loadEligibleRun(iterationId);
    if (!query.isEligible(run)) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.NOT_ELIGIBLE,
          "This run is not an eligible production compile failure");
    }
    requireRunBoundSession(codexSessionId, iterationId);

    BuilderGoalRecoveryIncident incident =
        incidentRepository.findFirstByIterationIdOrderByCreatedAtDesc(iterationId).orElse(null);
    if (incident != null && !RecoveryStates.isTerminal(incident.getState())) {
      if (RecoveryStates.ANALYZING.equals(incident.getState())
          || RecoveryStates.EVIDENCE_READY.equals(incident.getState())
          || RecoveryStates.INCIDENT_CAPTURED.equals(incident.getState())) {
        // Idempotent re-entry for the same analysis cycle; the UI resumes polling.
        return analysisAccepted(incident);
      }
      throw RecoveryException.conflict(
          "ACTIVE_ATTEMPT_EXISTS", "A nonterminal recovery cycle already owns this run");
    }

    incident = createIncident(run, incident);
    incident.setState(RecoveryStates.ANALYZING);
    incidentRepository.save(incident);
    auditService.record(
        "ANALYSIS_REQUESTED",
        actorId,
        "OPERATOR",
        iterationId,
        incident.getIncidentId(),
        "incident",
        incident.getIncidentId(),
        "OK",
        null,
        Map.of("codexSessionBound", true));

    // Provider work stays outside the incident transaction; a failure leaves the incident in
    // ANALYZING and the operator can retry the analysis.
    final BuilderGoalRecoveryIncident current = incident;
    Runnable work = () -> generateProposal(current, codexSessionId, actorId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.run();
            }
          });
    } else {
      work.run();
    }
    return analysisAccepted(incident);
  }

  private void generateProposal(
      BuilderGoalRecoveryIncident incident, String codexSessionId, String actorId) {
    try {
      List<BuilderGoalRecoveryArtifact> artifacts = artifactServiceList(incident.getIncidentId());
      List<BuilderGoalCompileDiagnosticParser.Diagnostic> diagnostics = readDiagnostics(artifacts);
      String prompt =
          promptFactory.build(
              incident,
              readMap(incident.getRunIdentityJson()),
              diagnostics,
              "Resolved at proposal time from the pinned Micro6 source boundary");
      BuilderGoalRecoveryArtifact promptArtifact =
          artifactService.store("AI_PROMPT", incident.getIncidentId(), null, prompt, false);
      String promptHash = promptArtifact.getSha256();
      PatchProposalProvider.ProviderOutput output =
          proposalProvider.requestProposal(prompt, Map.of("codexSessionId", codexSessionId));
      proposalService.createProposal(
          new PatchProposalService.ProposalInput(
              incident,
              prompt,
              promptHash,
              output.canonicalDiff(),
              diagnostics,
              output.generatorMetadata(),
              codexSessionId));
      auditService.record(
          "PROPOSAL_CREATED",
          actorId,
          "OPERATOR",
          incident.getIterationId(),
          incident.getIncidentId(),
          "incident",
          incident.getIncidentId(),
          "OK",
          null,
          null);
    } catch (RecoveryException e) {
      auditService.record(
          "ANALYSIS_FAILED",
          actorId,
          "OPERATOR",
          incident.getIterationId(),
          incident.getIncidentId(),
          "incident",
          incident.getIncidentId(),
          "FAILED",
          e.code(),
          null);
    } catch (Exception e) {
      auditService.record(
          "ANALYSIS_FAILED",
          actorId,
          "OPERATOR",
          incident.getIterationId(),
          incident.getIncidentId(),
          "incident",
          incident.getIncidentId(),
          "FAILED",
          RecoveryFailureCodes.AI_PROVIDER_OUTPUT_INVALID,
          null);
    }
  }

  private Map<String, Object> analysisAccepted(BuilderGoalRecoveryIncident incident) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", true);
    response.put("incidentId", incident.getIncidentId());
    response.put("state", incident.getState());
    response.put(
        "statusUrl",
        "/api/debug/builder-goals/runs/" + incident.getIterationId() + "/compile-recovery");
    return response;
  }

  private BuilderGoalRecoveryIncident createIncident(
      PatchLoopIteration run, BuilderGoalRecoveryIncident existing) {
    // The FK parent must exist before evidence persistence. In particular, never use a
    // placeholder incident ID: PostgreSQL is the authority for this relationship.
    if (existing == null) {
      existing = new BuilderGoalRecoveryIncident();
      existing.setIncidentId(RecoveryHashes.newId("incident"));
      existing.setIterationId(run.getIterationId());
      existing.setIncidentType("COMPILE_PREFLIGHT");
      existing.setFailureCode(run.getFailureCode());
      existing.setCorrelationId(RecoveryHashes.newId("corr"));
      existing.setRunIdentityJson(writeJson(runIdentity(run)));
      existing.setState(RecoveryStates.INCIDENT_CAPTURED);
      existing.setCreatedAt(Instant.now());
      existing = incidentRepository.saveAndFlush(existing);
    }
    BuilderGoalCompileArtifactService.CapturedEvidence evidence =
        artifactService.captureCompileEvidence(
            existing.getIncidentId(), run.getTaskMetadataJson(), null);
    if (evidence.rawLog().getSha256().equals(firstLogHash(existing))) {
      return existing;
    }
    existing.setEvidenceManifestJson(
        writeJson(List.of(evidence.rawLog().getSha256(), evidence.diagnostics().getSha256())));
    existing.setEvidenceManifestHash(
        RecoveryHashes.sha256(
            evidence.rawLog().getSha256() + "|" + evidence.diagnostics().getSha256()));
    existing.setState(RecoveryStates.EVIDENCE_READY);
    return incidentRepository.save(existing);
  }

  private String firstLogHash(BuilderGoalRecoveryIncident incident) {
    JsonNode manifest = readTree(incident.getEvidenceManifestJson());
    return manifest.isArray() && !manifest.isEmpty() ? manifest.get(0).asText(null) : null;
  }

  @Transactional
  public Micro6PatchApproval approve(
      String iterationId,
      String proposalId,
      String claimedProposalHash,
      String reason,
      String actorId,
      String actorRole,
      String environment) {
    requireMutationsEnabled();
    query.loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = query.requireIncident(iterationId);
    Micro6PatchApproval approval =
        approvalService.approve(
            proposalId,
            incident.getIncidentId(),
            claimedProposalHash,
            actorId,
            actorRole,
            reason,
            environment);
    auditService.record(
        "APPROVAL_GRANTED",
        actorId,
        actorRole,
        iterationId,
        incident.getIncidentId(),
        "proposal",
        proposalId,
        "OK",
        null,
        null);
    return approval;
  }

  @Transactional
  public Map<String, Object> review(
      String iterationId, String proposalId, String action, String actorId) {
    requireMutationsEnabled();
    query.loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = query.requireIncident(iterationId);
    Micro6PatchProposal proposal;
    if ("reject".equals(action)) {
      proposal = proposalService.reject(proposalId, incident.getIncidentId(), actorId);
    } else if ("request-changes".equals(action)) {
      proposal = proposalService.requestChanges(proposalId, incident.getIncidentId(), actorId);
    } else {
      throw RecoveryException.badRequest("INVALID_REVIEW_ACTION", "Unknown review action");
    }
    auditService.record(
        "PROPOSAL_REVIEWED",
        actorId,
        "OPERATOR",
        iterationId,
        incident.getIncidentId(),
        "proposal",
        proposalId,
        "OK",
        null,
        Map.of("action", action));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("proposalId", proposal.getProposalId());
    response.put("status", proposal.getStatus());
    return response;
  }

  @Transactional
  public Map<String, Object> queueValidation(
      String iterationId,
      String proposalId,
      String approvalId,
      String idempotencyKey,
      String actorId) {
    requireMutationsEnabled();
    query.loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = query.requireIncident(iterationId);
    Micro6PatchValidationJob job =
        validationJobService.queue(
            incident.getIncidentId(), proposalId, approvalId, idempotencyKey);
    auditService.record(
        "VALIDATION_QUEUED",
        actorId,
        "OPERATOR",
        iterationId,
        incident.getIncidentId(),
        "job",
        job.getJobId(),
        "OK",
        null,
        null);
    Map<String, Object> response = validationJobService.toStatus(job);
    response.put(
        "statusUrl",
        "/api/debug/builder-goals/runs/"
            + iterationId
            + "/compile-recovery/validation-jobs/"
            + job.getJobId());
    response.put("approvalId", approvalId);
    response.put("proposalId", proposalId);
    return response;
  }

  public Map<String, Object> validationStatus(String iterationId, String jobId) {
    query.loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = query.requireIncident(iterationId);
    Micro6PatchValidationJob job =
        validationJobRepository
            .findByJobId(jobId)
            .orElseThrow(
                () -> RecoveryException.notFound("JOB_NOT_FOUND", "Validation job is unavailable"));
    if (!incident.getIncidentId().equals(job.getIncidentId())) {
      throw RecoveryException.notFound(
          "JOB_NOT_FOUND", "No such validation job under the requested run");
    }
    Map<String, Object> response = validationJobService.toStatus(job);
    response.put(
        "statusUrl",
        "/api/debug/builder-goals/runs/"
            + iterationId
            + "/compile-recovery/validation-jobs/"
            + job.getJobId());
    return response;
  }

  @Transactional
  public Map<String, Object> cancelValidation(String iterationId, String jobId, String actorId) {
    requireMutationsEnabled();
    query.loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = query.requireIncident(iterationId);
    Micro6PatchValidationJob job = validationJobService.cancel(jobId, incident.getIncidentId());
    auditService.record(
        "VALIDATION_CANCELLED",
        actorId,
        "OPERATOR",
        iterationId,
        incident.getIncidentId(),
        "job",
        jobId,
        "OK",
        null,
        null);
    Map<String, Object> response = validationJobService.toStatus(job);
    response.put(
        "statusUrl",
        "/api/debug/builder-goals/runs/"
            + iterationId
            + "/compile-recovery/validation-jobs/"
            + job.getJobId());
    return response;
  }

  @Transactional
  public Map<String, Object> requestRerun(
      String iterationId,
      String candidateId,
      String claimedDigest,
      String idempotencyKey,
      String actorId) {
    requireMutationsEnabled();
    query.loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = query.requireIncident(iterationId);
    Map<String, Object> response =
        rerunService.requestRerun(
            incident.getIncidentId(),
            candidateId,
            claimedDigest,
            iterationId,
            idempotencyKey,
            actorId);
    auditService.record(
        "RERUN_AUTHORIZED",
        actorId,
        "OPERATOR",
        iterationId,
        incident.getIncidentId(),
        "candidate",
        candidateId,
        "OK",
        null,
        null);
    return response;
  }

  private void requireRunBoundSession(String codexSessionId, String iterationId) {
    try {
      Map<String, Object> active = orchestrationService.getActiveRun(codexSessionId);
      Object bound = active.get("activeIterationId");
      if (!iterationId.equals(bound == null ? null : String.valueOf(bound))) {
        throw RecoveryException.conflict(
            RecoveryFailureCodes.CODEX_SESSION_NOT_BOUND,
            "The Codex session is not bound to this Builder Goal run");
      }
    } catch (RecoveryException e) {
      throw e;
    } catch (Exception e) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.CODEX_SESSION_NOT_BOUND,
          "The Codex session is unavailable or not bound to this run");
    }
  }

  private List<BuilderGoalRecoveryArtifact> artifactServiceList(String incidentId) {
    return artifactRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId);
  }

  private List<BuilderGoalCompileDiagnosticParser.Diagnostic> readDiagnostics(
      List<BuilderGoalRecoveryArtifact> artifacts) {
    for (BuilderGoalRecoveryArtifact artifact : artifacts) {
      if ("COMPILER_DIAGNOSTICS".equals(artifact.getKind())) {
        try {
          JsonNode node = objectMapper.readTree(artifactService.readContent(artifact));
          List<BuilderGoalCompileDiagnosticParser.Diagnostic> diagnostics =
              new java.util.ArrayList<>();
          for (JsonNode entry : node.path("diagnostics")) {
            diagnostics.add(
                new BuilderGoalCompileDiagnosticParser.Diagnostic(
                    entry.path("tool").asText("javac"),
                    entry.path("severity").asText("error"),
                    entry.path("file").asText(null),
                    entry.path("line").asInt(0),
                    entry.path("column").isInt() ? entry.path("column").asInt() : null,
                    null,
                    entry.path("message").asText("")));
          }
          return diagnostics;
        } catch (Exception ignored) {
          // An unreadable diagnostics artifact degrades citations without blocking capture.
        }
      }
    }
    return List.of();
  }

  private Map<String, Object> runIdentity(PatchLoopIteration run) {
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("iterationId", run.getIterationId());
    identity.put("scriptName", run.getScriptName());
    identity.put("failureCode", run.getFailureCode());
    identity.put("runRecordingId", run.getRunRecordingId());
    identity.put("runSessionId", run.getRunSessionId());
    return identity;
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json == null ? "[]" : json);
    } catch (Exception e) {
      return objectMapper.createArrayNode();
    }
  }

  private Map<String, Object> readMap(String json) {
    try {
      return objectMapper.readValue(json == null ? "{}" : json, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }
}
