package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.junit.jupiter.api.Test;

/**
 * Window lookups share one native enumeration callback (a fresh callback per call leaked JNI weak
 * references). The shared callback must still dispatch to the right handler on every call.
 */
class WindowHandlerEnumerationTest {

  @Test
  void nestedEnumerationRestoresOuterHandlerAndSharesNativeCallback() throws Exception {
    requireWindows();
    var type = com.sun.jna.platform.win32.WinUser.WNDENUMPROC.class;
    var windows =
        WindowHandler.class.getDeclaredMethod(
            "enumWindows", WindowHandler.User32.class, type, com.sun.jna.Pointer.class);
    var children =
        WindowHandler.class.getDeclaredMethod(
            "enumChildWindows",
            WindowHandler.User32.class,
            HWND.class,
            type,
            com.sun.jna.Pointer.class);
    var field = WindowHandler.class.getDeclaredField("ENUM_HANDLER");
    windows.setAccessible(true);
    children.setAccessible(true);
    field.setAccessible(true);
    ThreadLocal<?> handler = (ThreadLocal<?>) field.get(null);
    WindowHandler.User32 api = org.mockito.Mockito.mock(WindowHandler.User32.class);
    var callbacks = new java.util.ArrayList<Object>();
    var order = new java.util.ArrayList<String>();
    org.mockito.Mockito.doAnswer(
            call -> {
              var callback =
                  call.getArgument(0, com.sun.jna.platform.win32.WinUser.WNDENUMPROC.class);
              callbacks.add(callback);
              callback.callback(null, null);
              return null;
            })
        .when(api)
        .EnumWindows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(
            call -> {
              var callback =
                  call.getArgument(1, com.sun.jna.platform.win32.WinUser.WNDENUMPROC.class);
              callbacks.add(callback);
              callback.callback(null, null);
              return null;
            })
        .when(api)
        .EnumChildWindows(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    com.sun.jna.platform.win32.WinUser.WNDENUMPROC[] outer =
        new com.sun.jna.platform.win32.WinUser.WNDENUMPROC[1];
    outer[0] =
        (window, data) -> {
          order.add("outer-before");
          try {
            com.sun.jna.platform.win32.WinUser.WNDENUMPROC inner =
                (w, d) -> {
                  order.add("inner");
                  return true;
                };
            children.invoke(null, api, null, inner, null);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
          org.junit.jupiter.api.Assertions.assertSame(outer[0], handler.get());
          order.add("outer-after");
          return true;
        };
    windows.invoke(null, api, outer[0], null);
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of("outer-before", "inner", "outer-after"), order);
    org.junit.jupiter.api.Assertions.assertSame(callbacks.get(0), callbacks.get(1));
    assertNull(handler.get());
  }

  @Test
  void nativeEnumerationExceptionClearsThreadLocalHandler() throws Exception {
    requireWindows();
    var method =
        WindowHandler.class.getDeclaredMethod(
            "enumWindows",
            WindowHandler.User32.class,
            com.sun.jna.platform.win32.WinUser.WNDENUMPROC.class,
            com.sun.jna.Pointer.class);
    method.setAccessible(true);
    var api = org.mockito.Mockito.mock(WindowHandler.User32.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("native enumeration failed"))
        .when(api)
        .EnumWindows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    com.sun.jna.platform.win32.WinUser.WNDENUMPROC callback = (w, d) -> true;
    org.junit.jupiter.api.Assertions.assertThrows(
        java.lang.reflect.InvocationTargetException.class,
        () -> method.invoke(null, api, callback, null));
    var field = WindowHandler.class.getDeclaredField("ENUM_HANDLER");
    field.setAccessible(true);
    assertNull(((ThreadLocal<?>) field.get(null)).get());
  }

  private static void requireWindows() {
    assumeTrue(
        System.getProperty("os.name", "").toLowerCase().contains("windows"),
        "Win32 window enumeration requires Windows");
  }

  @Test
  void repeatedChildLookupsKeepDispatchingToTheirOwnHandler() {
    requireWindows();
    HWND desktop = User32.INSTANCE.GetDesktopWindow();
    assertNotNull(desktop);

    for (int i = 0; i < 50; i++) {
      assertNull(WindowHandler.findNthChildWindow(desktop, "NoSuchWindowClass-" + i, 1));
    }
  }

  @Test
  void enumerationHandlerIsClearedBetweenCalls() {
    requireWindows();
    HWND desktop = User32.INSTANCE.GetDesktopWindow();

    // A lookup that stops early must not leave its handler behind for the next lookup.
    WindowHandler.findNthChildWindow(desktop, "Progman", 1);
    assertNull(WindowHandler.findNthChildWindow(desktop, "NoSuchWindowClass", 1));
  }
}
