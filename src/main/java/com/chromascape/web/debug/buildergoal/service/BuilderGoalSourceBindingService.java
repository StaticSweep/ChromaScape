package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.SourceManifestBindingService;
import com.chromascape.web.aiplayer.routine.source.SourceManifestException;
import com.chromascape.web.aiplayer.routine.source.TaskSourceManifestVerifier;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.config.SourceTrustProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Binds a dispatched builder-goal run to the verified source snapshot it actually executed against.
 *
 * <p>SC-810 already modelled this exactly — {@code source_run_bindings} and {@link
 * SourceManifestBindingService} verify signature, environment and availability before any write —
 * but only the AI-player stabilization flow ({@code SourceRunOrchestrationService}) ever called it.
 * Builder-goal dispatch persisted no binding at all, so once the executor stopped, nothing recorded
 * which snapshot or artifact a historical run had used. This service closes that gap using the same
 * verification path rather than a parallel one.
 *
 * <p><strong>Attestation, and its present limit.</strong> When the caller supplies a build id and
 * artifact digest independently attested by the running executor, they are pinned into the expected
 * identity, and a signed manifest describing a different build cannot bind the run. Today the
 * Micro6 heartbeat does <em>not</em> carry those fields — {@code
 * Micro6OnlineClientRegistryService.TargetClient} has no artifact or build identity — so binding
 * normally proceeds unpinned and attests <em>the signed source the executor served</em> rather than
 * an independently corroborated artifact. That is a genuine weakening and is stated plainly instead
 * of being papered over: closing it requires adding {@code artifact_digest} / {@code build_id} to
 * the heartbeat contract, which changes the Micro6 artifact and therefore re-issues the signed
 * bundle. The pinning path is implemented and used the moment those fields arrive.
 *
 * <p><strong>Transport.</strong> The manifest is read through {@link Micro6SourceManifestClient},
 * which accepts a task key and nothing else — no caller-supplied URL crosses that boundary, by
 * design. Binding therefore targets the configured source transport, so an instance dispatching to
 * several distinct executors would need that transport configured per executor. That is a real
 * limitation and is recorded here rather than worked around by passing a run-supplied URL, which
 * would reintroduce exactly the server-side request forgery surface the client deliberately avoids.
 */
@Service
public class BuilderGoalSourceBindingService {

  private static final Logger logger =
      LoggerFactory.getLogger(BuilderGoalSourceBindingService.class);

  /** A verified binding row exists for this run. */
  public static final String STATE_BOUND = "BOUND";

  /** Source trust is not configured on this instance; no binding was attempted. */
  public static final String STATE_NOT_CONFIGURED = "NOT_CONFIGURED";

  /** Binding was required and attempted, but did not complete. */
  public static final String STATE_FAILED = "FAILED";

  /** Matches the orchestration service: an immutable-identity conflict is retryable. */
  private static final int MAX_CONFLICT_RETRIES = 3;

  private final Micro6SourceManifestClient upstream;
  private final SourceManifestBindingService bindings;
  private final SourceTrustProperties trust;
  private final TransactionTemplate transactions;

