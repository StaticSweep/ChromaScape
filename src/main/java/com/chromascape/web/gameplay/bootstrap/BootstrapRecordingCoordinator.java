package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import com.chromascape.web.recording.service.RecordingService.RecordingStart;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Durable lifecycle for one SC-1498 bootstrap recording projection.
 *
 * <p>The exact preapproved recording is started through {@link RecordingService} before SC-1431
 * delivery, a matching {@link RecordingSession} row is created, and the STARTED projection is
 * persisted. On a proven terminal outcome the exact recording is stopped once, its video artifact
 * is digested, the session is finalized and the projection moves to FINALIZED. Finalization never
 * repeats gameplay: recovery resumes from durable evidence. Safety flags are always false and
 * enforced by PostgreSQL.
 */
@Repository
public class BootstrapRecordingCoordinator {
  private static final String ARTIFACT_DIGEST_PREFIX = "sha256:";
  private static final String ORCHESTRATION_LOCK_PREFIX = "sc1498-bootstrap-recording:";

  private final EntityManager entityManager;
  private final RecordingService recordingService;
  private final RecordingSessionRepository recordingSessionRepository;
  private final DebugSessionRepository debugSessionRepository;
  private final BootstrapFinalizationJournal finalizationJournal;
  private final Clock clock;

  @Autowired
  public BootstrapRecordingCoordinator(
      EntityManager entityManager,
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      DebugSessionRepository debugSessionRepository,
      BootstrapFinalizationJournal finalizationJournal) {
    this(
        entityManager,
        recordingService,
        recordingSessionRepository,
        debugSessionRepository,
        finalizationJournal,
        Clock.systemUTC());
  }

  BootstrapRecordingCoordinator(
      EntityManager entityManager,
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      DebugSessionRepository debugSessionRepository,
      BootstrapFinalizationJournal finalizationJournal,
      Clock clock) {
    this.entityManager = entityManager;
    this.recordingService = recordingService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.debugSessionRepository = debugSessionRepository;
    this.finalizationJournal = finalizationJournal;
    this.clock = clock;
  }

  /**
   * Starts the exact preapproved recording, creates the matching session row and persists the
   * STARTED projection before delivery. Identical replay returns the same row without starting a
   * second recording. A failure while persisting the projection compensates by stopping only the
   * exact recording this call started.
   */
  @Transactional
  public RecordingView start(
      String orchestrationId,
      String recordingId,
      String providerSessionId,
      String providerTurnId,
      String decisionDigest,
      String envelopeDigest,
      String provider,
      String modelVersion,
      String accountId,
      String taskName) {
    requireId(orchestrationId, "Orchestration id is required");
    requireId(recordingId, "Recording id is required");
    // Serialize identical dispatches for this orchestration before any read or side effect. The
    // transaction-scoped lock is released on commit, so the request that loses the race observes
    // the winner's durable projection and never starts or stops a recording of its own.
    acquireOrchestrationClaim(orchestrationId);
    Instant now = clock.instant();
    BootstrapRecordingEntity existing =
        entityManager.find(BootstrapRecordingEntity.class, orchestrationId);
    if (existing != null) {
      if (!existing.recordingId.equals(recordingId)
          || !existing.providerSessionId.equals(providerSessionId)
          || !existing.providerTurnId.equals(providerTurnId)
          || !existing.decisionDigest.equals(decisionDigest)
          || !existing.envelopeDigest.equals(envelopeDigest)) {
        throw new BootstrapRecordingException(
            "RECORDING_CONFLICT", "Recording identity was reused with different evidence");
      }
      // Recovery path: the projection already exists, so the recording was already started once.
      // Never start a second process-wide recording here.
      return toView(existing);
    }
    List<BootstrapRecordingEntity> byRecording =
        entityManager
            .createQuery(
                "select r from BootstrapRecordingEntity r where r.recordingId=:recordingId",
                BootstrapRecordingEntity.class)
            .setParameter("recordingId", recordingId)
            .setMaxResults(1)
            .getResultList();
    if (!byRecording.isEmpty() && !byRecording.get(0).orchestrationId.equals(orchestrationId)) {
      throw new BootstrapRecordingException(
          "RECORDING_CONFLICT", "Recording identity was reused with different evidence");
    }
    // SC-1498 finding 6: the client's AccountBuilder run already emits ticks through a debug
    // session it created first, and the recording compiles only when that session carries this
    // exact recording id. Resolve the tick-bearing session before the external side effect so a
    // missing or ambiguous session fails closed with no recording started, and never bind a
    // session that already owns a different recording.
    DebugSession tickSession = resolveTickSession(accountId);
    // SC-1498: the real recording starts before any remote delivery. The preapproved recording id
    // is passed verbatim so the active recording id always matches the approved envelope.
    RecordingStart started =
        recordingService.startRecording(
            recordingId, RecordingTriggerSource.BOOTSTRAP_RECORDING, accountId, taskName);
    try {
      bindTickSession(tickSession, recordingId);
      ensureRecordingSession(
          recordingId, started, providerSessionId, providerTurnId, accountId, taskName);
      BootstrapRecordingEntity entity = new BootstrapRecordingEntity();
      entity.orchestrationId = orchestrationId;
      entity.recordingId = recordingId;
      entity.providerSessionId = providerSessionId;
      entity.providerTurnId = providerTurnId;
      entity.decisionDigest = decisionDigest;
      entity.envelopeDigest = envelopeDigest;
      entity.provider = provider;
      entity.modelVersion = modelVersion;
      entity.manifestState = "STARTED";
      entity.learnedModelActivated = false;
      entity.continuingAuthority = false;
      entity.simulatorTrace = false;
      entity.createdAt = now;
      entityManager.persist(entity);
      entityManager.flush();
      return toView(entity);
    } catch (RuntimeException failure) {
      // Persistence failed after the external recording side effect began. Compensate by stopping
      // only the exact recording this call started; a foreign recording is never touched.
      try {
        recordingService.stopRecordingIfCurrent(recordingId);
      } catch (RuntimeException compensationFailure) {
        failure.addSuppressed(compensationFailure);
      }
      throw failure;
    }
  }

