package com.chromascape.web.debug.tickdebugger.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Counters for client-pushed screen frames.
 *
 * <p>Frames arrive over the tick-stream connection and are forwarded straight into the live
 * viewport pipeline: they are never persisted and never acknowledged. Every rejection carries a
 * stable code for the debugger UI.
 */
@Component
public class ClientFrameMetrics {

  private volatile boolean activeSession;

  public void sessionStarted(String id) {
    activeSession = true;
    lastSessionId.set(id);
    lastDecision.set("WAITING");
  }

  public void sessionEnded(String id) {
    if (id.equals(lastSessionId.get())) activeSession = false;
  }

  private volatile boolean enabled = true;

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  private final AtomicLong received = new AtomicLong(0);
  private final AtomicLong forwarded = new AtomicLong(0);
  private final AtomicLong droppedAttached = new AtomicLong(0);
  private final AtomicLong droppedDesktopWatch = new AtomicLong(0);
  private final AtomicLong droppedInvalid = new AtomicLong(0);
  private final AtomicReference<String> lastRejection = new AtomicReference<>(null);
  private final AtomicLong lastForwardedAtMillis = new AtomicLong(0);
  private final AtomicReference<String> lastSessionId = new AtomicReference<>(null);
  private final AtomicReference<String> lastSource = new AtomicReference<>("none");
  private final AtomicLong lastDecisionAtMillis = new AtomicLong(0);
  private final AtomicReference<String> lastDecision = new AtomicReference<>(null);

  public void countReceived() {
    received.incrementAndGet();
  }

  public void countNoSubscribers() {
    lastDecisionAtMillis.set(System.currentTimeMillis());
    lastDecision.set("NO_SUBSCRIBERS");
    lastSource.set("client");
  }

  public void countForwarded(int width, int height) {
    forwarded.incrementAndGet();
    lastForwardedAtMillis.set(System.currentTimeMillis());
    lastDecisionAtMillis.set(System.currentTimeMillis());
    lastDecision.set("FORWARDED");
    lastSource.set("client");
  }

  /** Records which session the latest forwarded frame belongs to. Never logs pixels. */
  public void noteForwardedSession(String sessionId) {
    lastSessionId.set(sessionId);
    lastDecisionAtMillis.set(System.currentTimeMillis());
    lastDecision.set("FORWARDED");
  }

  public void countDroppedAttached() {
    droppedAttached.incrementAndGet();
    lastRejection.set("ATTACHED_SOURCE_WINS");
    lastDecisionAtMillis.set(System.currentTimeMillis());
    lastDecision.set("REJECTED");
    lastSource.set("attached");
  }

  public void countDroppedDesktopWatch() {
    droppedDesktopWatch.incrementAndGet();
    lastRejection.set("DESKTOP_WATCH_WINS");
    lastDecisionAtMillis.set(System.currentTimeMillis());
    lastDecision.set("REJECTED");
    lastSource.set("desktop");
  }

  public void countDroppedInvalid(String code) {
    droppedInvalid.incrementAndGet();
    lastRejection.set(code);
    lastDecisionAtMillis.set(System.currentTimeMillis());
    lastDecision.set("REJECTED");
  }

  /** Current feed state; receiving without viewers is distinct from a silent sender. */
  public String getFeedState() {
    if (!enabled) return "DISABLED";
    if (!activeSession || "WAITING".equals(lastDecision.get()) || received.get() == 0) {
      return "WAITING_FOR_SESSION";
    }
    if ("NO_SUBSCRIBERS".equals(lastDecision.get())) {
      return System.currentTimeMillis() - lastDecisionAtMillis.get() <= 5_000
          ? "IDLE_NO_SUBSCRIBERS"
          : "STALE";
    }
    if ("FORWARDED".equals(lastDecision.get())) {
      return System.currentTimeMillis() - lastForwardedAtMillis.get() <= 5_000 ? "ACTIVE" : "STALE";
    }
    return "REJECTED";
  }

  /** Milliseconds since the last forwarded frame, or -1 when nothing was ever forwarded. */
  public long getLastForwardAgeMillis() {
    if (forwarded.get() == 0) {
      return -1;
    }
    return Math.max(0, System.currentTimeMillis() - lastForwardedAtMillis.get());
  }

  public long getReceived() {
    return received.get();
  }

  public long getForwarded() {
    return forwarded.get();
  }

  public long getDroppedAttached() {
    return droppedAttached.get();
  }

  public long getDroppedDesktopWatch() {
    return droppedDesktopWatch.get();
  }

  public String getLastSessionId() {
    return lastSessionId.get();
  }

  public String getLastSource() {
    return lastSource.get();
  }

  public long getLastDecisionAtMillis() {
    return lastDecisionAtMillis.get();
  }

  public long getDroppedInvalid() {
    return droppedInvalid.get();
  }

  public String getLastRejection() {
    return lastRejection.get();
  }

  public long getLastForwardedAtMillis() {
    return lastForwardedAtMillis.get();
  }
}
