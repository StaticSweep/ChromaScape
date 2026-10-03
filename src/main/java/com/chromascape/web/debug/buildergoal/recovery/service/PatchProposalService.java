package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryArtifactKinds;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryStates;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryIncident;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import com.chromascape.web.debug.buildergoal.recovery.repository.BuilderGoalRecoveryIncidentRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchProposalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generalizes evidence-bound proposal generation: canonical diffs only, server-owned policy, exact
 * source binding, per-file citations, and immutable checksummed persistence. Loose oldText/newText
 * patch drafts are rejected on this path.
 */
@Service
public class PatchProposalService {

  static final String PROPOSAL_SCHEMA = "micro6-patch-proposal/v1";
  static final String GENERATOR_VERSION = "sc1173/1";

  private final Micro6PatchProposalRepository proposalRepository;
  private final BuilderGoalRecoveryIncidentRepository incidentRepository;
  private final BuilderGoalCompileArtifactService artifactService;
  private final PatchPolicyService policyService;
  private final Micro6SourceSnapshotService sourceSnapshotService;
  private final BuilderGoalRecoveryProperties properties;
  private final ObjectMapper objectMapper;

  public PatchProposalService(
      Micro6PatchProposalRepository proposalRepository,
      BuilderGoalRecoveryIncidentRepository incidentRepository,
      BuilderGoalCompileArtifactService artifactService,
      PatchPolicyService policyService,
      Micro6SourceSnapshotService sourceSnapshotService,
      BuilderGoalRecoveryProperties properties,
      ObjectMapper objectMapper) {
    this.proposalRepository = proposalRepository;
    this.incidentRepository = incidentRepository;
    this.artifactService = artifactService;
    this.policyService = policyService;
    this.sourceSnapshotService = sourceSnapshotService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public record ProposalInput(
      BuilderGoalRecoveryIncident incident,
      String prompt,
      String promptHash,
      String canonicalDiff,
      List<BuilderGoalCompileDiagnosticParser.Diagnostic> diagnostics,
      Map<String, Object> generatorMetadata,
      String proposerIdentity) {}

  @Transactional
  public Micro6PatchProposal createProposal(ProposalInput input) {
    BuilderGoalRecoveryIncident incident = input.incident();
    if (input.promptHash() != null) {
      Micro6PatchProposal identical =
          proposalRepository.findByIncidentIdOrderByVersionDesc(incident.getIncidentId()).stream()
              .filter(proposal -> input.promptHash().equals(proposal.getPromptHash()))
              .findFirst()
              .orElse(null);
      if (identical != null) {
        return identical;
      }
    }

    PatchParser.ParsedPatch parsed = PatchParser.parse(input.canonicalDiff(), null);
    List<String> affectedPaths = new ArrayList<>(parsed.affectedPaths());
    PatchPolicyService.PolicyReport policyReport = policyService.evaluate(parsed, affectedPaths);
    policyService.requireAccepted(policyReport);

    Micro6SourceSnapshotService.SourceSnapshot snapshot =
        sourceSnapshotService.resolve(affectedPaths);
    sourceSnapshotService.requireVerifiedProvenance(snapshot);
    verifyPreimages(parsed, snapshot, affectedPaths);

    Map<String, Object> citations = buildCitations(parsed, input.diagnostics());
    String validationPlanJson = validationPlanJson();
    String validationPlanHash = RecoveryHashes.sha256(validationPlanJson);

    int nextVersion =
        proposalRepository.findByIncidentIdOrderByVersionDesc(incident.getIncidentId()).stream()
                .mapToInt(Micro6PatchProposal::getVersion)
                .max()
                .orElse(0)
            + 1;
    Instant now = Instant.now();
    Map<String, String> preimageHashes = new java.util.TreeMap<>();
    snapshot.files().forEach((path, file) -> preimageHashes.put(path, file.contentHash()));
    ObjectNode canonical =
        buildCanonicalContent(
            incident, nextVersion, parsed, snapshot, citations, validationPlanHash, input, now);
    String proposalHash =
        com.chromascape.web.aiplayer.contract.CanonicalJsonHasher.hash(canonical, null);

    Micro6PatchProposal proposal = new Micro6PatchProposal();
    proposal.setProposalId(RecoveryHashes.shortId("proposal", proposalHash));
    proposal.setIncidentId(incident.getIncidentId());
    proposal.setVersion(nextVersion);
    proposal.setStatus(RecoveryStates.AWAITING_REVIEW);
    proposal.setSourceSnapshotId(snapshot.sourceSnapshotId());
    proposal.setSourceManifestHash(snapshot.sourceManifestHash());
    proposal.setRepositoryId(snapshot.repositoryId());
    proposal.setBaseCommit(snapshot.commitId());
    proposal.setBaseTree(snapshot.treeId());
    proposal.setBaseBuildId(snapshot.buildId());
    proposal.setBaseArtifactDigest(snapshot.artifactDigest());
    proposal.setCanonicalDiff(input.canonicalDiff());
    proposal.setPatchHash(RecoveryHashes.sha256(input.canonicalDiff()));
    proposal.setProposalHash(proposalHash);
    proposal.setAffectedFilesJson(json(affectedPaths));
    proposal.setPreimagesJson(json(preimageHashes));
    proposal.setCitationsJson(json(citations));
    proposal.setRationale(sanitize(input.generatorMetadata().get("rationale")));
    proposal.setExpectedChange(sanitize(input.generatorMetadata().get("expectedChange")));
    proposal.setInvariantsJson("[]");
    proposal.setNonGoalsJson("[\"certification\",\"promotion\"]");
    proposal.setValidationPlanJson(validationPlanJson);
    proposal.setValidationPlanHash(validationPlanHash);
    proposal.setPolicyReportJson(json(policyService.reportToJson(policyReport)));
    proposal.setGeneratorJson(json(generatorJson(input)));
    proposal.setPromptHash(input.promptHash());
    proposal.setExpiresAt(now.plus(properties.getProposalTtl()));
    proposal.setCreatedAt(now);
    Micro6PatchProposal saved = proposalRepository.save(proposal);

    artifactService.store(
        RecoveryArtifactKinds.PROPOSAL,
        incident.getIncidentId(),
        saved.getProposalId(),
        objectMapper.valueToTree(canonical).toPrettyString(),
        false);
    artifactService.store(
        RecoveryArtifactKinds.POLICY_REPORT,
        incident.getIncidentId(),
        saved.getProposalId(),
        objectMapper.valueToTree(policyService.reportToJson(policyReport)).toPrettyString(),
        false);

    incident.setState(RecoveryStates.AWAITING_REVIEW);
    incidentRepository.save(incident);
    return saved;
  }

  @Transactional
  public Micro6PatchProposal reject(String proposalId, String incidentId, String actorId) {
    Micro6PatchProposal proposal = load(proposalId, incidentId);
    requireReviewable(proposal);
    proposal.setStatus(RecoveryStates.REJECTED);
    Micro6PatchProposal saved = proposalRepository.save(proposal);
    incidentRepository
        .findWithLockByIncidentId(incidentId)
        .ifPresent(
            incident -> {
              incident.setState(RecoveryStates.ANALYZING);
              incidentRepository.save(incident);
            });
    return saved;
  }

  @Transactional
  public Micro6PatchProposal requestChanges(String proposalId, String incidentId, String actorId) {
    Micro6PatchProposal proposal = load(proposalId, incidentId);
    requireReviewable(proposal);
    proposal.setStatus(RecoveryStates.CHANGES_REQUESTED);
    Micro6PatchProposal saved = proposalRepository.save(proposal);
    incidentRepository
        .findWithLockByIncidentId(incidentId)
        .ifPresent(
            incident -> {
              incident.setState(RecoveryStates.ANALYZING);
              incidentRepository.save(incident);
            });
    return saved;
  }

  public Micro6PatchProposal load(String proposalId, String incidentId) {
    return proposalRepository
        .findByProposalIdAndIncidentId(proposalId, incidentId)
        .orElseThrow(
            () ->
                com.chromascape.web.debug.buildergoal.recovery.RecoveryException.notFound(
                    "PROPOSAL_NOT_FOUND", "No such proposal under the requested run's incident"));
  }

  public Micro6PatchProposal latestForIncident(String incidentId) {
    return proposalRepository.findByIncidentIdOrderByVersionDesc(incidentId).stream()
        .findFirst()
        .orElse(null);
  }

  /** Rebuilds the canonical content from persisted columns; any change alters the hash. */
  public String recomputeProposalHash(Micro6PatchProposal proposal) {
    ObjectNode canonical = buildCanonicalContentFromEntity(proposal);
    return com.chromascape.web.aiplayer.contract.CanonicalJsonHasher.hash(canonical, null);
  }

  private void requireReviewable(Micro6PatchProposal proposal) {
    if (!RecoveryStates.AWAITING_REVIEW.equals(proposal.getStatus())) {
      throw com.chromascape.web.debug.buildergoal.recovery.RecoveryException.conflict(
          com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes.PROPOSAL_STALE,
          "Proposal is no longer awaiting review");
    }
    if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(Instant.now())) {
      throw com.chromascape.web.debug.buildergoal.recovery.RecoveryException.conflict(
          com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes.PROPOSAL_EXPIRED,
          "Proposal has expired");
    }
  }