  /**
   * Serializes concurrent identical dispatches on the durable orchestration identity. The advisory
   * lock is transaction-scoped: it exists only for the duration of this invocation's claim, and the
   * projection row it then inserts is the durable owner.
   */
  private void acquireOrchestrationClaim(String orchestrationId) {
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .setParameter("key", ORCHESTRATION_LOCK_PREFIX + orchestrationId)
        .getSingleResult();
  }

  /**
   * Resolves the single active debug session that must own the approved recording: it is the one
   * receiving the run's tick stream and, because it has no recording yet, has not started a
   * competing video. Missing or ambiguous candidates fail closed before any recording starts.
   */
  private DebugSession resolveTickSession(String accountId) {
    List<DebugSession> candidates =
        debugSessionRepository.findByEndTimeIsNullAndRecordingIdIsNullOrderByStartTimeDesc();
    if (accountId != null && !accountId.isBlank()) {
      List<DebugSession> scoped =
          candidates.stream().filter(candidate -> accountId.equals(candidate.getAccountName()))
              .toList();
      if (scoped.size() > 1) {
        throw new BootstrapRecordingException(
            "SESSION_BINDING_AMBIGUOUS",
            "More than one active unrecorded debug session belongs to the run account");
      }
      if (!scoped.isEmpty()) {
        return scoped.get(0);
      }
      throw new BootstrapRecordingException(
          "SESSION_BINDING_MISSING",
          "No active unrecorded debug session exists for the run account");
    }
    if (candidates.isEmpty()) {
      throw new BootstrapRecordingException(
          "SESSION_BINDING_MISSING",
          "No active unrecorded debug session is available for the bootstrap recording");
    }
    if (candidates.size() > 1) {
      throw new BootstrapRecordingException(
          "SESSION_BINDING_AMBIGUOUS",
          "More than one active unrecorded debug session could own the bootstrap recording");
    }
    return candidates.get(0);
  }

