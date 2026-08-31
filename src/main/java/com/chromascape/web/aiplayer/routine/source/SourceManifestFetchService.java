package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
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
   * Fetches the manifest from Micro6 with no transaction active, parses and verifies it against
   * the run's already-attested client identity, and enforces the configured environment identity.
   * Performs no database writes.
   *
   * <p>{@code NOT_SUPPORTED} guarantees the upstream HTTP call never runs inside a database
   * transaction, even when the caller already holds one: a remote read must never pin a connection
   * or become part of a rollback decision.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public FetchedSource fetchForRun(String runId, String taskKey, CurrentSliceIdentity identity) {
    if (upstream == null) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_UNAVAILABLE,
          "source manifest endpoint is unavailable");
    }
    if (runId == null || runId.isBlank() || !SourceManifestContract.isTaskKey(taskKey)
        || identity == null || identity.run() == null || !runId.equals(identity.run().runId())) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run and source task identity are required");
    }
    if (identity.task() == null || !taskKey.equals(identity.task().taskKey())) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "run task identity differs from the requested source task");
    }
    identities.validate(identity);
    String manifestJson = upstream.manifest(taskKey);
    try {
      JsonNode raw = CanonicalJsonHasher.parse(
          manifestJson.getBytes(StandardCharsets.UTF_8));
      String schema = raw.path("schema").asText();
      boolean legacy = SourceManifestContract.LEGACY_SCHEMA.equals(schema)
          || SourceManifestContract.LEGACY_SCHEMA_V2.equals(schema);
      TaskSourceManifestVerifier.ExpectedIdentity expected = expectedIdentity(taskKey, identity, legacy);
      TaskSourceManifestVerifier.Verified verified = verifierFor(trust).verify(raw, expected);
      if (!taskKey.equals(verified.manifest().taskKey())) {
        throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
            "task identity differs from the requested task");
      }
      enforceEnvironment(trust, raw);
      return new FetchedSource(verified, manifestJson, expected);
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest could not be read", exception);
    }
  }

  /** Builds the verifier from the single canonical trust configuration. */
  static TaskSourceManifestVerifier verifierFor(SourceTrustProperties trust) {
    return new TaskSourceManifestVerifier(trust.isRequireSignature(), trust.isAllowLegacy(),
        SourceManifestSignatureVerifier.fromX509Base64(
            trust.getPublicKeyBase64(), trust.getPublicKeyId()));
  }

  /** Builds the expected source identity from the run's attested client identity. */
  static TaskSourceManifestVerifier.ExpectedIdentity expectedIdentity(
      String taskKey, CurrentSliceIdentity identity, boolean legacy) {
    CurrentSliceIdentity.Source source = identity.source();
    if (legacy) {
      return new TaskSourceManifestVerifier.ExpectedIdentity(
          taskKey, null, null, null, null, null,
          source == null ? null : source.sourceSnapshotId(),
          source == null ? null : source.sourceManifestHash());
    }
    CurrentSliceIdentity.Client client = identity.client();
    return new TaskSourceManifestVerifier.ExpectedIdentity(
        taskKey,
        source == null ? null : source.repositoryId(),
        source == null ? null : source.commitId(),
        source == null ? null : source.treeId(),
        source != null && source.buildId() != null ? source.buildId()
            : client == null ? null : client.clientBuildId(),
        source != null && source.artifactDigest() != null ? source.artifactDigest()
            : client == null ? null : client.artifactDigest(),
        source == null ? null : source.sourceSnapshotId(),
        source == null ? null : source.sourceManifestHash());
  }

  /** Fails when the manifest's declared environment differs from the configured environment. */
  static void enforceEnvironment(SourceTrustProperties trust, JsonNode manifest) {
    String configured = trust.configuredEnvironment();
    if (configured.isBlank()) {
      return;
    }
    String declared = declaredEnvironment(manifest);
    if (declared == null || !configured.equals(declared)) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "source environment identity differs from the configured environment");
    }
  }

  static String declaredEnvironment(JsonNode manifest) {
    JsonNode value = manifest == null ? null : manifest.get("environment");
    return value != null && value.isTextual() && !value.textValue().isBlank()
        ? value.textValue().trim() : null;
  }
}
