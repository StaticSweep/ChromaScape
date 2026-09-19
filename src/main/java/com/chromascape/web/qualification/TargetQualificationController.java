package com.chromascape.web.qualification;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.input.TargetInputSessionFactory;
import com.chromascape.utils.core.input.remoteinput.MouseButton;
import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only qualification harness for one explicitly selected target session.
 *
 * <p>The harness is <b>absent</b> from the application context unless <em>both</em> of the
 * following hold, and Spring enforces each one rather than leaving it to operator convention:
 *
 * <ul>
 *   <li>the {@value #REQUIRED_PROFILE} Spring profile is active ({@link Profile}) — the same
 *       development-only profile the stabilization and replay security filters gate their loopback
 *       fallbacks on, so a Fedora development host already runs under it; and
 *   <li>{@code chromascape.qualification.enabled=true} is set explicitly ({@link
 *       ConditionalOnProperty} with {@code matchIfMissing=false}).
 * </ul>
 *
 * <p>With no active profile, with a production or test profile, or with the property missing or
 * {@code false}, the bean is never created and {@code /api/qualification/**} does not exist. It is
 * therefore never part of the production API surface. It exists so the Fedora live qualification
 * can open a target-owned session, capture rendered frames, issue a small set of bounded
 * client-relative mouse/keyboard operations, and verify release without exposing arbitrary native
 * input. Every mutation runs under a single lock and delegates to the same lifecycle-fenced {@link
 * TargetInputSession} the application uses, so bounds, selection fencing, and held-input tracking
 * are unchanged.
 *
 * <p>The harness additionally refuses any caller that is not loopback, so enabling it on a Fedora
 * host cannot expose input over a shared interface.
 *
 * <p>Application shutdown closes any open harness session and releases held input through {@link
 * #shutdown()}; a normal stop never depends on the operator calling {@code /session/close}.
 */
@RestController
@RequestMapping("/api/qualification")
@Profile(TargetQualificationController.REQUIRED_PROFILE)
@ConditionalOnProperty(
    name = "chromascape.qualification.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class TargetQualificationController {

  /** The only Spring profile under which the harness bean may exist. */
  public static final String REQUIRED_PROFILE = "local-development";

  private static final Logger logger = LogManager.getLogger(TargetQualificationController.class);
  private static final int DEFAULT_KEY_HOLD_MILLIS = 60;

  private final TargetInputSessionFactory sessionFactory;
  private final WindowSelectionService selection;
  private final Path captureDirectory;
  private final ReentrantLock lock = new ReentrantLock();

  private TargetInputSession session;
  private TargetFrameSource frameSource;

  /**
   * Creates the harness.
   *
   * @param sessionFactory the lifecycle-owned session factory
   * @param selection the shared selection service used for identity reporting
   * @param captureDirectory directory for captured PNG evidence
   */
  @Autowired
  public TargetQualificationController(
      TargetInputSessionFactory sessionFactory,
      WindowSelectionService selection,
      @Value("${chromascape.qualification.capture-directory:.chromascape/qualification/captures}")
          String captureDirectory) {
    this.sessionFactory = sessionFactory;
    this.selection = selection;
    this.captureDirectory = Path.of(captureDirectory).toAbsolutePath().normalize();
  }

  /** Opens one fenced session for the current explicit selection. */
  @PostMapping("/session/open")
  public ResponseEntity<Map<String, Object>> openSession(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      if (session != null && session.isOpen()) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("SESSION_ALREADY_OPEN", "A qualification session is already open"));
      }
      // A previous session that closed or was poisoned underneath us must not keep its
      // frame-source registration alive, or the new install below would be refused.
      discardDeadSession();
      TargetInputSession opened = sessionFactory.openSelectedSession();
      TargetFrameSource installed = null;
      if (opened instanceof TargetFrameSource source) {
        try {
          TargetFrameRuntime.install(source);
          installed = source;
        } catch (RuntimeException installFailure) {
          closeQuietly(opened, installFailure);
          throw installFailure;
        }
      }
      session = opened;
      frameSource = installed;
      logger.info(
          "Qualification session opened for pid={} generation={}",
          opened.identity().pid(),
          opened.selectionGeneration());
      return ResponseEntity.ok(statusBody());
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } catch (IllegalStateException failure) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(error("HARNESS_CONFLICT", failure.getMessage()));
    } finally {
      lock.unlock();
    }
  }

  /** Closes the qualification session and releases every held input. */
  @PostMapping("/session/close")
  public ResponseEntity<Map<String, Object>> closeSession(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      final Map<String, Object> before =
          session != null && session.isOpen() ? identityBody(session) : Map.of();
      releaseAndClose();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("status", "closed");
      body.put("sessionOpen", false);
      body.put("closedIdentity", before);
      return ResponseEntity.ok(body);
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  /** Reports the current qualification session state. */
  @GetMapping("/session")
  public ResponseEntity<Map<String, Object>> sessionStatus(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      return ResponseEntity.ok(statusBody());
    } finally {
      lock.unlock();
    }
  }

  /** Captures one detached rendered frame and writes it as PNG evidence. */
  @PostMapping("/capture")
  public ResponseEntity<Map<String, Object>> capture(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetFrameSource source = requireFrameSource();
      CapturedFrame frame = source.captureFrame();
      BufferedImage image = frame.image();
      if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(error("EMPTY_FRAME", "Captured frame had no image data"));
      }
      Files.createDirectories(captureDirectory);
      String fileName =
          "capture-" + frame.identity().pid() + "-" + System.currentTimeMillis() + ".png";
      Path target = captureDirectory.resolve(fileName);
      if (!ImageIO.write(image, "png", target.toFile())) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(error("PNG_WRITE_FAILED", "No PNG writer was available"));
      }
      byte[] pngBytes = Files.readAllBytes(target);
      Map<String, Object> body = identityBody(session);
      body.put("status", "captured");
      body.put("width", image.getWidth());
      body.put("height", image.getHeight());
      body.put("freshness", frame.freshness().name());
      body.put("observedAt", frame.observedAt().toString());
      body.put("contentChangedAt", frame.contentChangedAt().toString());
      body.put("contentSha256", sha256(pngBytes));
      body.put("pngBytes", pngBytes.length);
      body.put("pngFile", target.getFileName().toString());
      body.put("hasNonUniformPixels", hasNonUniformPixels(image));
      return ResponseEntity.ok(body);
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } catch (IOException failure) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(error("CAPTURE_IO_FAILED", failure.getMessage()));
    } finally {
      lock.unlock();
    }
  }

  /** Moves the virtual mouse to a bounded client-relative point. */
  @PostMapping("/mouse/move")
  public ResponseEntity<Map<String, Object>> moveMouse(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      Point point = new Point(requireInt(body, "x"), requireInt(body, "y"));
      Rectangle bounds = active.getTargetBounds();
      if (!bounds.contains(point)) {
        return ResponseEntity.badRequest()
            .body(error("OUT_OF_BOUNDS", "Point is outside target bounds " + bounds));
      }
      active.moveMouse(point);
      Map<String, Object> result = identityBody(active);
      result.put("status", "moved");
      result.put("mousePosition", Map.of("x", point.x, "y", point.y));
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  /** Holds a mouse button at the current client-relative position. */
  @PostMapping("/mouse/hold")
  public ResponseEntity<Map<String, Object>> holdMouse(
      HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
    return mouseButtonAction(request, body, true);
  }

  /** Releases a held mouse button at the current client-relative position. */
  @PostMapping("/mouse/release")
  public ResponseEntity<Map<String, Object>> releaseMouse(
      HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
    return mouseButtonAction(request, body, false);
  }

  /** Performs one bounded click (hold then release) of a mouse button. */
  @PostMapping("/mouse/click")
  public ResponseEntity<Map<String, Object>> clickMouse(
      HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      MouseButton button = parseButton(body);
      active.holdMouse(button);
      // Once the hold succeeded the release is always attempted, even if a later step fails.
      active.releaseMouse(button);
      Map<String, Object> result = identityBody(active);
      result.put("status", "clicked");
      result.put("button", button.name());
      result.put("mouseHeld", active.isMouseHeld(button));
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  /** Scrolls the mouse wheel at the current position. */
  @PostMapping("/mouse/scroll")
  public ResponseEntity<Map<String, Object>> scrollMouse(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      active.scrollMouse(requireInt(body, "notches"));
      Map<String, Object> result = identityBody(active);
      result.put("status", "scrolled");
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  /** Holds a key identified by a Java {@code KeyEvent} code. */
  @PostMapping("/key/hold")
  public ResponseEntity<Map<String, Object>> holdKey(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    return keyAction(request, body, true);
  }

  /** Releases a held key identified by a Java {@code KeyEvent} code. */
  @PostMapping("/key/release")
  public ResponseEntity<Map<String, Object>> releaseKey(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    return keyAction(request, body, false);
  }

  /** Presses and releases one key with a bounded hold time. */
  @PostMapping("/key/press")
  public ResponseEntity<Map<String, Object>> pressKey(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      int keyCode = requireInt(body, "keyCode");
      int holdMillis = optionalInt(body, "holdMillis", DEFAULT_KEY_HOLD_MILLIS);
      if (holdMillis < 0 || holdMillis > 5000) {
        return ResponseEntity.badRequest()
            .body(error("INVALID_INPUT", "holdMillis must be between 0 and 5000"));
      }
      active.holdKey(keyCode);
      boolean interrupted = false;
      try {
        Thread.sleep(holdMillis);
      } catch (InterruptedException interruption) {
        interrupted = true;
        Thread.currentThread().interrupt();
      } finally {
        // The hold succeeded, so the release is always attempted; a release failure while the
        // hold was interrupted is preserved as a suppressed failure on the reported error.
        releaseHeldKey(active, keyCode, interrupted);
      }
      Map<String, Object> result = identityBody(active);
      result.put("keyCode", keyCode);
      result.put("keyHeld", active.isKeyHeld(keyCode));
      if (interrupted) {
        result.put("status", "error");
        result.put("error", "KEY_PRESS_INTERRUPTED");
        result.put(
            "message", "The key hold was interrupted before the requested hold time elapsed");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
      }
      result.put("status", "pressed");
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  /** Reports whether a key/button is currently held by this session. */
  @GetMapping("/input/held")
  public ResponseEntity<Map<String, Object>> held(
      HttpServletRequest request,
      @RequestParam(name = "keyCode", required = false) Integer keyCode,
      @RequestParam(name = "button", required = false) String button) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      Map<String, Object> result = identityBody(active);
      if (keyCode != null) {
        result.put("keyCode", keyCode);
        result.put("keyHeld", active.isKeyHeld(keyCode));
      }
      if (button != null) {
        MouseButton parsed = parseButton(Map.of("button", button));
        result.put("button", parsed.name());
        result.put("mouseHeld", active.isMouseHeld(parsed));
      }
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  private ResponseEntity<Map<String, Object>> mouseButtonAction(
      HttpServletRequest request, Map<String, Object> body, boolean hold) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      MouseButton button = parseButton(body);
      if (hold) {
        active.holdMouse(button);
      } else {
        active.releaseMouse(button);
      }
      Map<String, Object> result = identityBody(active);
      result.put("status", hold ? "held" : "released");
      result.put("button", button.name());
      result.put("mouseHeld", active.isMouseHeld(button));
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  private ResponseEntity<Map<String, Object>> keyAction(
      HttpServletRequest request, Map<String, Object> body, boolean hold) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    lock.lock();
    try {
      TargetInputSession active = requireOpenSession();
      int keyCode = requireInt(body, "keyCode");
      if (hold) {
        active.holdKey(keyCode);
      } else {
        active.releaseKey(keyCode);
      }
      Map<String, Object> result = identityBody(active);
      result.put("status", hold ? "held" : "released");
      result.put("keyCode", keyCode);
      result.put("keyHeld", active.isKeyHeld(keyCode));
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException failure) {
      return ResponseEntity.badRequest().body(error("INVALID_INPUT", failure.getMessage()));
    } catch (InputSessionException failure) {
      return diagnosticResponse(failure);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Releases a key whose hold already succeeded. When the hold was interrupted, a release failure
   * is attached as a suppressed failure to the interruption diagnostic so neither is lost.
   */
  private static void releaseHeldKey(TargetInputSession active, int keyCode, boolean interrupted) {
    try {
      active.releaseKey(keyCode);
    } catch (RuntimeException releaseFailure) {
      if (!interrupted) {
        throw releaseFailure;
      }
      InputSessionException reported =
          new InputSessionException(
              InputSessionDiagnostic.NATIVE_CALL_FAILED,
              active.identity(),
              active.selectionGeneration(),
              "Key hold was interrupted and the release of key " + keyCode + " failed");
      reported.addSuppressed(releaseFailure);
      throw reported;
    }
  }

  /**
   * Closes the harness session and releases held input when the application context stops.
   *
   * <p>Idempotent and never throws: cleanup failures are logged so Spring shutdown proceeds. The
   * interrupt status is preserved if the shutdown thread was interrupted while acquiring the lock.
   */
  @PreDestroy
  public void shutdown() {
    boolean interrupted = Thread.interrupted();
    lock.lock();
    try {
      if (session == null && frameSource == null) {
        return;
      }
      logger.info("Closing qualification session on shutdown");
      final TargetFrameSource source = frameSource;
      final TargetInputSession active = session;
      frameSource = null;
      session = null;
      if (source != null) {
        try {
          TargetFrameRuntime.uninstall(source);
        } catch (RuntimeException failure) {
          logger.warn("Failed to uninstall qualification frame source on shutdown", failure);
        }
      }
      if (active != null) {
        try {
          active.close();
        } catch (RuntimeException failure) {
          logger.warn("Failed to close qualification session on shutdown", failure);
        }
      }
    } finally {
      lock.unlock();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Clears a non-null session that is no longer open (closed underneath us or poisoned) so its
   * frame-source registration and reference cannot refuse or corrupt a later open. Failures of the
   * dead session's own {@code close()} are logged, not propagated: the caller is about to open a
   * fresh session and the dead one retains no usable state.
   */
  private void discardDeadSession() {
    final TargetFrameSource stale = frameSource;
    final TargetInputSession dead = session;
    frameSource = null;
    session = null;
    if (stale != null) {
      TargetFrameRuntime.uninstall(stale);
    }
    if (dead != null) {
      logger.info("Discarding closed qualification session before reopening");
      try {
        dead.close();
      } catch (RuntimeException failure) {
        logger.warn("Closing an already-dead qualification session failed", failure);
      }
    }
  }

  /**
   * Operator-path close. The session reference is dropped only after {@code close()} succeeds so a
   * failed release ({@code RELEASE_TIMEOUT}, {@code FAILED} state) can be retried through {@code
   * /session/close} instead of leaving an orphaned attachment lease until the application restarts.
   */
  private void releaseAndClose() {
    if (frameSource != null) {
      TargetFrameRuntime.uninstall(frameSource);
      frameSource = null;
    }
    if (session != null) {
      session.close();
      session = null;
    }
  }

  private static void closeQuietly(TargetInputSession opened, RuntimeException original) {
    try {
      opened.close();
    } catch (RuntimeException cleanupFailure) {
      original.addSuppressed(cleanupFailure);
    }
  }

  private TargetFrameSource requireFrameSource() {
    requireOpenSession();
    if (frameSource == null) {
      throw new InputSessionException(
          InputSessionDiagnostic.UNSUPPORTED_PLATFORM,
          session.identity(),
          session.selectionGeneration(),
          "The active session does not expose a rendered frame source on this platform");
    }
    return frameSource;
  }

  private TargetInputSession requireOpenSession() {
    if (session == null || !session.isOpen()) {
      throw new InputSessionException(
          InputSessionDiagnostic.SESSION_CLOSED,
          null,
          selection.getSelectedGeneration(),
          "No qualification session is open");
    }
    return session;
  }

  private Map<String, Object> statusBody() {
    boolean open = session != null && session.isOpen();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", open ? "open" : "closed");
    body.put("sessionOpen", open);
    if (open) {
      body.putAll(identityBody(session));
    } else if (selection.hasSelection() && selection.getSelectedIdentity() != null) {
      body.put(
          "selected",
          describeIdentity(selection.getSelectedIdentity(), selection.getSelectedGeneration()));
    }
    return body;
  }

  private static Map<String, Object> identityBody(TargetInputSession active) {
    Map<String, Object> body = new LinkedHashMap<>();
    ProcessIdentity identity = active.identity();
    body.put("pid", identity.pid());
    body.put("origin", identity.origin().name());
    body.put(
        "processStartTime", identity.startTime() == null ? null : identity.startTime().toString());
    body.put("bootIdPresent", identity.bootId() != null && !identity.bootId().isEmpty());
    body.put("selectionGeneration", active.selectionGeneration());
    Rectangle bounds = active.getTargetBounds();
    body.put(
        "targetBounds",
        Map.of("x", bounds.x, "y", bounds.y, "width", bounds.width, "height", bounds.height));
    Point mouse = active.getMousePosition();
    body.put("mousePosition", Map.of("x", mouse.x, "y", mouse.y));
    body.put("isOpen", active.isOpen());
    return body;
  }

  private static Map<String, Object> describeIdentity(ProcessIdentity identity, long generation) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pid", identity.pid());
    body.put("origin", identity.origin().name());
    body.put(
        "processStartTime", identity.startTime() == null ? null : identity.startTime().toString());
    body.put("bootIdPresent", identity.bootId() != null && !identity.bootId().isEmpty());
    body.put("selectionGeneration", generation);
    return body;
  }

  private static boolean hasNonUniformPixels(BufferedImage image) {
    int first = image.getRGB(0, 0);
    int sampled = 0;
    for (int y = 0; y < image.getHeight(); y += Math.max(1, image.getHeight() / 32)) {
      for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 32)) {
        sampled++;
        if (image.getRGB(x, y) != first) {
          return true;
        }
      }
    }
    return sampled == 0;
  }

  private static int requireInt(Map<String, Object> body, String key) {
    if (body == null || !body.containsKey(key)) {
      throw new IllegalArgumentException("Missing integer field '" + key + "'");
    }
    Object value = body.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("Field '" + key + "' must be an integer");
    }
  }

  private static int optionalInt(Map<String, Object> body, String key, int fallback) {
    if (body == null || !body.containsKey(key) || body.get(key) == null) {
      return fallback;
    }
    return requireInt(body, key);
  }

  private static MouseButton parseButton(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("button");
    String name = raw == null || String.valueOf(raw).isBlank() ? "LEFT" : String.valueOf(raw);
    try {
      return MouseButton.valueOf(name.trim().toUpperCase());
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("button must be one of LEFT, MIDDLE, RIGHT");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static ResponseEntity<Map<String, Object>> diagnosticResponse(
      InputSessionException failure) {
    Map<String, Object> body = error(failure.diagnostic().name(), failure.getMessage());
    body.put("diagnostic", failure.diagnostic().name());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  private static Map<String, Object> error(String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "error");
    body.put("error", code);
    body.put("message", message);
    return body;
  }

  private static ResponseEntity<Map<String, Object>> requireLoopback(HttpServletRequest request) {
    if (isLoopbackAddress(request.getRemoteAddr())) {
      return null;
    }
    logger.warn("Refused non-loopback qualification request from {}", request.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(error("NON_LOOPBACK_REFUSED", "The qualification harness is loopback-only"));
  }

  /** Whether the address text denotes the local host; package-private for focused tests. */
  static boolean isLoopbackAddress(String address) {
    if (address == null || address.isBlank()) {
      return false;
    }
    try {
      return InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException failure) {
      return false;
    }
  }
}