  /**
   * Binds the approved recording to the tick-bearing session. A session that already owns a
   * different recording is a conflict, never a silent rebind; a session that already owns this
   * recording is an idempotent replay.
   */
  private void bindTickSession(DebugSession session, String recordingId) {
    DebugSession locked =
        debugSessionRepository
            .findBySessionIdForUpdate(session.getSessionId())
            .orElseThrow(
                () ->
                    new BootstrapRecordingException(
                        "SESSION_BINDING_MISSING",
                        "The tick-bearing debug session disappeared before binding"));
    String existing = locked.getRecordingId();
    if (existing != null && !existing.isBlank()) {
      if (!existing.equals(recordingId)) {
        throw new BootstrapRecordingException(
            "SESSION_BINDING_CONFLICT",
            "The tick-bearing debug session already owns a different recording");
      }
      return;
    }
    locked.setRecordingId(recordingId);
    debugSessionRepository.save(locked);
  }

  private void ensureRecordingSession(
      String recordingId,
      RecordingStart started,
      String providerSessionId,
      String providerTurnId,
      String accountId,
      String taskName) {
    RecordingSession session =
        recordingSessionRepository.findByRecordingId(recordingId).orElse(null);
    if (session != null) {
      return;
    }
    LocalDateTime startTime =
        started == null || started.getStartTime() <= 0
            ? LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
            : LocalDateTime.ofInstant(
                Instant.ofEpochMilli(started.getStartTime()), ZoneId.systemDefault());
    RecordingSession created =
        new RecordingSession(
            recordingId,
            accountId,
            taskName == null ? providerSessionId : taskName,
            startTime,
            RecordingSession.SessionTrigger.BOOTSTRAP_RECORDING,
            started == null ? null : started.getFilePath());
    recordingSessionRepository.save(created);
  }

