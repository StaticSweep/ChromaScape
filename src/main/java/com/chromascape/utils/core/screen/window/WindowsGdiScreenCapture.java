package com.chromascape.utils.core.screen.window;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Windows-only GDI implementation kept outside the platform-neutral {@link ScreenManager}. */
final class WindowsGdiScreenCapture {
  private static final Logger logger = LoggerFactory.getLogger(WindowsGdiScreenCapture.class);
  private static final int SRCCOPY = 0x00CC0020;

  private WindowsGdiScreenCapture() {}

  interface User32Extended extends User32 {
    User32Extended INSTANCE = Native.load("user32", User32Extended.class);

    @SuppressWarnings("checkstyle:MethodName")
    void ClientToScreen(HWND hwnd, POINT point);
  }

  static BufferedImage captureWindow(WindowSelectionService selectionService) {
    CanvasTarget target = resolveCanvasTarget(selectionService);
    HWND hwnd = target.hwnd();
    if (hwnd == null) {
      throw new IllegalStateException("Game window not found - is RuneLite running?");
    }

    RECT bounds = new RECT();
    if (!User32.INSTANCE.GetClientRect(hwnd, bounds)) {
      throw new IllegalStateException("Unable to read target client bounds");
    }
    int width = bounds.right - bounds.left;
    int height = bounds.bottom - bounds.top;
    if (width <= 0 || height <= 0) {
      throw new IllegalStateException(
          "Invalid dimensions for capture HWND "
              + formatHwnd(hwnd)
              + " via "
              + target.source()
              + ": width="
              + width
              + ", height="
              + height);
    }

    HDC source = User32.INSTANCE.GetDC(hwnd);
    if (source == null) {
      throw new IllegalStateException("Unable to acquire target device context");
    }
    HDC memory = null;
    HBITMAP bitmap = null;
    HANDLE previous = null;
    try {
      memory = GDI32.INSTANCE.CreateCompatibleDC(source);
      bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(source, width, height);
      if (memory == null || bitmap == null) {
        throw new IllegalStateException("Unable to allocate GDI capture resources");
      }
      previous = GDI32.INSTANCE.SelectObject(memory, bitmap);
      if (!GDI32.INSTANCE.BitBlt(memory, 0, 0, width, height, source, 0, 0, SRCCOPY)) {
        throw new IllegalStateException("BitBlt capture failed");
      }

      BITMAPINFO info = new BITMAPINFO();
      info.bmiHeader.biWidth = width;
      info.bmiHeader.biHeight = -height;
      info.bmiHeader.biPlanes = 1;
      info.bmiHeader.biBitCount = 32;
      info.bmiHeader.biCompression = WinGDI.BI_RGB;
      Memory buffer = new Memory(Math.multiplyExact(Math.multiplyExact((long) width, height), 4L));
      int scanLines =
          GDI32.INSTANCE.GetDIBits(memory, bitmap, 0, height, buffer, info, WinGDI.DIB_RGB_COLORS);
      if (scanLines != height) {
        throw new IllegalStateException("GetDIBits returned an incomplete capture");
      }

      BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      int[] pixels = buffer.getIntArray(0, Math.multiplyExact(width, height));
      int[] destination = ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
      System.arraycopy(pixels, 0, destination, 0, pixels.length);
      BufferedImage bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
      Graphics2D graphics = bgr.createGraphics();
      try {
        graphics.drawImage(rgb, 0, 0, null);
      } finally {
        graphics.dispose();
      }
      return bgr;
    } finally {
      if (memory != null && previous != null) {
        GDI32.INSTANCE.SelectObject(memory, previous);
      }
      if (bitmap != null) {
        GDI32.INSTANCE.DeleteObject(bitmap);
      }
      if (memory != null) {
        GDI32.INSTANCE.DeleteDC(memory);
      }
      User32.INSTANCE.ReleaseDC(hwnd, source);
    }
  }

  static Rectangle getWindowBounds(WindowSelectionService selectionService) {
    HWND hwnd = resolveCanvasTarget(selectionService).hwnd();
    if (hwnd == null) {
      throw new IllegalStateException("Game window not found - is RuneLite running?");
    }
    RECT dimensions = new RECT();
    User32.INSTANCE.GetClientRect(hwnd, dimensions);
    POINT origin = new POINT();
    User32Extended.INSTANCE.ClientToScreen(hwnd, origin);
    return new Rectangle(
        origin.x, origin.y, dimensions.right - dimensions.left, dimensions.bottom - dimensions.top);
  }

  static Rectangle getMonitorBounds(WindowSelectionService selectionService) {
    HWND hwnd = resolveCanvasTarget(selectionService).hwnd();
    if (hwnd == null) {
      throw new IllegalStateException("Game window not found - is RuneLite running?");
    }
    WinUser.HMONITOR monitor =
        User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST);
    WinUser.MONITORINFO info = new WinUser.MONITORINFO();
    info.cbSize = info.size();
    User32.INSTANCE.GetMonitorInfo(monitor, info);
    RECT bounds = info.rcMonitor;
    return new Rectangle(
        bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top);
  }

  private static CanvasTarget resolveCanvasTarget(WindowSelectionService selectionService) {
    HWND target = selectionService != null ? selectionService.getSelectedHwnd() : null;
    String source = "explicit selection";
    if (target == null) {
      target = WindowHandler.getTargetWindow();
      source = "auto-detection";
    }
    if (target == null) {
      logger.warn("No target window available for capture");
      return new CanvasTarget(null, source);
    }
    HWND canvas = WindowHandler.findNthChildWindow(target, "SunAwtCanvas", 2);
    if (canvas == null) {
      canvas = WindowHandler.findNthChildWindow(target, "SunAwtCanvas", 1);
    }
    if (canvas == null) {
      canvas = target;
    }
    return new CanvasTarget(canvas, source);
  }

  private static String formatHwnd(HWND hwnd) {
    return String.format("0x%X", Pointer.nativeValue(hwnd.getPointer()));
  }

  private record CanvasTarget(HWND hwnd, String source) {}
}
