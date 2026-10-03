package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.aiplayer.routine.service.CandidateSigner;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryHashes;
import com.chromascape.web.debug.buildergoal.recovery.config.BuilderGoalRecoveryProperties;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6CandidateArtifact;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchApproval;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import com.chromascape.web.debug.buildergoal.recovery.repository.Micro6CandidateArtifactRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Atomically persists the validated candidate: package, SBOM, signed manifest, and validation
 * report. Partial writes stay in a staging directory and are never returned as candidates.
 */
@Service
public class CandidateArtifactService {

  private final BuilderGoalRecoveryProperties properties;
  private final Micro6CandidateArtifactRepository repository;
  private final ObjectMapper objectMapper;

  public CandidateArtifactService(
      BuilderGoalRecoveryProperties properties,
      Micro6CandidateArtifactRepository repository,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public record Finalized(Micro6CandidateArtifact candidate) {}

  public Micro6CandidateArtifact finalizeCandidate(
      Micro6PatchValidationJob job,
      Micro6PatchProposal proposal,
      Micro6PatchApproval approval,
      com.chromascape.web.aiplayer.routine.service.IsolatedPatchWorker.BuildResult result,
      String validationReportJson)
      throws IOException {
    if (result == null
        || result.jarBytes() == null
        || result.sbomBytes() == null
        || result.certificationBytes() == null) {
      throw new IOException("CANDIDATE_EVIDENCE_MISSING");
    }
    String packageDigest = RecoveryHashes.sha256(result.jarBytes());
    // Package bytes are content-addressed, but candidate identity is validation-lineage-addressed.
    // Two independently approved validations may legitimately build identical package bytes.
    String candidateId =
        RecoveryHashes.shortId(
            "cand",
            RecoveryHashes.sha256(
                String.join(
                    "|",
                    job.getJobId(),
                    proposal.getProposalHash(),
                    approval.getApprovalHash(),
                    packageDigest)));
    Micro6CandidateArtifact existing = repository.findByJobId(job.getJobId()).orElse(null);
    if (existing != null) {
      if (!candidateId.equals(existing.getCandidateId())
          || !packageDigest.equals(existing.getCandidateDigest())) {
        throw new IOException("CANDIDATE_JOB_IDENTITY_CONFLICT");
      }
      return existing;
    }
    CandidateSigner signer =
        new CandidateSigner(properties.getSigningKeyId(), Path.of(properties.getSigningKeyPath()));
    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("schema", "micro6-candidate-manifest/v1");
    manifest.put("candidate_id", candidateId);
    manifest.put("candidate_digest", packageDigest);
    manifest.put("resulting_tree_hash", resultingTreeHash(job, result));
    manifest.put("job_id", job.getJobId());
    manifest.put("proposal_id", proposal.getProposalId());
    manifest.put("proposal_hash", proposal.getProposalHash());
    manifest.put("patch_hash", proposal.getPatchHash());
    manifest.put("approval_id", approval.getApprovalId());
    manifest.put("approval_hash", approval.getApprovalHash());
    manifest.put("base_commit", proposal.getBaseCommit());
    manifest.put("base_tree", proposal.getBaseTree());
    manifest.put("source_snapshot_id", proposal.getSourceSnapshotId());
    manifest.put("source_manifest_hash", proposal.getSourceManifestHash());
    manifest.put("validation_plan_hash", proposal.getValidationPlanHash());
    manifest.put("toolchain_hash", job.getToolchainHash());
    manifest.put("jar_name", result.jarName());
    manifest.put("jar_size", (long) result.jarBytes().length);
    manifest.put("jar_sha256", packageDigest);
    manifest.put("sbom_name", result.sbomName());
    manifest.put("sbom_sha256", RecoveryHashes.sha256(result.sbomBytes()));
    manifest.put("certification_document_hash", RecoveryHashes.sha256(result.certificationBytes()));
    manifest.put("control_allowed", false);
    manifest.put("eligibility", "BUILD_VALIDATED");
    String unsignedHash = signer.unsignedHash(manifest);
    manifest.put("candidate_hash", unsignedHash);
    manifest.put("signature_algorithm", "Ed25519");
    manifest.put("key_id", properties.getSigningKeyId());
    manifest.put("signature", signer.signCandidateHash(unsignedHash));
    String manifestJson = objectMapper.writeValueAsString(manifest);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);

    Path root = Path.of(properties.getArtifactRoot()).toAbsolutePath().normalize();
    Path target = root.resolve("candidates").resolve(candidateId).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("candidate storage key escapes artifact root");
    }
    if (!Files.isRegularFile(target.resolve(result.jarName()))) {
      Path staging =
          root.resolve("candidates").resolve(".tmp").resolve(UUID.randomUUID().toString());
      Files.createDirectories(staging);
      try {
        writeNew(staging.resolve(result.jarName()), result.jarBytes());
        writeNew(staging.resolve(result.sbomName()), result.sbomBytes());
        writeNew(staging.resolve("certification.json"), result.certificationBytes());
        writeNew(staging.resolve("manifest.json"), manifestBytes);
        Files.createDirectories(target.getParent());
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        deleteQuietly(staging);
      }
    }