  /**
   * Stops the exact active recording once and returns its finalized video artifact. Returns an
   * empty result when a different recording (or none) is active: the caller must treat that as
   * reconciliation and never finalize a foreign recording. The matching {@link RecordingSession}
   * row is finalized in the same call.
   */
  @Transactional
  public Optional<RecordingArtifact> stopForFinalization(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      throw new BootstrapRecordingException("INVALID_REQUEST", "A recording id is required");
    }
    // The stop is irreversible: journal and commit the intent first so a crash between the stop
    // and the projection update is recoverable instead of leaving a STARTED row with no end time.
    finalizationJournal.requestFinalization(recordingId);
    RecordingMetadata metadata = recordingService.stopRecordingIfCurrentWithMetadata(recordingId);
    if (metadata == null) {
      // No live recording remains. Accept the already-finalized file only when the durable intent
      // and the durable session evidence exist; otherwise fail closed with a stable code instead
      // of returning an empty artifact that strands the projection.
      Optional<RecordingArtifact> rebuilt = rebuildArtifactForRecovery(recordingId);
      if (rebuilt.isEmpty()) {
        throw new BootstrapRecordingException(
            "RECORDING_STOP_UNRECONCILED",
            "The recording stop cannot be reconciled from durable evidence");
      }
      return rebuilt;
    }
    String artifactDigest = digestFile(Path.of(metadata.getFilePath()));
    Instant endedAt = clock.instant();
    RecordingSession session =
        recordingSessionRepository.findByRecordingId(recordingId).orElse(null);
    if (session != null) {
      session.setEndTime(LocalDateTime.ofInstant(endedAt, ZoneId.systemDefault()));
      session.setVideoPath(metadata.getFilePath());
      session.setDurationMs(metadata.getDuration());
      session.setFrameCount(metadata.getFrameCount());
      session.setFps(metadata.getFps());
      session.setVideoCodec(metadata.getVideoCodec());
      recordingSessionRepository.save(session);
    }
    return Optional.of(
        new RecordingArtifact(
            recordingId,
            metadata.getFilePath(),
            artifactDigest,
            metadata.getDuration(),
            metadata.getFrameCount(),
            metadata.getFps(),
            metadata.getVideoCodec(),
            session == null ? null : session.getId(),
            session == null ? null : session.getStartTime(),
            LocalDateTime.ofInstant(endedAt, ZoneId.systemDefault())));
  }

  private static String digestFile(Path path) {
    if (path == null || !Files.isReadable(path)) {
      throw new BootstrapRecordingException(
          "ARTIFACT_UNREADABLE", "The finalized recording artifact is not readable");
    }
    try (InputStream input = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      return ARTIFACT_DIGEST_PREFIX + HexFormat.of().formatHex(digest.digest());
    } catch (IOException exception) {
      throw new BootstrapRecordingException(
          "ARTIFACT_UNREADABLE", "The finalized recording artifact could not be read");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /**
   * Recovery path for crash injection between recording stop and projection finalization. When the
   * live recording was already stopped (so {@link #stopForFinalization} returns empty) but the
   * projection is still STARTED, the finalized artifact is rebuilt from the durable {@link
   * RecordingSession} row and its video file. Gameplay is never repeated to recover an artifact.
   */
  @Transactional
  public Optional<RecordingArtifact> rebuildArtifactForRecovery(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      throw new BootstrapRecordingException("INVALID_REQUEST", "A recording id is required");
    }
    // Only a run that durably asked to stop may accept an existing file as complete; a crash in
    // the middle of recording must never be recovered as a finished artifact.
    if (!finalizationJournal.finalizationRequested(recordingId)) {
      return Optional.empty();
    }
    RecordingSession session =
        recordingSessionRepository.findByRecordingId(recordingId).orElse(null);
    if (session == null || session.getVideoPath() == null) {
      return Optional.empty();
    }
    Path video = Path.of(session.getVideoPath());
    if (!Files.isReadable(video)) {
      return Optional.empty();
    }
    if (session.getEndTime() == null) {
      // The stop already happened but its metadata was lost. The media file's own last write is
      // durable evidence of when the last frame landed; persist the reconstruction so every later
      // recovery attempt observes the identical end time and duration.
      try {
        LocalDateTime reconstructedEnd =
            LocalDateTime.ofInstant(
                Files.getLastModifiedTime(video).toInstant(), ZoneId.systemDefault());
        if (session.getStartTime() != null && !reconstructedEnd.isAfter(session.getStartTime())) {
          reconstructedEnd = session.getStartTime();
        }
        session.setEndTime(reconstructedEnd);
        if (session.getStartTime() != null) {
          session.setDurationMs(
              Duration.between(session.getStartTime(), reconstructedEnd).toMillis());
        }
        recordingSessionRepository.save(session);
      } catch (IOException exception) {
        throw new BootstrapRecordingException(
            "ARTIFACT_UNREADABLE", "The finalized recording artifact is not readable");
      }
    }
    String artifactDigest = digestFile(video);
    return Optional.of(
        new RecordingArtifact(
            recordingId,
            session.getVideoPath(),
            artifactDigest,
            session.getDurationMs() == null ? 0L : session.getDurationMs(),
            session.getFrameCount() == null ? 0L : session.getFrameCount(),
            session.getFps() == null ? 0 : session.getFps(),
            session.getVideoCodec(),
            session.getId(),
            session.getStartTime(),
            session.getEndTime()));
  }

  /**
   * Finalizes once with the terminal orchestration outcome and manifest digest. Replaying the
   * identical terminal outcome returns the same row; a different outcome is rejected and gameplay
   * is never repeated.
   */
  @Transactional
  public RecordingView finalizeRecording(
      String orchestrationId,
      String terminalOutcome,
      String stableCode,
      String manifestDigest,
      String artifactRef,
      String artifactDigest) {
    return finalizeRecording(
        orchestrationId,
        terminalOutcome,
        stableCode,
        manifestDigest,
        artifactRef,
        artifactDigest,
        clock.instant());
  }

  /**
   * Finalizes with an explicit durable finalization timestamp. The operator passes the same durable
   * instant that was embedded in the published manifest, so the database row and the immutable file
   * agree and recovery replays the identical manifest digest.
   */
  @Transactional
  public RecordingView finalizeRecording(
      String orchestrationId,
      String terminalOutcome,
      String stableCode,
      String manifestDigest,
      String artifactRef,
      String artifactDigest,
      Instant finalizedAt) {
    // The recording row is written by the recording lifecycle as well as by this coordinator, and
    // the request-scoped persistence context may still hold an instance loaded before that write.
    // A locked load of a stale cached instance throws StaleObjectStateException before any
    // mutation, so drop the cached copy first and re-read the durable row under the lock. The
    // immutability check below then runs against the durable state.
    BootstrapRecordingEntity cached =
        entityManager.find(BootstrapRecordingEntity.class, orchestrationId);
    if (cached == null) {
      throw new BootstrapRecordingException(
          "RECORDING_MISSING", "Bootstrap recording was not found");
    }
    entityManager.detach(cached);
    BootstrapRecordingEntity entity =
        entityManager.find(
            BootstrapRecordingEntity.class, orchestrationId, LockModeType.PESSIMISTIC_WRITE);
    if (entity == null) {
      throw new BootstrapRecordingException(
          "RECORDING_MISSING", "Bootstrap recording was not found");
    }
    if ("FINALIZED".equals(entity.manifestState)) {
      if (!java.util.Objects.equals(entity.terminalOutcome, terminalOutcome)
          || !java.util.Objects.equals(entity.stableCode, stableCode)
          || !java.util.Objects.equals(entity.manifestDigest, manifestDigest)) {
        throw new BootstrapRecordingException(
            "RECORDING_IMMUTABLE", "Finalized bootstrap recording cannot be rewritten");
      }
      return toView(entity);
    }
    if (manifestDigest == null || !manifestDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new BootstrapRecordingException("MANIFEST_INVALID", "A manifest digest is required");
    }
    entity.manifestState = "FINALIZED";
    entity.terminalOutcome = terminalOutcome;
    entity.stableCode = stableCode;
    entity.manifestDigest = manifestDigest;
    entity.artifactRef = artifactRef;
    entity.artifactDigest = artifactDigest;
    entity.finalizedAt = finalizedAt == null ? clock.instant() : finalizedAt;
    entityManager.flush();
    return toView(entity);
  }

  @Transactional
  public Optional<RecordingView> find(String orchestrationId) {
    BootstrapRecordingEntity entity =
        entityManager.find(BootstrapRecordingEntity.class, orchestrationId);
    return entity == null ? Optional.empty() : Optional.of(toView(entity));
  }

  @Transactional
  public Optional<RecordingView> findByRecording(String recordingId) {
    List<BootstrapRecordingEntity> values =
        entityManager
            .createQuery(
                "select r from BootstrapRecordingEntity r where r.recordingId=:recordingId",
                BootstrapRecordingEntity.class)
            .setParameter("recordingId", recordingId)
            .setMaxResults(1)
            .getResultList();
    return values.isEmpty() ? Optional.empty() : Optional.of(toView(values.get(0)));
  }

  private RecordingView toView(BootstrapRecordingEntity entity) {
    return new RecordingView(
        entity.orchestrationId,
        entity.recordingId,
        entity.providerSessionId,
        entity.providerTurnId,
        entity.decisionDigest,
        entity.envelopeDigest,
        entity.provider,
        entity.modelVersion,
        entity.manifestState,
        entity.terminalOutcome,
        entity.stableCode,
        entity.manifestDigest,
        entity.artifactRef,
        entity.artifactDigest,
        entity.createdAt,
        entity.finalizedAt);
  }

  private void requireId(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new BootstrapRecordingException("INVALID_REQUEST", message);
    }
  }

  public record RecordingView(
      String orchestrationId,
      String recordingId,
      String providerSessionId,
      String providerTurnId,
      String decisionDigest,
      String envelopeDigest,
      String provider,
      String modelVersion,
      String manifestState,
      String terminalOutcome,
      String stableCode,
      String manifestDigest,
      String artifactRef,
      String artifactDigest,
      Instant createdAt,
      Instant finalizedAt) {}

  /** Finalized video facts used to build the immutable evidence manifest. */
  public record RecordingArtifact(
      String recordingId,
      String videoPath,
      String artifactDigest,
      long durationMs,
      long frameCount,
      int fps,
      String videoCodec,
      Long recordingSessionId,
      LocalDateTime startedAt,
      LocalDateTime finalizedAt) {}

  public static final class BootstrapRecordingException extends RuntimeException {
    private final String failureCode;

    public BootstrapRecordingException(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
