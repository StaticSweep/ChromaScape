package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only construction of a worker that can exercise durable lifecycle behavior with the
 * explicitly synthetic replay kernel.
 *
 * <p>The production {@link ReplayWorker} constructor remains fail-closed for synthetic fidelity.
 * This class lives under test sources so the diagnostic switch cannot be enabled by application
 * configuration or shipped in the runtime artifact.
 */
public final class DiagnosticReplayWorkerFactory {

  private DiagnosticReplayWorkerFactory() {}

  public static ReplayWorker create(
      ReplayRunRepository runRepository,
      ReplayAttemptRepository attemptRepository,
      ReplayRunService runService,
      ScenarioManifestRepository manifestRepository,
      ReplayCheckpointRepository checkpointRepository,
      ArtifactRefRepository artifactRefRepository,
      ReplayMismatchRepository mismatchRepository,
      ReplayWorkerPersistenceService persistence,
      ReplayLeaseHeartbeat heartbeatService,
      ReplayExpiredAttemptRecoveryService recoveryService,
      ReplayArtifactService artifactService,
      MismatchEngine mismatchEngine,
      ReplayReportService reportService,
      CanonicalProjectionService projectionService,
      ReplayProperties properties,
      ObjectMapper mapper) {
    return new ReplayWorker(
        runRepository,
        attemptRepository,
        runService,
        manifestRepository,
        checkpointRepository,
        artifactRefRepository,
        mismatchRepository,
        persistence,
        heartbeatService,
        recoveryService,
        artifactService,
        mismatchEngine,
        reportService,
        projectionService,
        properties,
        mapper,
        true);
  }
}