  private void verifyPreimages(
      PatchParser.ParsedPatch parsed,
      Micro6SourceSnapshotService.SourceSnapshot snapshot,
      List<String> affectedPaths) {
    for (PatchParser.PatchFile file : parsed.files()) {
      if ("ADD".equals(file.changeType())) {
        String emptyHash = RecoveryHashes.sha256("");
        String declared =
            snapshot.files().containsKey(file.path())
                ? snapshot.files().get(file.path()).contentHash()
                : null;
        if (declared != null && !emptyHash.equals(declared)) {
          throw com.chromascape.web.debug.buildergoal.recovery.RecoveryException.unprocessable(
              com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes
                  .PATCH_PREIMAGE_MISMATCH,
              "New-file proposal conflicts with an existing pinned source file");
        }
        continue;
      }
      String expected =
          snapshot.files().containsKey(file.path())
              ? snapshot.files().get(file.path()).contentHash()
              : null;
      if (expected == null) {
        throw com.chromascape.web.debug.buildergoal.recovery.RecoveryException.unprocessable(
            com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes
                .PATCH_PREIMAGE_MISMATCH,
            "Pinned base is missing a file the proposal modifies");
      }
    }
  }

  private Map<String, Object> buildCitations(
      PatchParser.ParsedPatch parsed,
      List<BuilderGoalCompileDiagnosticParser.Diagnostic> diagnostics) {
    Map<String, Object> citations = new LinkedHashMap<>();
    for (PatchParser.PatchFile file : parsed.files()) {
      List<Map<String, Object>> fileCitations = new ArrayList<>();
      for (BuilderGoalCompileDiagnosticParser.Diagnostic diagnostic : diagnostics) {
        boolean sameFile =
            diagnostic.relativeFile() != null && diagnostic.relativeFile().equals(file.path());
        boolean taskLevel = diagnostic.relativeFile() == null;
        if (sameFile || taskLevel) {
          Map<String, Object> citation = new LinkedHashMap<>();
          citation.put("kind", taskLevel ? "GRADLE_TASK" : "COMPILER_DIAGNOSTIC");
          citation.put("file", diagnostic.relativeFile());
          citation.put("line", diagnostic.line());
          citation.put("severity", diagnostic.severity());
          citation.put("message", diagnostic.message());
          fileCitations.add(citation);
          if (fileCitations.size() >= 10) {
            break;
          }
        }
      }
      if (fileCitations.isEmpty()) {
        throw com.chromascape.web.debug.buildergoal.recovery.RecoveryException.unprocessable(
            com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes
                .PATCH_EVIDENCE_INCOMPLETE,
            "Every changed file must cite at least one diagnostic or run-evidence reference: "
                + file.path());
      }
      citations.put(file.path(), fileCitations);
    }
    return citations;
  }

