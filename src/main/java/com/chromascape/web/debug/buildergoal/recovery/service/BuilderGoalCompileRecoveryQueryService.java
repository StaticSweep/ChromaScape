package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryStates;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryArtifact;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryIncident;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6CandidateArtifact;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryArtifactRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryIncidentRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6CandidateArtifactRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchProposalRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchValidationJobRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the compile-recovery API: eligibility, durable snapshot, artifact reads, and allowed
 * actions. Reads never mutate state and never expose host paths, tokens, or credentials.
 */
@Service
public class BuilderGoalCompileRecoveryQueryService {

  private final PatchLoopIterationRepository iterationRepository;
  private final BuilderGoalRecoveryIncidentRepository incidentRepository;
  private final BuilderGoalRecoveryArtifactRepository artifactRepository;
  private final Micro6PatchProposalRepository proposalRepository;
  private final Micro6PatchValidationJobRepository jobRepository;
  private final Micro6CandidateArtifactRepository candidateRepository;
  private final BuilderGoalCompileArtifactService artifactService;
  private final BuilderGoalRecoveryProperties properties;
  private final ObjectMapper objectMapper;

  public BuilderGoalCompileRecoveryQueryService(
      PatchLoopIterationRepository iterationRepository,
      BuilderGoalRecoveryIncidentRepository incidentRepository,
      BuilderGoalRecoveryArtifactRepository artifactRepository,
      Micro6PatchProposalRepository proposalRepository,
      Micro6PatchValidationJobRepository jobRepository,
      Micro6CandidateArtifactRepository candidateRepository,
      BuilderGoalCompileArtifactService artifactService,
      BuilderGoalRecoveryProperties properties,
      ObjectMapper objectMapper) {
    this.iterationRepository = iterationRepository;
    this.incidentRepository = incidentRepository;
    this.artifactRepository = artifactRepository;
    this.proposalRepository = proposalRepository;
    this.jobRepository = jobRepository;
    this.candidateRepository = candidateRepository;
    this.artifactService = artifactService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public PatchLoopIteration loadEligibleRun(String iterationId) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationId(iterationId)
            .orElseThrow(
                () -> RecoveryException.notFound("RUN_NOT_FOUND", "Builder Goal run not found"));
    if (!"builder_goal".equals(iteration.getSourceType())) {
      throw RecoveryException.notFound("RUN_NOT_FOUND", "Builder Goal run not found");
    }
    return iteration;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> snapshot(String iterationId) {
    PatchLoopIteration run = loadEligibleRun(iterationId);
    boolean eligible = isEligible(run);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("schemaVersion", "builder-goal-compile-recovery/v1");
    response.put("iterationId", iterationId);
    response.put("eligible", eligible);
    response.put("failureCode", run.getFailureCode());
    BuilderGoalRecoveryIncident incident = latestIncident(iterationId);
    List<BuilderGoalRecoveryArtifact> artifacts =
        incident == null ? List.of() : artifactsFor(incident.getIncidentId());
    response.put("incident", incident == null ? null : incidentView(incident));
    response.put("diagnosticSummary", diagnosticSummary(artifacts));
    response.put(
        "artifacts",
        artifacts.stream().map(artifact -> artifactSummary(iterationId, artifact, true)).toList());
    Micro6PatchProposal proposal =
        incident == null ? null : proposalService_latest(incident.getIncidentId());
    response.put("proposal", proposal == null ? null : proposalSummary(proposal));
    Micro6PatchValidationJob job = incident == null ? null : latestJob(incident.getIncidentId());
    response.put("validationJob", job == null ? null : validationJobView(job));
    Micro6CandidateArtifact candidate =
        incident == null ? null : latestCandidate(incident.getIncidentId());
    response.put("candidate", candidate == null ? null : candidateResponse(candidate));
    response.put("allowedActions", allowedActions(eligible, incident, proposal, job, candidate));
    return response;
  }

  boolean isEligible(PatchLoopIteration run) {
    return "PRODUCTION_COMPILE_FAILED".equals(run.getFailureCode())
        && run.getStatus() != null
        && run.getStatus().isTerminal();
  }

  private List<String> allowedActions(
      boolean eligible,
      BuilderGoalRecoveryIncident incident,
      Micro6PatchProposal proposal,
      Micro6PatchValidationJob job,
      Micro6CandidateArtifact candidate) {
    List<String> actions = new ArrayList<>();
    if (!eligible) {
      return actions;
    }
    actions.add("COPY");
    actions.add("DOWNLOAD");
    if (!properties.isMutationsEnabled()) {
      return actions;
    }
    if (incident == null) {
      actions.add("START_ANALYSIS");
    } else if (RecoveryStates.isTerminal(incident.getState())) {
      actions.add("START_ANALYSIS");
    } else if (RecoveryStates.EVIDENCE_READY.equals(incident.getState())
        || RecoveryStates.INCIDENT_CAPTURED.equals(incident.getState())
        || RecoveryStates.REJECTED.equals(incident.getState())
        || RecoveryStates.CHANGES_REQUESTED.equals(incident.getState())
        || RecoveryStates.VALIDATION_FAILED.equals(incident.getState())) {
      actions.add("START_ANALYSIS");
      actions.add("VIEW_PROPOSAL");
    } else if (RecoveryStates.AWAITING_REVIEW.equals(incident.getState()) && proposal != null) {
      actions.add("VIEW_PROPOSAL");
      actions.add("APPROVE");
      actions.add("REJECT");
      actions.add("REQUEST_CHANGES");
    } else if (RecoveryStates.BUILD_VALIDATED.equals(incident.getState()) && candidate != null) {
      actions.add("VIEW_PROPOSAL");
      actions.add("RERUN_CANDIDATE");
    } else if (job != null
        && (RecoveryStates.VALIDATION_QUEUED.equals(incident.getState())
            || RecoveryStates.VALIDATING.equals(incident.getState()))) {
      actions.add("VIEW_PROPOSAL");
      actions.add("CANCEL_VALIDATION");
    }
    return actions;
  }

  private BuilderGoalRecoveryIncident latestIncident(String iterationId) {
    return incidentRepository.findFirstByIterationIdOrderByCreatedAtDesc(iterationId).orElse(null);
  }

  private List<BuilderGoalRecoveryArtifact> artifactsFor(String incidentId) {
    return artifactRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId);
  }