    Micro6CandidateArtifact candidate = new Micro6CandidateArtifact();
    candidate.setCandidateId(candidateId);
    candidate.setJobId(job.getJobId());
    candidate.setIncidentId(job.getIncidentId());
    candidate.setProposalId(proposal.getProposalId());
    candidate.setCandidateDigest(packageDigest);
    candidate.setResultingTreeHash(resultingTreeHash(job, result));
    candidate.setPackageStorageKey("candidates/" + candidateId + "/" + result.jarName());
    candidate.setPackageSha256(packageDigest);
    candidate.setPackageSize((long) result.jarBytes().length);
    candidate.setSbomStorageKey("candidates/" + candidateId + "/" + result.sbomName());
    candidate.setSbomSha256(RecoveryHashes.sha256(result.sbomBytes()));
    candidate.setManifestJson(manifestJson);
    candidate.setManifestHash(RecoveryHashes.sha256(manifestBytes));
    candidate.setSignature(String.valueOf(manifest.get("signature").asText()));
    candidate.setSigningKeyId(properties.getSigningKeyId());
    candidate.setValidationReportJson(validationReportJson == null ? "{}" : validationReportJson);
    candidate.setEligibilityState("BUILD_VALIDATED");
    candidate.setCreatedAt(Instant.now());
    candidate.setQuarantinedAt(Instant.now());
    return repository.save(candidate);
  }

  public Map<String, Object> toResponse(Micro6CandidateArtifact candidate) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("candidateId", candidate.getCandidateId());
    response.put("candidateDigest", candidate.getCandidateDigest());
    response.put("resultingTreeHash", candidate.getResultingTreeHash());
    response.put("eligibilityState", candidate.getEligibilityState());
    response.put("jobId", candidate.getJobId());
    response.put("proposalId", candidate.getProposalId());
    response.put("createdAt", String.valueOf(candidate.getCreatedAt()));
    response.put("validationReport", candidate.getValidationReportJson());
    return response;
  }

  private String resultingTreeHash(
      Micro6PatchValidationJob job,
      com.chromascape.web.aiplayer.routine.service.IsolatedPatchWorker.BuildResult result) {
    if (job.getResultingTreeHash() != null) {
      return job.getResultingTreeHash();
    }
    return RecoveryHashes.sha256(
        result.baseCommit() == null ? "" : result.baseCommit() + ":" + result.changedFiles());
  }

  private static void writeNew(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.getParent());
    try (var channel =
        java.nio.channels.FileChannel.open(
            target,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE)) {
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static void deleteQuietly(Path path) {
    try (var stream = Files.walk(path)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // Staging leftovers never become candidates; finalization already failed.
                }
              });
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }
}
