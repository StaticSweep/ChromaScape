package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchApproval;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchApprovalRepository;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6PatchProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records exact human approvals bound to the proposal, source binding, pre-image set, and
 * validation plan. Every binding is recomputed server-side at approval time; the proposing identity
 * can never approve its own patch.
 */
@Service
public class PatchApprovalService {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_INVALIDATED = "INVALIDATED";
  private static final String PROPOSER_ROLE = "AI_PROVIDER";

  private final Micro6PatchApprovalRepository approvalRepository;
  private final Micro6PatchProposalRepository proposalRepository;
  private final PatchProposalService proposalService;
  private final BuilderGoalRecoveryProperties properties;
  private final ObjectMapper objectMapper;

  public PatchApprovalService(
      Micro6PatchApprovalRepository approvalRepository,
      Micro6PatchProposalRepository proposalRepository,
      PatchProposalService proposalService,
      BuilderGoalRecoveryProperties properties,
      ObjectMapper objectMapper) {
    this.approvalRepository = approvalRepository;
    this.proposalRepository = proposalRepository;
    this.proposalService = proposalService;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Micro6PatchApproval approve(
      String proposalId,
      String incidentId,
      String claimedProposalHash,
      String actorId,
      String actorRole,
      String reason,
      String environment) {
    if (actorId == null || actorId.isBlank()) {
      throw RecoveryException.forbidden(
          RecoveryFailureCodes.APPROVAL_FORBIDDEN, "An authenticated operator is required");
    }
    Micro6PatchProposal proposal =
        proposalRepository
            .findByProposalIdAndIncidentId(proposalId, incidentId)
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "PROPOSAL_NOT_FOUND", "No such proposal under the requested run"));
    if (!"AWAITING_REVIEW".equals(proposal.getStatus())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.PROPOSAL_STALE, "Proposal is no longer awaiting review");
    }
    if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(Instant.now())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.PROPOSAL_EXPIRED, "Proposal has expired");
    }
    String recomputed = proposalService.recomputeProposalHash(proposal);
    if (!recomputed.equals(proposal.getProposalHash())) {
      invalidateExisting(proposal.getProposalHash());
      throw RecoveryException.conflict(
          RecoveryFailureCodes.PROPOSAL_STALE,
          "The proposal changed after it was rendered; a re-review is required");
    }
    if (claimedProposalHash != null && !claimedProposalHash.equals(proposal.getProposalHash())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.PROPOSAL_STALE,
          "The approved proposal checksum no longer matches the stored proposal");
    }
    String generatorIdentity = readGeneratorIdentity(proposal.getGeneratorJson());
    if (actorId.equals(generatorIdentity) || PROPOSER_ROLE.equals(actorRole)) {
      throw RecoveryException.forbidden(
          RecoveryFailureCodes.APPROVAL_FORBIDDEN,
          "The proposing identity can never approve its own patch");
    }
    if (reason == null || reason.isBlank()) {
      throw RecoveryException.badRequest(
          "APPROVAL_REASON_REQUIRED", "An approval reason is required");
    }
    Micro6PatchApproval existing =
        approvalRepository
            .findByProposalHashAndStatus(proposal.getProposalHash(), STATUS_ACTIVE)
            .orElse(null);
    if (existing != null) {
      if (existing.getProposalId().equals(proposalId) && actorId.equals(existing.getActorId())) {
        return existing;
      }
      throw RecoveryException.conflict(
          RecoveryFailureCodes.APPROVAL_INVALIDATED,
          "An active approval already exists for this proposal");
    }
    Instant now = Instant.now();
    ObjectNode approvalContent = objectMapper.createObjectNode();
    approvalContent.put("schema", "micro6-patch-approval/v1");
    approvalContent.put("proposal_id", proposalId);
    approvalContent.put("proposal_hash", proposal.getProposalHash());
    approvalContent.put("source_binding_hash", sourceBindingHash(proposal));
    approvalContent.put("preimage_set_hash", preimageSetHash(proposal));
    approvalContent.put("validation_plan_hash", proposal.getValidationPlanHash());
    approvalContent.put("actor_id", actorId);
    approvalContent.put("actor_role", actorRole);
    approvalContent.put("reason", reason);
    approvalContent.put("environment", environment);
    approvalContent.put("policy_version", properties.getPolicyVersion());
    approvalContent.put("approved_at", now.toString());
    approvalContent.put("expires_at", now.plus(properties.getApprovalTtl()).toString());
    String approvalHash =
        com.chromascape.web.aiplayer.contract.CanonicalJsonHasher.hash(approvalContent, null);

    Micro6PatchApproval approval = new Micro6PatchApproval();
    approval.setApprovalId(RecoveryHashes.shortId("approval", approvalHash));
    approval.setProposalId(proposalId);
    approval.setProposalHash(proposal.getProposalHash());
    approval.setSourceBindingHash(sourceBindingHash(proposal));
    approval.setPreimageSetHash(preimageSetHash(proposal));
    approval.setValidationPlanHash(proposal.getValidationPlanHash());
    approval.setActorId(actorId);
    approval.setActorRole(actorRole);
    approval.setReason(reason);
    approval.setEnvironment(environment);
    approval.setPolicyVersion(properties.getPolicyVersion());
    approval.setApprovedAt(now);
    approval.setExpiresAt(now.plus(properties.getApprovalTtl()));
    approval.setApprovalHash(approvalHash);
    approval.setStatus(STATUS_ACTIVE);
    return approvalRepository.save(approval);
  }

  @Transactional
  public void invalidateExisting(String proposalHash) {
    approvalRepository
        .findByProposalHashAndStatus(proposalHash, STATUS_ACTIVE)
        .ifPresent(
            approval -> {
              approval.setStatus(STATUS_INVALIDATED);
              approvalRepository.save(approval);
            });
  }

  public Micro6PatchApproval loadActive(String approvalId, String proposalId) {
    Micro6PatchApproval approval =
        approvalRepository
            .findByApprovalIdAndProposalId(approvalId, proposalId)
            .orElseThrow(
                () ->
                    RecoveryException.notFound(
                        "APPROVAL_NOT_FOUND", "No such approval for the requested proposal"));
    if (!STATUS_ACTIVE.equals(approval.getStatus())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.APPROVAL_INVALIDATED, "The approval is no longer active");
    }
    if (approval.getExpiresAt() != null && approval.getExpiresAt().isBefore(Instant.now())) {
      throw RecoveryException.conflict(
          RecoveryFailureCodes.APPROVAL_INVALIDATED, "The approval has expired");
    }
    return approval;
  }

  private String sourceBindingHash(Micro6PatchProposal proposal) {
    return RecoveryHashes.sha256(
        String.join(
            "|",
            proposal.getRepositoryId(),
            proposal.getSourceSnapshotId(),
            proposal.getSourceManifestHash(),
            proposal.getBaseCommit(),
            proposal.getBaseTree(),
            proposal.getBaseBuildId(),
            proposal.getBaseArtifactDigest()));
  }

  private String preimageSetHash(Micro6PatchProposal proposal) {
    Map<String, Object> preimages = readMap(proposal.getPreimagesJson());
    return RecoveryHashes.sha256(
        String.join(
            "\n",
            new TreeMap<>(preimages)
                .entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList()));
  }

  private String readGeneratorIdentity(String generatorJson) {
    try {
      Map<String, Object> generator = objectMapper.readValue(generatorJson, Map.class);
      Object identity = generator.get("identity");
      return identity == null ? null : String.valueOf(identity);
    } catch (Exception e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMap(String json) {
    try {
      return objectMapper.readValue(json == null ? "{}" : json, Map.class);
    } catch (Exception e) {
      return new LinkedHashMap<>();
    }
  }
}
