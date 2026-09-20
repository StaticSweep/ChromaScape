package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.ApprovalView;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.RecordingArtifact;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.RecordingView;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the immutable SC-1498 evidence manifest from durable rows and artifact digests.
 *
 * <p>The manifest is published atomically only after durable terminal reconciliation and recording
 * finalization. It carries safe digests only: no operator keys, API keys, HMAC secrets, raw
 * signatures, fence tokens, full prompts or unredacted observations.
 */
@Service
public final class BootstrapEvidenceManifestService {
  private final ObjectMapper objectMapper;
  private final CanonicalGameplayJson canonicalJson;
  private final Path root;

  @Autowired
  public BootstrapEvidenceManifestService(
      ObjectMapper objectMapper,
      CanonicalGameplayJson canonicalJson,
      @Value("${gameplay.bootstrap.evidence-root:.chromascape/bootstrap-recordings}")
          String evidenceRoot) {
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.root = Path.of(evidenceRoot);
  }

  BootstrapEvidenceManifestService(
      ObjectMapper objectMapper, CanonicalGameplayJson canonicalJson, Path root) {
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.root = root;
  }

  /**
   * Builds the manifest as a pure function of durable inputs. {@code finalizedAt} must be a durable
   * timestamp (the persisted SC-1431 terminal-result timestamp, or the persisted terminal view
   * update time); it is never sampled from the wall clock here so a crash between publication and
   * database finalization replays byte-identical bytes and digest.
   */
  public ManifestPublication buildManifest(
      ApprovalView approval,
      RecordingView recording,
      RecordingArtifact artifact,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      JsonNode observation,
      HumanAuthority authority,
      OrchestrationView orchestration,
      Instant finalizedAt) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("contractVersion", "sc-1429/v1");
    document.put("recordingKind", "BOOTSTRAP_RECORDING");
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("origin", decision.provider().name());
    provenance.put("modelVersion", decision.modelVersion());
    provenance.put("providerSessionId", approval.providerSessionId());
    provenance.put("providerTurnId", approval.providerTurnId());
    provenance.put("decisionId", decision.decisionId());
    provenance.put("decisionDigest", canonicalJson.digest(decision));
    provenance.put("semanticActionDigest", canonicalJson.digest(decision.semanticAction()));
    document.put("provenance", provenance);
    Map<String, Object> identities = new LinkedHashMap<>();
    identities.put("orchestrationId", approval.orchestrationId());
    identities.put("requestId", approval.requestId());
    identities.put("recordingId", approval.recordingId());
    identities.put("authorityId", authority.authorityId());
    document.put("identities", identities);
    document.put("observationDigest", canonicalJson.digest(observation));
    document.put("envelopeDigest", canonicalJson.digest(envelope));
    document.put("scopeDigest", approval.scopeDigest());
    document.put("requestFingerprint", approval.requestFingerprint());
    document.put("bindingsDigest", canonicalJson.digest(bindings));
    Map<String, Object> approvalEvidence = new LinkedHashMap<>();
    approvalEvidence.put("approvedBy", approval.approvedBy());
    approvalEvidence.put("approvedAt", approval.approvedAt().toString());
    approvalEvidence.put("expiresAt", approval.expiresAt().toString());
    approvalEvidence.put("signatureDigest", approval.signatureDigest());
    document.put("humanApproval", approvalEvidence);
    Map<String, Object> orchestrationEvidence = new LinkedHashMap<>();
    orchestrationEvidence.put("state", orchestration.state().name());
    orchestrationEvidence.put("failureCode", orchestration.failureCode().name());
    orchestrationEvidence.put("envelopeDigest", orchestration.envelopeDigest());
    orchestrationEvidence.put("authorityConsumed", orchestration.authorityConsumed());
    orchestrationEvidence.put("retryAllowed", orchestration.retryAllowed());
    orchestrationEvidence.put("receiptCount", orchestration.receipts().size());
    orchestrationEvidence.put(
        "receiptDigests", orchestration.receipts().stream().map(canonicalJson::digest).toList());
    document.put("orchestration", orchestrationEvidence);
    document.put("recordingId", recording.recordingId());
    Map<String, Object> recordingEvidence = new LinkedHashMap<>();
    recordingEvidence.put("recordingId", recording.recordingId());
    recordingEvidence.put(
        "recordingSessionId", artifact == null ? null : artifact.recordingSessionId());
    recordingEvidence.put("videoPath", artifact == null ? null : artifact.videoPath());
    recordingEvidence.put("videoDigest", artifact == null ? null : artifact.artifactDigest());
    recordingEvidence.put("durationMs", artifact == null ? null : artifact.durationMs());
    recordingEvidence.put("frameCount", artifact == null ? null : artifact.frameCount());
    recordingEvidence.put("fps", artifact == null ? null : artifact.fps());
    recordingEvidence.put("videoCodec", artifact == null ? null : artifact.videoCodec());
    document.put("recording", recordingEvidence);
    document.put("terminalOutcome", orchestration.state().name());
    document.put("stableCode", orchestration.failureCode().name());
    document.put("startedAt", recording.createdAt().toString());
    if (finalizedAt == null) {
      throw new IllegalArgumentException("A durable finalization timestamp is required");
    }
    document.put("finalizedAt", finalizedAt.toString());
    document.put("learnedModelActivated", false);
    document.put("continuingAuthority", false);
    document.put("simulatorTrace", false);
    String manifestDigest = canonicalJson.digest(document);
    document.put("evidenceDigest", manifestDigest);
    document.put("manifestDigest", manifestDigest);
    return new ManifestPublication(document, manifestDigest);
  }

  /**
   * Publishes the manifest atomically. The target already existing with a different digest is a
   * terminal conflict; the identical digest is an idempotent replay.
   */
  public Path publish(String recordingId, Map<String, Object> document, String manifestDigest) {
    try {
      Files.createDirectories(root);
      Path target = root.resolve(recordingId + ".sc-1498.json");
      if (Files.exists(target)) {
        JsonNode existing = objectMapper.readTree(target.toFile());
        String existingDigest =
            existing.path("manifestDigest").asText(existing.path("evidenceDigest").asText(null));
        if (manifestDigest.equals(existingDigest)) {
          return target;
        }
        throw new IllegalStateException("Immutable bootstrap evidence already exists: " + target);
      }
      Path temporary = Files.createTempFile(root, recordingId + ".", ".pending");
      try {
        objectMapper.writeValue(temporary.toFile(), document);
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
          throw new IllegalStateException(
              "Evidence filesystem does not support atomic finalization", exception);
        }
        return target;
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to atomically publish bootstrap manifest", exception);
    }
  }

  public List<String> evidenceRefs(String recordingId, String manifestDigest) {
    return List.of(
        "chromascape:bootstrap:" + recordingId, "chromascape:manifest:" + manifestDigest);
  }

  public record ManifestPublication(Map<String, Object> document, String manifestDigest) {}
}