  private Micro6PatchProposal proposalService_latest(String incidentId) {
    return proposalRepository.findByIncidentIdOrderByVersionDesc(incidentId).stream()
        .findFirst()
        .orElse(null);
  }

  private Micro6PatchValidationJob latestJob(String incidentId) {
    return jobRepository.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId).orElse(null);
  }

  private Micro6CandidateArtifact latestCandidate(String incidentId) {
    return candidateRepository.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId).orElse(null);
  }

  private Map<String, Object> incidentView(BuilderGoalRecoveryIncident incident) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("incidentId", incident.getIncidentId());
    view.put("type", incident.getIncidentType());
    view.put("state", incident.getState());
    view.put("evidenceManifestHash", incident.getEvidenceManifestHash());
    view.put("correlationId", incident.getCorrelationId());
    view.put("createdAt", String.valueOf(incident.getCreatedAt()));
    return view;
  }

  private Map<String, Object> diagnosticSummary(List<BuilderGoalRecoveryArtifact> artifacts) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("errorCount", 0);
    summary.put("warningCount", 0);
    summary.put("gradleCommandKey", properties.getGradleCommandKey());
    summary.put("truncated", false);
    for (BuilderGoalRecoveryArtifact artifact : artifacts) {
      if ("COMPILER_DIAGNOSTICS".equals(artifact.getKind())) {
        try {
          JsonNode node = objectMapper.readTree(artifactService.readContent(artifact));
          summary.put("errorCount", node.path("error_count").asInt(0));
          summary.put("warningCount", node.path("warning_count").asInt(0));
        } catch (Exception ignored) {
          // A damaged diagnostics artifact degrades the summary without blocking the snapshot.
        }
      }
      if ("ORIGINAL_COMPILE_LOG".equals(artifact.getKind()) && artifact.isTruncated()) {
        summary.put("truncated", true);
      }
    }
    return summary;
  }

  public Map<String, Object> artifactSummary(
      String iterationId, BuilderGoalRecoveryArtifact artifact, boolean includeMetadata) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("artifactId", artifact.getArtifactId());
    view.put("kind", artifact.getKind());
    view.put("sha256", artifact.getSha256());
    view.put("byteCount", artifact.getByteCount());
    view.put("lineCount", artifact.getLineCount());
    view.put("truncated", artifact.isTruncated());
    view.put("sanitizationVersion", artifact.getSanitizationVersion());
    view.put("createdAt", String.valueOf(artifact.getCreatedAt()));
    return view;
  }

  private Map<String, Object> proposalSummary(Micro6PatchProposal proposal) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("proposalId", proposal.getProposalId());
    view.put("version", proposal.getVersion());
    view.put("status", proposal.getStatus());
    view.put("proposalHash", proposal.getProposalHash());
    view.put("patchHash", proposal.getPatchHash());
    view.put("baseCommit", proposal.getBaseCommit());
    view.put("expiresAt", String.valueOf(proposal.getExpiresAt()));
    return view;
  }

  private Map<String, Object> validationJobView(Micro6PatchValidationJob job) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("jobId", job.getJobId());
    view.put("state", job.getState());
    view.put("currentStage", job.getCurrentStage());
    view.put("failureCode", job.getFailureCode());
    view.put("candidateId", job.getCandidateId());
    view.put("cancelRequested", job.getCancelRequestedAt() != null);
    return view;
  }

  private Map<String, Object> candidateResponse(Micro6CandidateArtifact candidate) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("candidateId", candidate.getCandidateId());
    view.put("candidateDigest", candidate.getCandidateDigest());
    view.put("resultingTreeHash", candidate.getResultingTreeHash());
    view.put("eligibilityState", candidate.getEligibilityState());
    return view;
  }

  public Map<String, Object> proposalDetail(String iterationId, String proposalId) {
    PatchLoopIteration run = loadEligibleRun(iterationId);
    BuilderGoalRecoveryIncident incident = requireIncident(run.getIterationId());
    Micro6PatchProposal proposal =
        proposalRepository
            .findByProposalIdAndIncidentId(proposalId, incident.getIncidentId())
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "PROPOSAL_NOT_FOUND", "No such proposal under the requested run"));
    Map<String, Object> view = proposalSummary(proposal);
    view.put("canonicalDiff", proposal.getCanonicalDiff());
    view.put("affectedFiles", readJson(proposal.getAffectedFilesJson()));
    view.put("citations", readJson(proposal.getCitationsJson()));
    view.put("policyReport", readJson(proposal.getPolicyReportJson()));
    view.put("validationPlan", readJson(proposal.getValidationPlanJson()));
    view.put("rationale", proposal.getRationale());
    view.put("expectedChange", proposal.getExpectedChange());
    view.put("source", sourceView(proposal));
    boolean approvable =
        RecoveryStates.AWAITING_REVIEW.equals(proposal.getStatus())
            && proposal.getExpiresAt() != null
            && proposal.getExpiresAt().isAfter(Instant.now())
            && properties.isMutationsEnabled();
    view.put("approvable", approvable);
    view.put(
        "allowedActions",
        approvable ? List.of("APPROVE", "REJECT", "REQUEST_CHANGES") : List.of("VIEW"));
    return view;
  }

  private Map<String, Object> sourceView(Micro6PatchProposal proposal) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sourceSnapshotId", proposal.getSourceSnapshotId());
    view.put("sourceManifestHash", proposal.getSourceManifestHash());
    view.put("repositoryId", proposal.getRepositoryId());
    view.put("baseCommit", proposal.getBaseCommit());
    view.put("baseTree", proposal.getBaseTree());
    view.put("baseBuildId", proposal.getBaseBuildId());
    view.put("baseArtifactDigest", proposal.getBaseArtifactDigest());
    return view;
  }

  public BuilderGoalRecoveryIncident requireIncident(String iterationId) {
    return incidentRepository
        .findFirstByIterationIdOrderByCreatedAtDesc(iterationId)
        .orElseThrow(
            () ->
                RecoveryException.conflict(
                    RecoveryFailureCodes.COMPILE_EVIDENCE_MISSING,
                    "No recovery incident exists for this run yet"));
  }

  public BuilderGoalRecoveryArtifact loadArtifact(String iterationId, String artifactId) {
    BuilderGoalRecoveryIncident incident = requireIncident(iterationId);
    return artifactRepository
        .findByArtifactIdAndIncidentId(artifactId, incident.getIncidentId())
        .orElseThrow(
            () ->
                RecoveryException.notFound(
                    "ARTIFACT_NOT_FOUND", "No such artifact under the requested run"));
  }

  public String artifactText(BuilderGoalRecoveryArtifact artifact, String view) {
    return artifactService.readView(artifact, view);
  }

  private Object readJson(String json) {
    try {
      return objectMapper.readTree(json == null ? "{}" : json);
    } catch (Exception e) {
      return Map.of();
    }
  }
}
