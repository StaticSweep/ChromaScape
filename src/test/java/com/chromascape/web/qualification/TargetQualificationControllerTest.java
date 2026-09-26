package com.chromascape.web.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.input.TargetInputSessionFactory;
import com.chromascape.utils.core.input.remoteinput.MouseButton;
import com.chromascape.utils.core.input.remoteinput.RemoteInputSession;
import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TargetQualificationControllerTest {

  private static final ProcessIdentity IDENTITY =
      new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");
  private static final String ENABLED = "chromascape.qualification.enabled=true";
  private static final String DEV_PROFILE = TargetQualificationController.REQUIRED_PROFILE;

  private final java.util.List<TargetFrameSource> installedSources = new java.util.ArrayList<>();

  @AfterEach
  void releaseFrameRuntime() {
    // Uninstall every source a test may have installed so a mid-test failure can never leave a
    // mock in the process-wide TargetFrameRuntime and poison later tests.
    for (TargetFrameSource source : installedSources) {
      TargetFrameRuntime.uninstall(source);
    }
    installedSources.clear();
  }

  // --- bean gating -------------------------------------------------------------------------

  @Test
  void harnessBeanIsAbsentWithoutTheExplicitProperty() {
    harnessContext()
        .withPropertyValues("spring.profiles.active=" + DEV_PROFILE)
        .run(
            context -> {
              assertTrue(
                  context.getBeansOfType(TargetQualificationController.class).isEmpty(),
                  "harness must stay disabled by default even under the development profile");
            });
  }

  @Test
  void harnessBeanIsAbsentWithoutAnyActiveProfile() {
    // A developer shell may export SPRING_PROFILES_ACTIVE; pin the runner to the reserved
    // "default" profile so this case really exercises "no explicit profile".
    harnessContext()
        .withPropertyValues(ENABLED, "spring.profiles.active=default")
        .run(
            context -> {
              assertTrue(
                  context.getBeansOfType(TargetQualificationController.class).isEmpty(),
                  "harness must be absent when no profile is active");
            });
  }

  @Test
  void harnessBeanIsAbsentUnderProductionAndTestProfiles() {
    for (String profile :
        new String[] {"production", "prod", "test", "sc1431-jpa", "fedora-development"}) {
      harnessContext()
          .withPropertyValues(ENABLED, "spring.profiles.active=" + profile)
          .run(
              context -> {
                assertTrue(
                    context.getBeansOfType(TargetQualificationController.class).isEmpty(),
                    "harness must be absent under profile " + profile);
              });
    }
  }

  @Test
  void harnessBeanIsAbsentWhenPropertyIsExplicitlyFalse() {
    harnessContext()
        .withPropertyValues(
            "chromascape.qualification.enabled=false", "spring.profiles.active=" + DEV_PROFILE)
        .run(
            context -> {
              assertTrue(context.getBeansOfType(TargetQualificationController.class).isEmpty());
            });
  }

  @Test
  void harnessBeanIsPresentOnlyWithProfileAndProperty() {
    harnessContext()
        .withPropertyValues(ENABLED, "spring.profiles.active=" + DEV_PROFILE)
        .run(
            context -> {
              assertNotNull(context.getBean(TargetQualificationController.class));
            });
  }

  @Test
  void defaultDisabledBehaviorRemainsIntact() {
    harnessContext()
        .withPropertyValues("spring.profiles.active=default")
        .run(
            context -> {
              assertTrue(
                  context.getBeansOfType(TargetQualificationController.class).isEmpty(),
                  "no property and no profile must yield no harness");
            });
  }

  // --- caller guard ------------------------------------------------------------------------

  @Test
  void loopbackGuardAcceptsOnlyLocalAddresses() {
    assertTrue(TargetQualificationController.isLoopbackAddress("127.0.0.1"));
    assertTrue(TargetQualificationController.isLoopbackAddress("::1"));
    assertTrue(TargetQualificationController.isLoopbackAddress("::ffff:127.0.0.1"));
    assertFalse(TargetQualificationController.isLoopbackAddress("10.0.0.5"));
    assertFalse(TargetQualificationController.isLoopbackAddress("192.168.1.10"));
    assertFalse(TargetQualificationController.isLoopbackAddress(null));
    assertFalse(TargetQualificationController.isLoopbackAddress("  "));
  }

  @Test
  void nonLoopbackCallerIsRefused() {
    Fixture fixture = new Fixture();
    HttpServletRequest request = requestFrom("10.0.0.5");

    ResponseEntity<Map<String, Object>> response = fixture.controller.openSession(request);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    assertEquals("NON_LOOPBACK_REFUSED", response.getBody().get("error"));
    verify(fixture.factory, never()).openSelectedSession();
  }

  @Test
  void nonLoopbackCallerIsRefusedOnEveryMutation() {
    Fixture fixture = new Fixture();
    HttpServletRequest remote = requestFrom("192.168.1.10");

    assertEquals(HttpStatus.FORBIDDEN, fixture.controller.closeSession(remote).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, fixture.controller.capture(remote).getStatusCode());
    assertEquals(
        HttpStatus.FORBIDDEN,
        fixture.controller.moveMouse(remote, Map.of("x", 1, "y", 1)).getStatusCode());
    assertEquals(
        HttpStatus.FORBIDDEN, fixture.controller.clickMouse(remote, Map.of()).getStatusCode());
    assertEquals(
        HttpStatus.FORBIDDEN,
        fixture.controller.pressKey(remote, Map.of("keyCode", 65)).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, fixture.controller.held(remote, 65, null).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, fixture.controller.sessionStatus(remote).getStatusCode());
  }

  // --- session behaviour -------------------------------------------------------------------

  @Test
  void disabledAttachmentSurfacesTheStableDiagnostic() {
    Fixture fixture = new Fixture();
    when(fixture.factory.openSelectedSession())
        .thenThrow(
            new InputSessionException(
                InputSessionDiagnostic.ATTACHMENT_DISABLED, null, 1L, "attachment disabled"));

    ResponseEntity<Map<String, Object>> response =
        fixture.controller.openSession(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals("ATTACHMENT_DISABLED", response.getBody().get("diagnostic"));
  }

  @Test
  void outOfBoundsMovementIsRejectedWithoutReachingTheSession() {
    Fixture fixture = new Fixture().withOpenSession();

    ResponseEntity<Map<String, Object>> response =
        fixture.controller.moveMouse(requestFrom("127.0.0.1"), Map.of("x", 500, "y", 50));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("OUT_OF_BOUNDS", response.getBody().get("error"));
    verify(fixture.session, never()).moveMouse(any());
  }

  @Test
  void inBoundsMovementReachesTheSession() {
    Fixture fixture = new Fixture().withOpenSession();
    when(fixture.session.getMousePosition()).thenReturn(new Point(50, 25));

    ResponseEntity<Map<String, Object>> response =
        fixture.controller.moveMouse(requestFrom("127.0.0.1"), Map.of("x", 50, "y", 25));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(fixture.session).moveMouse(new Point(50, 25));
  }

  @Test
  void closeReleasesTheSessionAndHeldInputState() {
    Fixture fixture = new Fixture().withOpenSession();
    when(fixture.selection.getSelectedGeneration()).thenReturn(7L);

    ResponseEntity<Map<String, Object>> closed =
        fixture.controller.closeSession(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.OK, closed.getStatusCode());
    assertEquals(false, closed.getBody().get("sessionOpen"));
    verify(fixture.session).close();
  }

  @Test
  void keyPressHoldsThenReleases() {
    Fixture fixture = new Fixture().withOpenSession();

    ResponseEntity<Map<String, Object>> response =
        fixture.controller.pressKey(
            requestFrom("127.0.0.1"), Map.of("keyCode", 65, "holdMillis", 0));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("pressed", response.getBody().get("status"));
    verify(fixture.session).holdKey(65);
    verify(fixture.session).releaseKey(65);
  }

  @Test
  void interruptedKeyPressStillReleasesAndReportsAnError() throws Exception {
    Fixture fixture = new Fixture().withOpenSession();
    CountDownLatch held = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              held.countDown();
              return null;
            })
        .when(fixture.session)
        .holdKey(65);
    AtomicReference<ResponseEntity<Map<String, Object>>> response = new AtomicReference<>();
    AtomicReference<Boolean> interruptFlagRestored = new AtomicReference<>(false);

    Thread presser =
        new Thread(
            () -> {
              response.set(
                  fixture.controller.pressKey(
                      requestFrom("127.0.0.1"), Map.of("keyCode", 65, "holdMillis", 5000)));
              interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            });
    presser.start();
    assertTrue(held.await(5, TimeUnit.SECONDS), "hold must have been issued");
    presser.interrupt();
    presser.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(presser.isAlive(), "interrupted press must not wait out the full hold");

    verify(fixture.session).holdKey(65);
    verify(fixture.session).releaseKey(65);
    assertTrue(interruptFlagRestored.get(), "interrupt status must be restored");
    assertEquals(HttpStatus.CONFLICT, response.get().getStatusCode());
    assertEquals("error", response.get().getBody().get("status"));
    assertEquals("KEY_PRESS_INTERRUPTED", response.get().getBody().get("error"));
  }

  @Test
  void interruptedKeyPressPreservesReleaseFailureAsSuppressed() throws Exception {
    Fixture fixture = new Fixture().withOpenSession();
    CountDownLatch held = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              held.countDown();
              return null;
            })
        .when(fixture.session)
        .holdKey(65);
    InputSessionException releaseFailure =
        new InputSessionException(
            InputSessionDiagnostic.NATIVE_CALL_TIMEOUT, IDENTITY, 7L, "release timed out");
    doThrow(releaseFailure).when(fixture.session).releaseKey(65);
    AtomicReference<ResponseEntity<Map<String, Object>>> response = new AtomicReference<>();

    Thread presser =
        new Thread(
            () ->
                response.set(
                    fixture.controller.pressKey(
                        requestFrom("127.0.0.1"), Map.of("keyCode", 65, "holdMillis", 5000))));
    presser.start();
    assertTrue(held.await(5, TimeUnit.SECONDS));
    presser.interrupt();
    presser.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(presser.isAlive());

    verify(fixture.session).releaseKey(65);
    assertEquals(HttpStatus.CONFLICT, response.get().getStatusCode());
    assertEquals("NATIVE_CALL_FAILED", response.get().getBody().get("diagnostic"));
  }

  @Test
  void failedMouseReleaseLeavesTheSessionRecoverableThroughClose() {
    Fixture fixture = new Fixture().withOpenSession();
    doThrow(
            new InputSessionException(
                InputSessionDiagnostic.NATIVE_CALL_FAILED, IDENTITY, 7L, "release failed"))
        .when(fixture.session)
        .releaseMouse(MouseButton.LEFT);

    ResponseEntity<Map<String, Object>> click =
        fixture.controller.clickMouse(requestFrom("127.0.0.1"), Map.of("button", "LEFT"));
    assertEquals(HttpStatus.CONFLICT, click.getStatusCode());
    assertEquals("NATIVE_CALL_FAILED", click.getBody().get("diagnostic"));
    verify(fixture.session).holdMouse(MouseButton.LEFT);
    verify(fixture.session).releaseMouse(MouseButton.LEFT);

    ResponseEntity<Map<String, Object>> status =
        fixture.controller.sessionStatus(requestFrom("127.0.0.1"));
    assertEquals(true, status.getBody().get("sessionOpen"), "session must still be tracked");

    ResponseEntity<Map<String, Object>> closed =
        fixture.controller.closeSession(requestFrom("127.0.0.1"));
    assertEquals(HttpStatus.OK, closed.getStatusCode());
    verify(fixture.session).close();
  }

  @Test
  void failedCloseKeepsTheSessionSoCloseCanBeRetried() {
    Fixture fixture = new Fixture().withOpenSession();
    doThrow(
            new InputSessionException(
                InputSessionDiagnostic.RELEASE_TIMEOUT, IDENTITY, 7L, "release timed out"))
        .doNothing()
        .when(fixture.session)
        .close();

    ResponseEntity<Map<String, Object>> first =
        fixture.controller.closeSession(requestFrom("127.0.0.1"));
    assertEquals(HttpStatus.CONFLICT, first.getStatusCode());
    assertEquals("RELEASE_TIMEOUT", first.getBody().get("diagnostic"));
    assertEquals(
        true,
        fixture.controller.sessionStatus(requestFrom("127.0.0.1")).getBody().get("sessionOpen"),
        "a session whose close failed must remain addressable for a retry");

    ResponseEntity<Map<String, Object>> second =
        fixture.controller.closeSession(requestFrom("127.0.0.1"));
    assertEquals(HttpStatus.OK, second.getStatusCode());
    verify(fixture.session, times(2)).close();
    assertEquals(
        false,
        fixture.controller.sessionStatus(requestFrom("127.0.0.1")).getBody().get("sessionOpen"));
  }

  @Test
  void previouslyClosedSessionDoesNotPoisonALaterOpen() {
    Fixture fixture = new Fixture();
    TargetInputSession first = frameSourceSession();
    TargetInputSession second = frameSourceSession();
    when(fixture.factory.openSelectedSession()).thenReturn(first, second);

    assertEquals(
        HttpStatus.OK, fixture.controller.openSession(requestFrom("127.0.0.1")).getStatusCode());
    assertSame(first, TargetFrameRuntime.requireActive());

    // The target dies underneath the harness: the session reports closed without /session/close.
    when(first.isOpen()).thenReturn(false);

    ResponseEntity<Map<String, Object>> reopened =
        fixture.controller.openSession(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.OK, reopened.getStatusCode());
    assertSame(second, TargetFrameRuntime.requireActive(), "stale registration must be replaced");
    verify(first).close();
  }

  @Test
  void openRefusesWhileASessionIsStillOpen() {
    Fixture fixture = new Fixture().withOpenSession();

    ResponseEntity<Map<String, Object>> again =
        fixture.controller.openSession(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.CONFLICT, again.getStatusCode());
    assertEquals("SESSION_ALREADY_OPEN", again.getBody().get("error"));
    verify(fixture.factory, times(1)).openSelectedSession();
  }

  // --- poisoned sessions -------------------------------------------------------------------

  @Test
  void poisonedSessionReportsRestartRequiredAndNeverReturnsClosed() {
    Fixture fixture = new Fixture();
    RemoteInputSession poisoned = poisonedSession(fixture);

    when(poisoned.isPoisoned()).thenReturn(true);
    when(poisoned.isOpen()).thenReturn(false);
    doThrow(
            new InputSessionException(
                InputSessionDiagnostic.SESSION_POISONED, IDENTITY, 7L, "restart required"))
        .when(poisoned)
        .close();

    ResponseEntity<Map<String, Object>> closed =
        fixture.controller.closeSession(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.CONFLICT, closed.getStatusCode());
    assertFalse(
        "closed".equals(closed.getBody().get("status")),
        "a poisoned session must never report closed: " + closed.getBody());
    assertEquals("SESSION_POISONED", closed.getBody().get("diagnostic"));
    assertEquals(true, closed.getBody().get("restartRequired"));
    assertEquals(false, closed.getBody().get("sessionOpen"));
    assertEquals(42L, closed.getBody().get("pid"));
    assertEquals(7L, closed.getBody().get("selectionGeneration"));
  }

  @Test
  void poisonedSessionCannotBeReopenedWithoutRestart() {
    Fixture fixture = new Fixture();
    RemoteInputSession poisoned = poisonedSession(fixture);
    when(poisoned.isPoisoned()).thenReturn(true);
    when(poisoned.isOpen()).thenReturn(false);

    ResponseEntity<Map<String, Object>> reopened =
        fixture.controller.openSession(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.CONFLICT, reopened.getStatusCode());
    assertEquals("SESSION_POISONED_RESTART_REQUIRED", reopened.getBody().get("error"));
    assertEquals(true, reopened.getBody().get("restartRequired"));
    verify(fixture.factory, times(1)).openSelectedSession();
  }

  @Test
  void poisonedSessionStatusReportsRestartRequired() {
    Fixture fixture = new Fixture();
    RemoteInputSession poisoned = poisonedSession(fixture);
    when(poisoned.isPoisoned()).thenReturn(true);

    ResponseEntity<Map<String, Object>> status =
        fixture.controller.sessionStatus(requestFrom("127.0.0.1"));

    assertEquals(HttpStatus.CONFLICT, status.getStatusCode());
    assertEquals(true, status.getBody().get("restartRequired"));
    assertEquals("SESSION_POISONED", status.getBody().get("diagnostic"));
    assertEquals(false, status.getBody().get("sessionOpen"));
  }

  // --- lifecycle cleanup -------------------------------------------------------------------

  @Test
  void destructionClosesAnOpenSession() {
    Fixture fixture = new Fixture().withOpenSession();

    fixture.controller.shutdown();

    verify(fixture.session).close();
    ResponseEntity<Map<String, Object>> status =
        fixture.controller.sessionStatus(requestFrom("127.0.0.1"));
    assertEquals(false, status.getBody().get("sessionOpen"));
  }

  @Test
  void destructionUninstallsTheExactFrameSource() {
    Fixture fixture = new Fixture();
    TargetInputSession session = frameSourceSession();
    when(fixture.factory.openSelectedSession()).thenReturn(session);
    assertEquals(
        HttpStatus.OK, fixture.controller.openSession(requestFrom("127.0.0.1")).getStatusCode());
    assertSame(session, TargetFrameRuntime.requireActive());

    fixture.controller.shutdown();

    assertThrows(IllegalStateException.class, TargetFrameRuntime::requireActive);
    verify(session).close();
  }

  @Test
  void destructionIsIdempotent() {
    Fixture fixture = new Fixture().withOpenSession();

    fixture.controller.shutdown();
    fixture.controller.shutdown();
    fixture.controller.shutdown();

    verify(fixture.session, times(1)).close();
  }

  @Test
  void destructionWithoutASessionIsANoOp() {
    Fixture fixture = new Fixture();

    fixture.controller.shutdown();

    verify(fixture.factory, never()).openSelectedSession();
  }

  @Test
  void destructionSwallowsCleanupFailuresAndPreservesInterrupt() {
    Fixture fixture = new Fixture().withOpenSession();
    doThrow(new IllegalStateException("native release exploded")).when(fixture.session).close();

    Thread.currentThread().interrupt();
    try {
      fixture.controller.shutdown();
      assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be preserved");
    } finally {
      Thread.interrupted();
    }
    verify(fixture.session).close();
    ResponseEntity<Map<String, Object>> status =
        fixture.controller.sessionStatus(requestFrom("127.0.0.1"));
    assertEquals(false, status.getBody().get("sessionOpen"));
  }

  // --- helpers -----------------------------------------------------------------------------

  private TargetInputSession frameSourceSession() {
    TargetInputSession session =
        mock(TargetInputSession.class, withSettings().extraInterfaces(TargetFrameSource.class));
    when(session.isOpen()).thenReturn(true);
    when(session.identity()).thenReturn(IDENTITY);
    when(session.selectionGeneration()).thenReturn(7L);
    when(session.getTargetBounds()).thenReturn(new Rectangle(0, 0, 100, 100));
    when(session.getMousePosition()).thenReturn(new Point(0, 0));
    installedSources.add((TargetFrameSource) session);
    return session;
  }

  /**
   * Opens a real {@link RemoteInputSession} mock as a healthy session (installing it as the frame
   * source) so a later {@code isPoisoned()} transition exercises the controller's restart-required
   * path. The mock is registered for uninstall in {@link #releaseFrameRuntime()}.
   */
  private RemoteInputSession poisonedSession(Fixture fixture) {
    RemoteInputSession poisoned = mock(RemoteInputSession.class);
    when(poisoned.isOpen()).thenReturn(true);
    when(poisoned.identity()).thenReturn(IDENTITY);
    when(poisoned.selectionGeneration()).thenReturn(7L);
    when(poisoned.getTargetBounds()).thenReturn(new Rectangle(0, 0, 100, 100));
    when(poisoned.getMousePosition()).thenReturn(new Point(0, 0));
    when(fixture.factory.openSelectedSession()).thenReturn(poisoned);
    installedSources.add(poisoned);
    assertEquals(
        HttpStatus.OK, fixture.controller.openSession(requestFrom("127.0.0.1")).getStatusCode());
    return poisoned;
  }

  private static HttpServletRequest requestFrom(String remoteAddr) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn(remoteAddr);
    return request;
  }

  private static ApplicationContextRunner harnessContext() {
    return new ApplicationContextRunner().withUserConfiguration(HarnessConfig.class);
  }

  @Configuration
  @Import(TargetQualificationController.class)
  static class HarnessConfig {
    @Bean
    TargetInputSessionFactory targetInputSessionFactory() {
      return mock(TargetInputSessionFactory.class);
    }

    @Bean
    WindowSelectionService windowSelectionService() {
      return mock(WindowSelectionService.class);
    }
  }

  private static final class Fixture {
    private final TargetInputSessionFactory factory = mock(TargetInputSessionFactory.class);
    private final WindowSelectionService selection = mock(WindowSelectionService.class);
    private final TargetInputSession session = mock(TargetInputSession.class);
    private final TargetQualificationController controller =
        new TargetQualificationController(
            factory, selection, System.getProperty("java.io.tmpdir") + "/qual-captures");

    Fixture withOpenSession() {
      when(factory.openSelectedSession()).thenReturn(session);
      when(session.isOpen()).thenReturn(true);
      when(session.identity()).thenReturn(IDENTITY);
      when(session.selectionGeneration()).thenReturn(7L);
      when(session.getTargetBounds()).thenReturn(new Rectangle(0, 0, 100, 100));
      when(session.getMousePosition()).thenReturn(new Point(0, 0));
      assertEquals(HttpStatus.OK, controller.openSession(requestFrom("127.0.0.1")).getStatusCode());
      return this;
    }
  }
}
