package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoJobServiceTest {

  @Mock MouseVideoJobRepository jobs;
  @Mock MouseVideoJobEventRepository events;
  @Mock MouseVideoFramePreviewRepository previews;
  @Mock EntityManager entityManager;

  private MouseVideoJobService service;

  @BeforeEach
  void setUp() {
    service = new MouseVideoJobService(jobs, events, previews, new ObjectMapper());
    ReflectionTestUtils.setField(service, "entityManager", entityManager);
    when(jobs.save(any(MouseVideoJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(jobs.saveAndFlush(any(MouseVideoJob.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static MouseVideoJob runningJob() {
    MouseVideoJob job =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "digest");
    job.setStatus(MouseVideoJob.STATUS_RUNNING);
    ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
    return job;
  }

  @Test
  void createsNewQueuedJob() {
    when(jobs.findByOwnerAndRequestId("owner", "req")).thenReturn(Optional.empty());
    MouseVideoJob job =
        service.create(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "digest");
    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_QUEUED);
    assertThat(job.getGeneration()).isEqualTo(1L);
  }

  @Test
  void reusesExistingJobForIdenticalDigest() {
    MouseVideoJob existing =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "digest");
    when(jobs.findByOwnerAndRequestId("owner", "req")).thenReturn(Optional.of(existing));
    assertThat(
            service.create(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "digest"))
        .isSameAs(existing);
  }

  @Test
  void rejectsConflictingBodyReuse() {
    MouseVideoJob existing =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "digest");
    when(jobs.findByOwnerAndRequestId("owner", "req")).thenReturn(Optional.of(existing));
    assertThatThrownBy(
            () ->
                service.create(
                    UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "other"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void claimAcquiresQueuedJobExactlyOnce() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "req", "digest");
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    MouseVideoJobService.ClaimResult first = service.claim(id, "worker", 30L);
    assertThat(first.acquired()).isTrue();
    assertThat(first.job().getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(first.job().getGeneration()).isGreaterThan(1L);

    // A second claim on the now-RUNNING, unexpired job is refused without stealing the lease.
    when(jobs.leaseValid(id)).thenReturn(true);
    MouseVideoJobService.ClaimResult second = service.claim(id, "other-worker", 30L);
    assertThat(second.acquired()).isFalse();
  }

  @Test
  void cancellationFencesPublicationAndRequiresConfirmedCleanup() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    when(jobs.findById(id)).thenReturn(Optional.of(job));

    long generationBefore = job.getGeneration();
    MouseVideoJob cancelled = service.requestCancellation(id, "owner", 0L);
    assertThat(cancelled.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
    assertThat(cancelled.getGeneration()).isGreaterThan(generationBefore);
    assertThat(cancelled.getLeaseOwner()).isEqualTo("worker");
    assertThat(service.mayPublish(id, generationBefore)).isFalse();

    MouseVideoJob confirmed = service.confirmCancelled(id, "owner");
    assertThat(confirmed.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLED);
    assertThat(confirmed.getLeaseOwner()).isNull();
  }

  @Test
  void cancellingAQueuedPreviewJobSettlesItsBoundPreviewAsCancelled() {
    MouseVideoJobService withPreviews =
        new MouseVideoJobService(jobs, events, previews, new ObjectMapper());
    ReflectionTestUtils.setField(withPreviews, "entityManager", entityManager);
    UUID sourceId = UUID.randomUUID();
    UUID previewId = UUID.randomUUID();
    MouseVideoJob job =
        new MouseVideoJob(
            sourceId,
            "owner",
            MouseVideoJob.KIND_PREVIEW,
            "req-preview",
            "digest",
            UUID.randomUUID(),
            previewId,
            "preview:" + previewId);
    UUID jobId = UUID.randomUUID();
    ReflectionTestUtils.setField(job, "id", jobId);
    MouseVideoFramePreview preview =
        new MouseVideoFramePreview(
            "owner",
            sourceId,
            job.getAnalysisId(),
            "req-preview",
            "digest",
            "sha256:" + "i".repeat(64),
            0L,
            "0",
            "0.0",
            "1",
            "10",
            MouseVideoFramePreview.METHOD_FRAME_PNG,
            640,
            360);
    ReflectionTestUtils.setField(preview, "id", previewId);
    preview.setJobId(jobId);
    when(jobs.findForUpdate(jobId)).thenReturn(Optional.of(job));
    when(previews.findForUpdate(previewId)).thenReturn(Optional.of(preview));

    MouseVideoJob cancelled = withPreviews.requestCancellation(jobId, "owner", 0L);

    assertThat(cancelled.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLED);
    assertThat(preview.getState()).isEqualTo(MouseVideoFramePreview.STATE_CANCELLED);
  }

  @Test
  void rejectsStaleCancellationRevision() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    assertThatThrownBy(() -> service.requestCancellation(id, "owner", 5L))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void reconciliationPreservesQueuedAndFencesExpiredRunning() {
    UUID queuedId = UUID.randomUUID();
    UUID runningId = UUID.randomUUID();
    MouseVideoJob queued =
        new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_IMPORT, "q", "d");
    ReflectionTestUtils.setField(queued, "id", queuedId);
    MouseVideoJob running = runningJob();
    ReflectionTestUtils.setField(running, "id", runningId);
    when(jobs.findIdsByStatusIn(any())).thenReturn(List.of(queuedId, runningId));
    when(jobs.findForUpdate(queuedId)).thenReturn(Optional.of(queued));
    when(jobs.findForUpdate(runningId)).thenReturn(Optional.of(running));
    when(jobs.leaseValid(runningId)).thenReturn(false);

    int reconciled = service.reconcileOnStartup();

    assertThat(reconciled).isEqualTo(2);
    assertThat(queued.getStatus()).isEqualTo(MouseVideoJob.STATUS_QUEUED);
    assertThat(running.getStatus()).isEqualTo(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    assertThat(running.getGeneration()).isGreaterThan(1L);
  }

  @Test
  void startupLeavesAnotherInstancesCancellationInProgressUntouched() {
    UUID runningId = UUID.randomUUID();
    MouseVideoJob cancelling = runningJob();
    ReflectionTestUtils.setField(cancelling, "id", runningId);
    cancelling.setStatus(MouseVideoJob.STATUS_CANCELLING);
    cancelling.setLeaseOwner("other-instance");
    when(jobs.findIdsByStatusIn(any())).thenReturn(List.of(runningId));
    when(jobs.findForUpdate(runningId)).thenReturn(Optional.of(cancelling));
    when(jobs.leaseValid(runningId)).thenReturn(true);

    int reconciled = service.reconcileOnStartup();

    assertThat(reconciled).isZero();
    assertThat(cancelling.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
    assertThat(cancelling.getLeaseOwner()).isEqualTo("other-instance");
  }

  @Test
  void startupLeavesAnotherInstancesLiveLeaseUntouched() {
    UUID runningId = UUID.randomUUID();
    MouseVideoJob running = runningJob();
    ReflectionTestUtils.setField(running, "id", runningId);
    running.setLeaseOwner("other-instance");
    long generation = running.getGeneration();
    when(jobs.findIdsByStatusIn(any())).thenReturn(List.of(runningId));
    when(jobs.findForUpdate(runningId)).thenReturn(Optional.of(running));
    when(jobs.leaseValid(runningId)).thenReturn(true);

    int reconciled = service.reconcileOnStartup();

    assertThat(reconciled).isZero();
    assertThat(running.getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(running.getGeneration()).isEqualTo(generation);
    assertThat(running.getLeaseOwner()).isEqualTo("other-instance");
  }

  @Test
  void expiredClaimIsFencedForReconciliationInsteadOfReclaimed() {
    UUID runningId = UUID.randomUUID();
    MouseVideoJob running = runningJob();
    ReflectionTestUtils.setField(running, "id", runningId);
    when(jobs.findIdsByStatusIn(any())).thenReturn(List.of(runningId));
    when(jobs.findForUpdate(runningId)).thenReturn(Optional.of(running));
    when(jobs.leaseValid(runningId)).thenReturn(false);

    int reconciled = service.reconcileExpiredClaims();

    assertThat(reconciled).isEqualTo(1);
    assertThat(running.getStatus()).isEqualTo(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    assertThat(running.getErrorCode()).isEqualTo(MouseVideoException.CODE_INTERRUPTED);
  }

  @Test
  void ownerReconciliationSettlesAFencedJobAsFailedWithTheProof() {
    MouseVideoJob running = runningJob();
    UUID jobId = running.getId();
    running.reconciliationRequired(MouseVideoException.CODE_INTERRUPTED, "lease expired");
    when(jobs.findForUpdate(jobId)).thenReturn(Optional.of(running));

    MouseVideoJob settled =
        service.resolveReconciliation(jobId, "owner", java.util.Map.of("activeScopes", 0));

    assertThat(settled.getStatus()).isEqualTo(MouseVideoJob.STATUS_FAILED);
    assertThat(settled.getErrorCode()).isEqualTo(MouseVideoException.CODE_INTERRUPTED);
    assertThat(settled.isTerminal()).isTrue();
    verify(events)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                event -> "RECONCILED".equals(event.getEventType())));
  }

  @Test
  void ownerReconciliationRefusesAJobThatDoesNotNeedIt() {
    MouseVideoJob running = runningJob();
    when(jobs.findForUpdate(running.getId())).thenReturn(Optional.of(running));

    assertThatThrownBy(
            () -> service.resolveReconciliation(running.getId(), "owner", java.util.Map.of()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
    assertThat(running.getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
  }

  @Test
  void ownerReconciliationIsOwnerScoped() {
    MouseVideoJob running = runningJob();
    running.reconciliationRequired(MouseVideoException.CODE_INTERRUPTED, "lease expired");
    when(jobs.findForUpdate(running.getId())).thenReturn(Optional.of(running));

    assertThatThrownBy(
            () -> service.resolveReconciliation(running.getId(), "intruder", java.util.Map.of()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_NOT_FOUND);
    assertThat(running.getStatus()).isEqualTo(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
  }

  @Test
  void cancellingJobWithClearedLeaseIsNotFencedByTheExpiryPoller() {
    UUID runningId = UUID.randomUUID();
    MouseVideoJob cancelling = runningJob();
    ReflectionTestUtils.setField(cancelling, "id", runningId);
    cancelling.setStatus(MouseVideoJob.STATUS_CANCELLING);
    when(jobs.findIdsByStatusIn(any())).thenReturn(List.of(runningId));
    when(jobs.findForUpdate(runningId)).thenReturn(Optional.of(cancelling));

    int reconciled = service.reconcileExpiredClaims();

    assertThat(reconciled).isZero();
    assertThat(cancelling.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
  }

  @Test
  void cancellationSettlementRefreshesAStaleManagedJobBeforeWriting() {
    MouseVideoJob job = runningJob();
    job.setStatus(MouseVideoJob.STATUS_CANCELLING);
    UUID id = job.getId();
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    when(entityManager.contains(job)).thenReturn(true);

    MouseVideoJob settled = service.confirmCancelled(id, "owner");

    verify(entityManager).refresh(job);
    assertThat(settled.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLED);
  }

  @Test
  void timeoutFailureSettlesAsTimedOut() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    MouseVideoJob settled =
        service.fail(
            id, job.getGeneration(), MouseVideoException.CODE_WORKER_TIMEOUT, "slow", true);
    assertThat(settled.getStatus()).isEqualTo(MouseVideoJob.STATUS_TIMED_OUT);
    assertThat(settled.getErrorCode()).isEqualTo(MouseVideoException.CODE_WORKER_TIMEOUT);
  }

  @Test
  void staleExecutionCannotReconcileAReplacementGeneration() {
    MouseVideoJob replacement = runningJob();
    replacement.setLeaseOwner("worker");
    long staleGeneration = replacement.getGeneration();
    replacement.bumpGeneration();
    replacement.setLeaseOwner("worker");
    UUID id = replacement.getId();
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(replacement));

    assertThatThrownBy(() -> service.reconcileExecution(id, staleGeneration, 0L, "worker", "lost"))
        .isInstanceOf(MouseVideoException.class);

    assertThat(replacement.getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(replacement.getLeaseOwner()).isEqualTo("worker");
    assertThat(replacement.getCleanupReason()).isNull();
    verify(jobs, never()).save(any());
    verify(events, never()).save(any());
  }

  @Test
  void foreignLeaseOwnerCannotReconcileTheSameGeneration() {
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("replacement");
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));

    assertThatThrownBy(
            () ->
                service.reconcileExecution(
                    job.getId(), job.getGeneration(), 0L, "old-worker", "lost"))
        .isInstanceOf(MouseVideoException.class);

    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(job.getLeaseOwner()).isEqualTo("replacement");
    verify(events, never()).save(any());
  }

  @Test
  void owningExecutionCanReconcileUncertainCleanup() {
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));

    service.reconcileExecution(job.getId(), job.getGeneration(), 0L, "worker", "uncertain");

    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    assertThat(job.getCleanupReason()).isEqualTo("uncertain");
    verify(events).save(any());
  }

  @Test
  void cancellationRevisionAdvancesStillBelongToTheOriginalExecution() {
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    long generation = job.getGeneration();
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));
    service.requestCancellation(job.getId(), "owner", 0L);
    service.requestCancellation(job.getId(), "owner", 1L);

    service.reconcileExecution(job.getId(), generation, 0L, "worker", "uncertain cancellation");

    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    assertThat(job.getCancellationRevision()).isEqualTo(2L);
  }

  @Test
  void cancellationOfAReplacementDoesNotRestoreStaleReconciliationRights() {
    MouseVideoJob job = runningJob();
    long staleGeneration = job.getGeneration();
    job.bumpGeneration();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));
    service.requestCancellation(job.getId(), "owner", 0L);

    assertThatThrownBy(
            () ->
                service.reconcileExecution(
                    job.getId(), staleGeneration, 0L, "worker", "stale cleanup"))
        .isInstanceOf(MouseVideoException.class);

    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
    assertThat(job.getLeaseOwner()).isEqualTo("worker");
  }

  @Test
  void executionCanConfirmItsOwnCancellationAfterRevisionAdvances() {
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    long generation = job.getGeneration();
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));
    service.requestCancellation(job.getId(), "owner", 0L);

    service.confirmExecutionCancelled(job.getId(), generation, 0L, "worker");

    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLED);
    assertThat(job.getLeaseOwner()).isNull();
  }

  @Test
  void staleExecutionCannotConfirmCancellationOfAReplacement() {
    MouseVideoJob job = runningJob();
    long staleGeneration = job.getGeneration();
    job.bumpGeneration();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(job.getId())).thenReturn(Optional.of(job));
    service.requestCancellation(job.getId(), "owner", 0L);

    assertThatThrownBy(
            () -> service.confirmExecutionCancelled(job.getId(), staleGeneration, 0L, "worker"))
        .isInstanceOf(MouseVideoException.class);

    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
    assertThat(job.getLeaseOwner()).isEqualTo("worker");
  }

  @Test
  void publicationFenceRejectsGenerationChange() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    assertThatThrownBy(() -> service.progress(id, job.getGeneration() + 1L, "PROBE", 10))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void viewSanitizesLegacyStoredFailureDiagnostics() {
    MouseVideoJob job = runningJob();
    job.fail(
        MouseVideoException.CODE_DECODER_FAILED,
        "Traceback: /tmp/sc2150-media/bytes/private-file secret=abc");

    MouseVideoJobService.JobView view = service.view(job);

    assertThat(view.errorCode()).isEqualTo(MouseVideoException.CODE_DECODER_FAILED);
    assertThat(view.errorMessage()).contains("reference " + job.getId());
    assertThat(view.errorMessage()).doesNotContain("/tmp").doesNotContain("private-file");
    assertThat(view.cleanupReason()).isNull();
  }

  @Test
  void viewSanitizesFailedJobWithoutAnErrorCode() {
    MouseVideoJob job = runningJob();
    String privateMessage = "Traceback: /tmp/sc2150/private-file secret=abc";
    job.fail(null, privateMessage);

    MouseVideoJobService.JobView view = service.view(job);

    assertThat(view.status()).isEqualTo(MouseVideoJob.STATUS_FAILED);
    assertThat(view.errorCode()).isNull();
    assertThat(view.errorMessage())
        .startsWith("The media could not be decoded or probed")
        .contains("reference " + job.getId())
        .doesNotContain("/tmp", "Traceback", "secret")
        .hasSizeLessThanOrEqualTo(320);
    assertThat(view.cleanupReason()).isNull();
    assertThat(job.getErrorMessage()).isEqualTo(privateMessage);
  }

  @Test
  void viewSanitizesTimedOutJobWithoutAnErrorCode() {
    MouseVideoJob job = runningJob();
    String privateMessage = "Timeout: C:\\private\\probe.py secret=abc";
    job.timeout(null, privateMessage);

    MouseVideoJobService.JobView view = service.view(job);

    assertThat(view.status()).isEqualTo(MouseVideoJob.STATUS_TIMED_OUT);
    assertThat(view.errorCode()).isNull();
    assertThat(view.errorMessage())
        .startsWith("The media could not be decoded or probed")
        .contains("reference " + job.getId())
        .doesNotContain("C:", "probe.py", "secret")
        .hasSizeLessThanOrEqualTo(320);
    assertThat(view.cleanupReason()).isNull();
    assertThat(job.getErrorMessage()).isEqualTo(privateMessage);
  }

  @Test
  void viewSanitizesLegacyStoredReconciliationDiagnostics() {
    MouseVideoJob job = runningJob();
    job.reconciliationRequired(
        MouseVideoException.CODE_CLEANUP_UNCONFIRMED, "cleanup left /tmp/sc2150/private");
    job.setCleanupReason("cleanup left /tmp/sc2150/private");

    MouseVideoJobService.JobView view = service.view(job);

    assertThat(view.errorMessage()).contains("reference " + job.getId());
    assertThat(view.errorMessage()).doesNotContain("/tmp").doesNotContain("private");
    assertThat(view.cleanupReason()).contains("reference " + job.getId());
    assertThat(view.cleanupReason()).doesNotContain("/tmp").doesNotContain("private");
  }

  @Test
  void gracefulShutdownRequeuesTheExactExecutionAndFencesIt() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    when(jobs.findById(id)).thenReturn(Optional.of(job));
    long generation = job.getGeneration();
    int attempt = job.getAttempt();

    MouseVideoJob requeued =
        service.requeueAfterShutdown(id, generation, job.getCancellationRevision(), "worker");

    assertThat(requeued.getStatus()).isEqualTo(MouseVideoJob.STATUS_QUEUED);
    assertThat(requeued.getGeneration()).isGreaterThan(generation);
    assertThat(requeued.getAttempt()).isEqualTo(attempt + 1);
    assertThat(requeued.getLeaseOwner()).isNull();
    assertThat(requeued.getLeaseExpiresAt()).isNull();
    assertThat(service.mayPublish(id, generation)).isFalse();
    verify(events)
        .save(org.mockito.ArgumentMatchers.argThat(e -> "REQUEUED".equals(e.getEventType())));
  }

  @Test
  void shutdownRequeueRefusesAStaleOrForeignExecution() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    long generation = job.getGeneration();

    assertThatThrownBy(
            () ->
                service.requeueAfterShutdown(
                    id, generation - 1, job.getCancellationRevision(), "worker"))
        .isInstanceOf(MouseVideoException.class);
    assertThatThrownBy(
            () ->
                service.requeueAfterShutdown(
                    id, generation, job.getCancellationRevision(), "other"))
        .isInstanceOf(MouseVideoException.class);
    assertThat(job.getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
  }

  @Test
  void userCancellationInProgressWinsOverShutdownRequeue() {
    UUID id = UUID.randomUUID();
    MouseVideoJob job = runningJob();
    job.setLeaseOwner("worker");
    when(jobs.findForUpdate(id)).thenReturn(Optional.of(job));
    when(jobs.findById(id)).thenReturn(Optional.of(job));
    long generation = job.getGeneration();
    long revision = job.getCancellationRevision();
    service.requestCancellation(id, "owner", 0L);

    MouseVideoJob settled = service.requeueAfterShutdown(id, generation, revision, "worker");

    assertThat(settled.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLED);
  }

  @Test
  void coverageReportsQueueDepthAndTheDatabaseClockAge() {
    when(jobs.countByStatus(MouseVideoJob.STATUS_QUEUED)).thenReturn(2L);
    when(jobs.countByStatus(MouseVideoJob.STATUS_RUNNING)).thenReturn(1L);
    when(jobs.countByStatus(MouseVideoJob.STATUS_CANCELLING)).thenReturn(0L);
    when(jobs.countByStatus(MouseVideoJob.STATUS_NEEDS_RECONCILIATION)).thenReturn(3L);
    when(jobs.oldestAgeSecondsByStatus(MouseVideoJob.STATUS_QUEUED)).thenReturn(12.6d);

    java.util.Map<String, Object> coverage = service.coverage();

    assertThat(coverage)
        .containsEntry("queued", 2L)
        .containsEntry("running", 1L)
        .containsEntry("cancelling", 0L)
        .containsEntry("needsReconciliation", 3L)
        .containsEntry("oldestQueuedSeconds", 13L);
  }

  @Test
  void coverageNeverReportsANegativeQueueAge() {
    when(jobs.oldestAgeSecondsByStatus(MouseVideoJob.STATUS_QUEUED)).thenReturn(-4.0d);

    assertThat(service.coverage()).containsEntry("oldestQueuedSeconds", 0L);
  }
}
