package com.chromascape.web.debug.tickdebugger.video.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Bounded, credential-free counters for external-video intake, queueing, cancellation, cleanup and
 * refusals. No media, private path or caller metadata is recorded.
 */
@Component
public class MouseVideoTelemetry {

  private final AtomicLong intakeAccepted = new AtomicLong();
  private final AtomicLong intakeReused = new AtomicLong();
  private final AtomicLong intakeRefused = new AtomicLong();
  private final AtomicLong jobsClaimed = new AtomicLong();
  private final AtomicLong jobsSucceeded = new AtomicLong();
  private final AtomicLong jobsFailed = new AtomicLong();
  private final AtomicLong cancellationsRequested = new AtomicLong();
  private final AtomicLong cancellationsConfirmed = new AtomicLong();
  private final AtomicLong reconciliationsRequired = new AtomicLong();
  private final AtomicLong mediaRefused = new AtomicLong();
  private final AtomicLong exportsCreated = new AtomicLong();

  public void intakeAccepted() {
    intakeAccepted.incrementAndGet();
  }

  public void intakeReused() {
    intakeReused.incrementAndGet();
  }

  public void intakeRefused() {
    intakeRefused.incrementAndGet();
  }

  public void jobClaimed() {
    jobsClaimed.incrementAndGet();
  }

  public void jobSucceeded() {
    jobsSucceeded.incrementAndGet();
  }

  public void jobFailed() {
    jobsFailed.incrementAndGet();
  }

  public void cancellationRequested() {
    cancellationsRequested.incrementAndGet();
  }

  public void cancellationConfirmed() {
    cancellationsConfirmed.incrementAndGet();
  }

  public void reconciliationRequired() {
    reconciliationsRequired.incrementAndGet();
  }

  public void mediaRefused() {
    mediaRefused.incrementAndGet();
  }

  public void exportCreated() {
    exportsCreated.incrementAndGet();
  }

  public Map<String, Object> snapshot() {
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("intakeAccepted", intakeAccepted.get());
    counts.put("intakeReused", intakeReused.get());
    counts.put("intakeRefused", intakeRefused.get());
    counts.put("jobsClaimed", jobsClaimed.get());
    counts.put("jobsSucceeded", jobsSucceeded.get());
    counts.put("jobsFailed", jobsFailed.get());
    counts.put("cancellationsRequested", cancellationsRequested.get());
    counts.put("cancellationsConfirmed", cancellationsConfirmed.get());
    counts.put("reconciliationsRequired", reconciliationsRequired.get());
    counts.put("mediaRefused", mediaRefused.get());
    counts.put("exportsCreated", exportsCreated.get());
    return counts;
  }
}
