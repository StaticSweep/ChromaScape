package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.config.SourceTrustProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fetches and verifies the Micro6 source manifest for one run identity without touching the
 * database.
 *
 * <p>The remote HTTP request must never run inside a database transaction: this bean owns all
 * network and verification work so persistence beans can wrap their writes in one short,
 * rollback-safe transaction ({@link SourceRunOrchestrationService}).
 */
@Service
public class SourceManifestFetchService {
  private final Micro6SourceManifestClient upstream;
  private final SourceTrustProperties trust;
  private final CurrentSliceIdentityValidator identities;

  public SourceManifestFetchService(
      Micro6SourceManifestClient upstream,
      SourceTrustProperties trust,
      CurrentSliceIdentityValidator identities) {
    this.upstream = upstream;
    this.trust = trust;
    this.identities = identities;
  }

  /** A manifest verified against the run's attested identity plus the exact bytes verified. */
  public record FetchedSource(
      TaskSourceManifestVerifier.Verified verified,
      String manifestJson,
      TaskSourceManifestVerifier.ExpectedIdentity expected) {}

  /**
   * Fetches the manifest from Micro6 with no transaction active, parses and verifies it against the
   * run's already-attested client identity, and enforces the configured environment identity.
   * Performs no database writes.
   *
   * <p>{@code NOT_SUPPORTED} guarantees the upstream HTTP call never runs inside a database
   * transaction, even when the caller already holds one: a remote read must never pin a connection
   * or become part of a rollback decision.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public FetchedSource fetchForRun(String runId, String taskKey, CurrentSliceIdentity identity) {
    if (upstream == null) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_UNAVAILABLE, "source manifest endpoint is unavailable");
    }
    if (runId == null
        || runId.isBlank()
        || !SourceManifestContract.isTaskKey(taskKey)
        || identity == null
        || identity.run() == null
        || !runId.equals(identity.run().runId())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run and source task identity are required");
    }
    if (identity.task() == null || !taskKey.equals(identity.task().taskKey())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run task identity differs from the requested source task");
    }
    if (identity.source() == null || !Boolean.TRUE.equals(identity.source().signatureVerified())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "complete verified source identity is required for source binding");
    }
    identities.validate(identity);
    String manifestJson = upstream.manifest(taskKey);
    try {
      JsonNode raw =
          SourceManifestCanonicalJson.parse(manifestJson.getBytes(StandardCharsets.UTF_8));
      TaskSourceManifestVerifier.ExpectedIdentity expected = expectedIdentity(taskKey, identity);
      TaskSourceManifestVerifier.Verified verified = verifierFor(trust).verify(raw, expected);
      if (!taskKey.equals(verified.manifest().taskKey())) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
            "task identity differs from the requested task");
      }
      enforceEnvironment(trust, raw);
      rejectTerminalStatus(verified.manifest().status());
      return new FetchedSource(verified, manifestJson, expected);
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest could not be read",
          exception);
    }
  }

  /** Builds the verifier from the single canonical trust configuration. */
  static TaskSourceManifestVerifier verifierFor(SourceTrustProperties trust) {
    if (trust == null) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest trust configuration is missing");
    }
    SourceManifestSignatureVerifier verifier =
        SourceManifestSignatureVerifier.fromX509Base64(
            trust.getPublicKeyBase64(), trust.getPublicKeyId());
    if (verifier == null) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest trust configuration is incomplete");
    }
    return new TaskSourceManifestVerifier(verifier);
  }

  /** Builds the expected source identity from the run's attested client identity. */
  static TaskSourceManifestVerifier.ExpectedIdentity expectedIdentity(
      String taskKey, CurrentSliceIdentity identity) {
    CurrentSliceIdentity.Source source = identity.source();
    CurrentSliceIdentity.Client client = identity.client();
    return new TaskSourceManifestVerifier.ExpectedIdentity(
        taskKey,
        source == null ? null : source.repositoryId(),
        source == null ? null : source.commitId(),
        source == null ? null : source.treeId(),
        source != null && source.buildId() != null
            ? source.buildId()
            : client == null ? null : client.clientBuildId(),
        source != null && source.artifactDigest() != null
            ? source.artifactDigest()
            : client == null ? null : client.artifactDigest(),
        source == null ? null : source.sourceSnapshotId(),
        source == null ? null : source.sourceManifestHash());
  }

  /** Fails when the manifest's declared environment differs from the configured environment. */
  static void enforceEnvironment(SourceTrustProperties trust, JsonNode manifest) {
    String configured = trust == null ? "" : trust.getEnvironment();
    String declared = declaredEnvironment(manifest);
    if (configured.isBlank() || declared == null || !configured.equals(declared)) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "source environment identity differs from the configured environment");
    }
  }

  private static void rejectTerminalStatus(String status) {
    if (status == null || "AVAILABLE".equals(status)) return;
    SourceManifestFailureCode code =
        switch (status) {
          case "INDEX_PARTIAL" -> SourceManifestFailureCode.SOURCE_INDEX_PARTIAL;
          case "UNAVAILABLE" -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
          case "REDACTED" -> SourceManifestFailureCode.SOURCE_REDACTED;
          case "PRUNED" -> SourceManifestFailureCode.SOURCE_PRUNED;
          case "GENERATED_ONLY" -> SourceManifestFailureCode.SOURCE_GENERATED_ONLY;
          default -> SourceManifestFailureCode.SOURCE_MANIFEST_INVALID;
        };
    throw new SourceManifestException(code, "source manifest is not available for binding");
  }

  static String declaredEnvironment(JsonNode manifest) {
    JsonNode value = manifest == null ? null : manifest.get("environment");
    return value != null && value.isTextual() && !value.textValue().isBlank()
        ? value.textValue().trim()
        : null;
  }
}