  private ObjectNode buildCanonicalContent(
      BuilderGoalRecoveryIncident incident,
      int version,
      PatchParser.ParsedPatch parsed,
      Micro6SourceSnapshotService.SourceSnapshot snapshot,
      Map<String, Object> citations,
      String validationPlanHash,
      ProposalInput input,
      Instant createdAt) {
    ObjectNode content = objectMapper.createObjectNode();
    content.put("schema", PROPOSAL_SCHEMA);
    content.put("incident_id", incident.getIncidentId());
    content.put("iteration_id", incident.getIterationId());
    content.put("version", version);
    content.put("source_snapshot_id", snapshot.sourceSnapshotId());
    content.put("source_manifest_hash", snapshot.sourceManifestHash());
    content.put("repository_id", snapshot.repositoryId());
    content.put("base_commit", snapshot.commitId());
    content.put("base_tree", snapshot.treeId());
    content.put("base_build_id", snapshot.buildId());
    content.put("base_artifact_digest", snapshot.artifactDigest());
    content.put("canonical_diff", input.canonicalDiff());
    Map<String, Object> sortedPreimages = new TreeMap<>();
    snapshot.files().forEach((path, file) -> sortedPreimages.put(path, file.contentHash()));
    content.set("preimages", objectMapper.valueToTree(sortedPreimages));
    content.set("citations", objectMapper.valueToTree(citations));
    content.put("validation_plan_hash", validationPlanHash);
    content.put("generator_version", GENERATOR_VERSION);
    content.put("created_at", createdAt.toString());
    return content;
  }