  public BuilderGoalSourceBindingService(
      Micro6SourceManifestClient upstream,
      SourceManifestBindingService bindings,
      SourceTrustProperties trust,
      PlatformTransactionManager transactionManager) {
    this.upstream = upstream;
    this.bindings = bindings;
    this.trust = trust;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Outcome of one binding attempt; {@code binding} is null unless the state is BOUND. */
  public record BindingOutcome(String state, SourceRunBinding binding, String detail) {

    public boolean bound() {
      return STATE_BOUND.equals(state);
    }
  }

  /**
   * True when this instance has the trust configuration required to verify a source manifest.
   * Without all three values no signature can be checked, so binding is not merely skipped — it is
   * impossible, and the run is recorded as {@code NOT_CONFIGURED} rather than silently unbound.
   */
  public boolean trustConfigured() {
    return trust != null
        && trust.getPublicKeyBase64() != null
        && !trust.getPublicKeyBase64().isBlank()
        && trust.getPublicKeyId() != null
        && !trust.getPublicKeyId().isBlank()
        && trust.getEnvironment() != null
        && !trust.getEnvironment().isBlank();
  }

  /**
   * Fetches, verifies and durably binds the run's source.
   *
   * <p>The remote fetch happens outside any transaction; the manifest write and the binding write
   * then commit together, so a run is never recorded as bound to a manifest that was not persisted.
   *
   * @param runId the builder-goal iteration id
   * @param recordingId the run's recording id, or null when it has not been observed yet
   * @param taskKey the dispatched task key
   * @param attestedBuildId build id independently attested by the executor, or null when the
   *     heartbeat did not report one (see the class note on attestation)
   * @param attestedArtifactDigest artifact digest independently attested by the executor, or null
   */
  public BindingOutcome bindRun(
      String runId,
      String recordingId,
      String taskKey,
      String attestedBuildId,
      String attestedArtifactDigest) {
    if (runId == null || runId.isBlank() || taskKey == null || taskKey.isBlank()) {
      return new BindingOutcome(STATE_FAILED, null, "run and task identity are required");
    }
    if (!trustConfigured()) {
      logger.warn(
          "[SC-810] run {} dispatched without source binding: trust is not configured "
              + "(chromascape.source.public-key-base64 / public-key-id / environment)",
          runId);
      return new BindingOutcome(
          STATE_NOT_CONFIGURED, null, "source trust configuration is incomplete");
    }
    boolean executorAttested =
        attestedBuildId != null
            && !attestedBuildId.isBlank()
            && attestedArtifactDigest != null
            && !attestedArtifactDigest.isBlank();
    if (!executorAttested) {
      // Recorded at debug rather than warn: today this is the normal path for every builder-goal
      // run, because the heartbeat carries no artifact identity. It is a known limitation of the
      // contract, not a per-run anomaly, and warning on every run would train operators to ignore
      // it. The class javadoc states the limitation; the fix is a heartbeat contract change.
      logger.debug(
          "[SC-810] run {} binding without executor-attested artifact identity "
              + "(heartbeat does not report build_id/artifact_digest)",
          runId);
    }

    String manifestJson;
    try {
      manifestJson = upstream.manifest(taskKey);
    } catch (Exception exception) {
      return failure(runId, "source manifest could not be fetched", exception);
    }

    // Pin build/artifact only when the executor genuinely attested them. Passing the manifest's own
    // values back as "expected" would be a self-consistent tautology that looks like verification.
    TaskSourceManifestVerifier.ExpectedIdentity expected =
        executorAttested
            ? new TaskSourceManifestVerifier.ExpectedIdentity(
                taskKey, null, null, null, attestedBuildId, attestedArtifactDigest, null, null)
            : new TaskSourceManifestVerifier.ExpectedIdentity(
                taskKey, null, null, null, null, null, null, null);

    SourceManifestException lastConflict = null;
    for (int attempt = 1; attempt <= MAX_CONFLICT_RETRIES; attempt++) {
      try {
        String json = manifestJson;
        SourceRunBinding saved =
            transactions.execute(
                status -> {
                  TaskSourceManifestVerifier.Verified verified =
                      bindings.verifyAndPersist(taskKey, json, expected);
                  return bindings.bindRun(runId, recordingId, taskKey, verified);
                });
        if (saved == null) {
          return new BindingOutcome(STATE_FAILED, null, "source binding produced no result");
        }
        logger.info(
            "[SC-810] bound run {} to snapshot {} (artifact {}, build {})",
            runId,
            saved.getSnapshotId(),
            saved.getArtifactDigest(),
            saved.getBuildId());
        return new BindingOutcome(STATE_BOUND, saved, null);
      } catch (SourceManifestException exception) {
        if (!SourceManifestBindingService.isManifestConflict(exception)
            || attempt == MAX_CONFLICT_RETRIES) {
          return failure(runId, exception.getMessage(), exception);
        }
        lastConflict = exception;
      } catch (Exception exception) {
        return failure(runId, "source binding failed", exception);
      }
    }
    return failure(
        runId,
        lastConflict == null ? "source binding failed" : lastConflict.getMessage(),
        lastConflict);
  }

  /** Reads a persisted binding for API exposure, or null when the run has none. */
  public Map<String, Object> binding(String runId) {
    if (runId == null || runId.isBlank()) return null;
    try {
      return bindings.binding(runId);
    } catch (SourceManifestException exception) {
      return null;
    }
  }

  private BindingOutcome failure(String runId, String detail, Throwable cause) {
    String message = detail == null || detail.isBlank() ? "source binding failed" : detail;
    logger.warn("[SC-810] source binding failed for run {}: {}", runId, message, cause);
    return new BindingOutcome(STATE_FAILED, null, message);
  }
}
