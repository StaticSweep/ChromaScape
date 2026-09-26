package com.chromascape.web.observe;

import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.ViewOnlyFrameSource;
import com.chromascape.utils.core.screen.capture.X11DesktopFrameSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Owns the view-only desktop watch lifecycle.
 *
 * <p>Starting a watch installs an {@link X11DesktopFrameSource} into the process-wide frame runtime
 * without opening any input session: no process is attached, injected, or driven. The runtime
 * refuses the install while an input session owns the frames, and a later input session preempts
 * (and closes) the watch, which then reports {@code EVICTED} until stopped.
 */
@Service
public class ObserveService {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.chromascape.web.debug.tickdebugger.service.ClientFrameMetrics clientFrames;

  private static final Logger logger = LogManager.getLogger(ObserveService.class);

  /** Builds the pixel source for a watch request; seam for focused tests. */
  public interface SourceFactory {
    ViewOnlyFrameSource create(String display, int targetWidth, int framesPerSecond);
  }

  private final SourceFactory sourceFactory;
  private ViewOnlyFrameSource active;
  private Instant startedAt;
  private String display;
  private int framesPerSecond;
  private int targetWidth;

  public ObserveService() {
    this(
        (display, targetWidth, framesPerSecond) ->
            new X11DesktopFrameSource("ffmpeg", display, targetWidth, framesPerSecond));
  }

  ObserveService(SourceFactory sourceFactory) {
    this.sourceFactory = sourceFactory;
  }

  /**
   * Starts watching the local desktop.
   *
   * @param display X display, blank for the process default
   * @param targetWidth downscaled capture width, zero for the default
   * @param framesPerSecond observation rate, zero for the default
   * @return status snapshot of the running watch
   */
  public synchronized Map<String, Object> start(
      String display, int targetWidth, int framesPerSecond) {
    if (active != null && TargetFrameRuntime.isActiveSource(active)) {
      throw new ObserveException(
          ObserveException.ALREADY_ACTIVE, "A desktop watch is already running");
    }
    if (active != null) {
      // A preempted watch lingers as EVICTED until stopped; starting again replaces it.
      closeQuietly(active);
      active = null;
    }
    String resolvedDisplay = resolveDisplay(display);
    int resolvedWidth = targetWidth > 0 ? targetWidth : X11DesktopFrameSource.DEFAULT_TARGET_WIDTH;
    int resolvedFps = framesPerSecond > 0 ? framesPerSecond : X11DesktopFrameSource.DEFAULT_FPS;
    ViewOnlyFrameSource source;
    try {
      source = sourceFactory.create(resolvedDisplay, resolvedWidth, resolvedFps);
    } catch (IllegalStateException failure) {
      throw mapStartFailure(failure);
    }
    try {
      TargetFrameRuntime.installViewOnly(source);
    } catch (IllegalStateException failure) {
      closeQuietly(source);
      throw new ObserveException(
          ObserveException.CONFLICT,
          "An input session already owns the frames; observation refuses to steal it");
    }
    active = source;
    startedAt = Instant.now();
    this.display = resolvedDisplay;
    this.targetWidth = resolvedWidth;
    this.framesPerSecond = resolvedFps;
    logger.info("Desktop watch started on display {}", resolvedDisplay);
    return status();
  }

  /** Stops the running watch and releases the capture pipeline. */
  public synchronized Map<String, Object> stop() {
    if (active == null) {
      throw new ObserveException(ObserveException.NOT_ACTIVE, "No desktop watch is running");
    }
    TargetFrameRuntime.uninstall(active);
    closeQuietly(active);
    active = null;
    startedAt = null;
    logger.info("Desktop watch stopped");
    return status();
  }

  /** Returns the current watch state for the debugger UI. */
  public synchronized Map<String, Object> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    if (clientFrames != null)
      body.put(
          "clientFrames",
          Map.of(
              "state",
              clientFrames.getFeedState(),
              "source",
              clientFrames.getLastSource(),
              "ageMillis",
              clientFrames.getLastForwardAgeMillis(),
              "received",
              clientFrames.getReceived(),
              "forwarded",
              clientFrames.getForwarded(),
              "dropped",
              clientFrames.getDroppedInvalid(),
              "lastRejection",
              java.util.Objects.toString(clientFrames.getLastRejection(), "")));

    if (active == null) {
      body.put("state", "IDLE");
      return body;
    }
    if (!TargetFrameRuntime.isActiveSource(active)) {
      body.put("state", "EVICTED");
      body.put("message", "An input session preempted the watch; stop it to clear this state");
      return body;
    }
    body.put("state", "ACTIVE");
    body.put("display", display);
    body.put("targetWidth", targetWidth);
    body.put("framesPerSecond", framesPerSecond);
    body.put("startedAt", startedAt == null ? null : startedAt.toString());
    if (active instanceof X11DesktopFrameSource desktop) {
      body.put("deliveredFrames", desktop.getDeliveredCount());
      body.put("failure", desktop.getFailure());
    }
    return body;
  }

  private static String resolveDisplay(String display) {
    if (display != null && !display.isBlank()) {
      return display;
    }
    String environment = System.getenv("DISPLAY");
    if (environment != null && !environment.isBlank()) {
      return environment;
    }
    return X11DesktopFrameSource.DEFAULT_DISPLAY;
  }

  private static ObserveException mapStartFailure(IllegalStateException failure) {
    String message = failure.getMessage() == null ? "" : failure.getMessage();
    if (message.contains("ffmpeg")) {
      return new ObserveException(
          ObserveException.FFMPEG_MISSING,
          "ffmpeg is not available for desktop capture: " + message,
          failure);
    }
    return new ObserveException(
        ObserveException.START_FAILED, "Could not start desktop capture: " + message, failure);
  }

  private static void closeQuietly(ViewOnlyFrameSource source) {
    try {
      source.close();
    } catch (RuntimeException failure) {
      logger.warn("View-only frame source threw during close", failure);
    }
  }
}