  private ObjectNode buildCanonicalContentFromEntity(Micro6PatchProposal proposal) {
    ObjectNode content = objectMapper.createObjectNode();
    content.put("schema", PROPOSAL_SCHEMA);
    content.put("incident_id", proposal.getIncidentId());
    content.put("iteration_id", readIncidentIterationId(proposal));
    content.put("version", proposal.getVersion());
    content.put("source_snapshot_id", proposal.getSourceSnapshotId());
    content.put("source_manifest_hash", proposal.getSourceManifestHash());
    content.put("repository_id", proposal.getRepositoryId());
    content.put("base_commit", proposal.getBaseCommit());
    content.put("base_tree", proposal.getBaseTree());
    content.put("base_build_id", proposal.getBaseBuildId());
    content.put("base_artifact_digest", proposal.getBaseArtifactDigest());
    content.put("canonical_diff", proposal.getCanonicalDiff());
    content.set("preimages", readTree(proposal.getPreimagesJson()));
    content.set("citations", readTree(proposal.getCitationsJson()));
    content.put("validation_plan_hash", proposal.getValidationPlanHash());
    content.put("generator_version", GENERATOR_VERSION);
    content.put("created_at", String.valueOf(proposal.getCreatedAt()));
    return content;
  }

  private String readIncidentIterationId(Micro6PatchProposal proposal) {
    return incidentRepository
        .findWithLockByIncidentId(proposal.getIncidentId())
        .map(BuilderGoalRecoveryIncident::getIterationId)
        .orElse("");
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json == null ? "{}" : json);
    } catch (Exception e) {
      return objectMapper.createObjectNode();
    }
  }

  private String validationPlanJson() {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("schema", "micro6-validation-plan/v1");
    plan.put("commandKey", properties.getGradleCommandKey());
    plan.put(
        "gates",
        List.of(
            "SOURCE_PREIMAGE_VERIFICATION",
            "STRICT_PATCH_CHECK_APPLY",
            "CHANGED_FILE_ALLOWLIST_EQUALITY",
            "COMPILE_TYPE_CHECK",
            "CHECKSTYLE_STATIC",
            "AFFECTED_UNIT_TESTS",
            "MANDATORY_CONTRACTS",
            "PACKAGE_SBOM_PROVENANCE",
            "CLEANUP_ATTESTATION"));
    plan.put("networkDenied", true);
    plan.put("controlAllowed", false);
    plan.put("rebaseAllowed", false);
    return json(plan);
  }

  private Map<String, Object> generatorJson(ProposalInput input) {
    Map<String, Object> generator = new LinkedHashMap<>();
    generator.put("version", GENERATOR_VERSION);
    generator.put("identity", input.proposerIdentity());
    Object rationale = input.generatorMetadata().get("model");
    if (rationale != null) {
      generator.put("model", String.valueOf(rationale));
    }
    return generator;
  }

  private String sanitize(Object value) {
    return value == null
        ? null
        : com.chromascape.web.debug.buildergoal.service.BuilderGoalSensitiveTextSanitizer.sanitize(
            String.valueOf(value));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }
}
