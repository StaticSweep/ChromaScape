package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CachedCanvasIdentityTest {
  private WindowsGdiScreenCapture.User32Extended api;
  private HWND root = new HWND(Pointer.createConstant(101));
  private HWND canvas = new HWND(Pointer.createConstant(102));
  private int pid = Math.toIntExact(ProcessHandle.current().pid());
  private Instant started = ProcessHandle.current().info().startInstant().orElseThrow();

  @BeforeEach
  void setup() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("os.name").startsWith("Windows"));
    api = mock(WindowsGdiScreenCapture.User32Extended.class);
    when(api.IsWindow(any())).thenReturn(true);
    when(api.IsChild(root, canvas)).thenReturn(true);
    when(api.GetWindowThreadProcessId(any(), any()))
        .thenAnswer(
            call -> {
              call.getArgument(1, IntByReference.class).setValue(pid);
              return 1;
            });
    when(api.GetWindowText(eq(root), any(char[].class), anyInt()))
        .thenAnswer(
            call -> {
              "RuneLite".getChars(0, 8, call.getArgument(1), 0);
              return 8;
            });
    when(api.GetClassName(eq(canvas), any(char[].class), anyInt()))
        .thenAnswer(
            call -> {
              "SunAwtCanvas".getChars(0, 12, call.getArgument(1), 0);
              return 12;
            });
  }

  private WindowsGdiScreenCapture.CachedCanvas target(Instant identityStart) {
    return new WindowsGdiScreenCapture.CachedCanvas(
        root, canvas, pid, identityStart, "RuneLite", "SunAwtCanvas");
  }

  @Test
  void unchangedIdentityIsAccepted() {
    assertTrue(target(started).isValid(api));
  }

  @Test
  void nativeParentCheckIsAvailableAndRejectsSelf() {
    HWND desktop = User32.INSTANCE.GetDesktopWindow();
    assertFalse(WindowsGdiScreenCapture.User32Extended.INSTANCE.IsChild(desktop, desktop));
  }

  @Test
  void nativeWindowTextAndClassBindingsResolveUnicodeExports() {
    HWND desktop = User32.INSTANCE.GetDesktopWindow();
    var nativeApi = WindowsGdiScreenCapture.User32Extended.INSTANCE;
    char[] title = new char[512];
    char[] className = new char[256];
    assertTrue(nativeApi.GetWindowText(desktop, title, title.length) >= 0);
    assertTrue(nativeApi.GetClassName(desktop, className, className.length) > 0);
  }

  @Test
  void recycledHandleForAnotherProcessIsRejected() {
    doAnswer(
            call -> {
              call.getArgument(1, IntByReference.class).setValue(pid + 1);
              return 1;
            })
        .when(api)
        .GetWindowThreadProcessId(any(), any());
    assertFalse(target(started).isValid(api));
  }

  @Test
  void reusedPidWithDifferentStartTimeIsRejected() {
    assertFalse(target(started.minusSeconds(1)).isValid(api));
  }

  @Test
  void detachedCanvasIsRejected() {
    when(api.IsChild(root, canvas)).thenReturn(false);
    assertFalse(target(started).isValid(api));
  }

  @Test
  void renamedRootAndChangedCanvasClassAreRejected() {
    assertFalse(
        new WindowsGdiScreenCapture.CachedCanvas(
                root, canvas, pid, started, "other", "SunAwtCanvas")
            .isValid(api));
    assertFalse(
        new WindowsGdiScreenCapture.CachedCanvas(
                root, canvas, pid, started, "RuneLite", "OtherClass")
            .isValid(api));
  }
}
