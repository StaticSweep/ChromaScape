package com.chromascape.utils.core.screen.window;

import com.chromascape.utils.core.AccountManager;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Note: ArrayList is already imported above, but ensuring it's available

/**
 * Utility class for locating and identifying a specific native window (e.g., "RuneLite") on the
 * Windows operating system using JNA and Win32 APIs.
 */
public class WindowHandler {
  private static final Logger logger = LoggerFactory.getLogger(WindowHandler.class);
  private static final String _windowName = "RuneLite";
  private static final String CANVAS_CLASS_NAME = "SunAwtCanvas";
  private static final String[] SUPPORTED_CLIENTS = {"RuneLite", "Leeuwbot", "VitaLite"};
  private static final AtomicReference<String> lastPreferredWindowLogKey =
      new AtomicReference<>("<unset>");

  /**
   * JNA interface for accessing low-level Win32 User32 functions that are not provided by the
   * default JNA platform mappings.
   */
  public interface User32 extends StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class);

    /**
     * Enumerates all top-level windows on the screen by invoking the provided callback.
     *
     * @param lpEnumFunc The callback to be called for each window.
     * @param arg A user-defined value passed to the callback (usually null).
     */
    void EnumWindows(WNDENUMPROC lpEnumFunc, Pointer arg);

    /**
     * Retrieves the title text of the specified window.
     *
     * @param hwnd Handle to the window.
     * @param lpString Buffer that receives the window title.
     * @param maxCount Maximum number of characters to copy.
     */
    void GetWindowTextA(HWND hwnd, byte[] lpString, int maxCount);

    /**
     * Retrieves the process identifier (PID) for the specified window.
     *
     * @param hwnd Handle to the window.
     * @param lpDword Receives the process ID.
     */
    void GetWindowThreadProcessId(HWND hwnd, IntByReference lpDword);

    /**
     * Enumerates all child windows of a specified parent window.
     *
     * <p>This function calls the provided callback {@code lpEnumFunc} for each child window of the
     * specified parent {@code hWndParent}. It is commonly used to locate specific child controls or
     * canvases within a larger window.
     *
     * @param hwndParent the handle of the parent window whose children are to be enumerated
     * @param lpEnumFunc the callback function to be called for each child window
     * @param data a pointer to user-defined data that will be passed to the callback
     */
    void EnumChildWindows(HWND hwndParent, WNDENUMPROC lpEnumFunc, Pointer data);

    /**
     * Retrieves the class name of a specified window.
     *
     * <p>This function writes the window class name of {@code hwnd} into the provided byte array
     * {@code lpString}, up to a maximum of {@code maxCount} characters. It is useful for
     * identifying specific child windows or controls by class.
     *
     * @param hwnd the handle of the window whose class name is to be retrieved
     * @param lpString the byte array to receive the class name
     * @param maxCount the maximum number of characters to copy into {@code lpString}
     */
    void GetClassNameA(HWND hwnd, byte[] lpString, int maxCount);

    /**
     * Retrieves the window rectangle for the specified window.
     *
     * @param hwnd The handle of the target window.
     * @param rect Receives the window rectangle coordinates.
     * @return true if successful, false otherwise.
     */
    boolean GetWindowRect(HWND hwnd, RECT rect);
  }

  /**
   * Attempts to locate the window whose title matches the {@code windowName}.
   *
   * <p>This method uses a multi-tier fallback strategy:
   *
   * <ol>
   *   <li>Exact match on "RuneLite" or "RuneLite - accountname"
   *   <li>Partial match (case-insensitive) for windows containing "runelite"
   *   <li>First available Java/AWT window (final fallback)
   * </ol>
   *
   * @return The {@link HWND} handle of the target window, or {@code null} if not found.
   */
  public static HWND getTargetWindow() {
    User32 user32 = User32.INSTANCE;

    // Get the selected account from AccountManager (loaded during startup)
    String selectedAccount = AccountManager.getSelectedAccount();

    // Build the resolved window name - make it effectively final
    final String resolvedWindowName =
        (selectedAccount != null && !selectedAccount.trim().isEmpty())
            ? _windowName + " - " + selectedAccount
            : _windowName;

    logger.debug("Looking for window: {}", resolvedWindowName);

    // Debug: Count total windows and log some titles
    AtomicInteger totalWindows = new AtomicInteger(0);
    List<String> sampleTitles = new ArrayList<>();

    // Tier 1: Try exact match
    AtomicReference<WindowInfo> exactMatch = new AtomicReference<>();
    user32.EnumWindows(
        (hwnd, arg) -> {
          totalWindows.incrementAndGet();
          byte[] buffer = new byte[512];
          user32.GetWindowTextA(hwnd, buffer, 512);
          String title = Native.toString(buffer);

          // Collect sample titles for debugging (first 10)
          if (sampleTitles.size() < 10 && !title.trim().isEmpty()) {
            sampleTitles.add(title.trim());
          }

          if (title.trim().equals(resolvedWindowName)) {
            int pid = getPid(hwnd);
            Rectangle bounds = getWindowBounds(user32, hwnd);
            exactMatch.set(new WindowInfo("0", hwnd, title.trim(), selectedAccount, pid, bounds));
            logger.info("Found exact match: {}", title);
            return false; // stop enumeration
          }
          return true;
        },
        null);

    if (exactMatch.get() != null && isGameClientWindow(exactMatch.get())) {
      logPreferredTargetSelection(
          exactMatch.get(),
          detectCaptureSignature(exactMatch.get()),
          "exact title match",
          true,
          totalWindows.get(),
          sampleTitles);
      return exactMatch.get().getHwnd();
    }

    List<WindowInfo> filteredTargets =
        getAllTargetWindows().stream().filter(WindowHandler::isGameClientWindow).toList();
    WindowInfo preferredTarget = selectBestWindow(filteredTargets, WindowHandler::detectCaptureSignature);
    if (preferredTarget != null) {
      CaptureSignature signature = detectCaptureSignature(preferredTarget);
      logPreferredTargetSelection(
          preferredTarget,
          signature,
          describeSelectionReason(filteredTargets, preferredTarget, signature),
          false,
          totalWindows.get(),
          sampleTitles);
      return preferredTarget.getHwnd();
    }

    logger.warn(
        "No matching window found. Enumerated {} total windows. Sample titles: {}",
        totalWindows.get(),
        sampleTitles);

    // Tier 3: Final fallback - get first Java window (manual selection via UI/API preferred)
    HWND javaWindow = getFirstJavaWindow();
    if (javaWindow != null) {
      logger.info("Using fallback Java window");
    } else {
      logger.error("No Java window found either - is RuneLite/Leeuwbot/VitaLite running?");
    }
    return javaWindow;
  }

  /**
   * Searches for the N-th child window of a given parent window that matches the specified class
   * name.
   *
   * <p>This is useful when multiple child windows share the same class (e.g., RuneLite has two
   * SunAwtCanvas children: the outer canvas with black bars and the inner canvas with the game
   * viewport). By specifying an index, you can reliably retrieve the intended child.
   *
   * @param parent the {@link HWND} of the parent window whose children are to be enumerated
   * @param className the class name of the child windows to match (e.g., "SunAwtCanvas")
   * @param n the 1-based index of the matching child window to retrieve (1 = first match)
   * @return the {@link HWND} of the N-th matching child window, or {@code null} if no such child
   *     exists
   */
  public static HWND findNthChildWindow(HWND parent, String className, int n) {
    AtomicReference<HWND> result = new AtomicReference<>();
    User32 user32 = User32.INSTANCE;
    int[] count = {0};

    user32.EnumChildWindows(
        parent,
        (hwnd, data) -> {
          byte[] buffer = new byte[512];
          user32.GetClassNameA(hwnd, buffer, buffer.length);
          String clsName = Native.toString(buffer).trim();

          if (clsName.equals(className)) {
            count[0]++;
            if (count[0] == n) {
              result.set(hwnd);
              return false; // stop enumeration
            }
          }
          return true; // continue enumeration
        },
        null);

    return result.get(); // may be null if no match found
  }

  private static int countChildWindowsByClass(HWND parent, String className) {
    if (parent == null || className == null || className.isBlank()) {
      return 0;
    }

    AtomicInteger count = new AtomicInteger();
    User32 user32 = User32.INSTANCE;
    user32.EnumChildWindows(
        parent,
        (hwnd, data) -> {
          byte[] buffer = new byte[512];
          user32.GetClassNameA(hwnd, buffer, buffer.length);
          String clsName = Native.toString(buffer).trim();
          if (className.equals(clsName)) {
            count.incrementAndGet();
          }
          return true;
        },
        null);

    return count.get();
  }

  /**
   * Retrieves the process ID associated with a given window handle.
   *
   * @param hwnd The handle of the target window.
   * @return The process ID (PID) of the window's owning process.
   */
  public static int getPid(HWND hwnd) {
    IntByReference pid = new IntByReference();
    User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
    return pid.getValue();
  }

  /**
   * Returns whether a window is a supported standalone game client window.
   *
   * @param window the window to evaluate
   * @return true if this window should be considered for selection/capture
   */
  static boolean isGameClientWindow(WindowInfo window) {
    if (window == null) {
      return false;
    }

    String title = window.getTitle();
    Rectangle bounds = window.getBounds();
    if (title == null || bounds == null) {
      return false;
    }

    if (bounds.width < 400 || bounds.height < 400) {
      return false;
    }

    if (!ProcessVerificationUtil.isStandaloneJavaProcess(window.getPid())) {
      return false;
    }

    String titleLower = title.toLowerCase();
    if (!titleLower.contains("runelite")
        && !titleLower.contains("leeuwbot")
        && !titleLower.contains("vitalite")) {
      return false;
    }

    return !titleLower.contains("inspector") && !titleLower.contains("shell");
  }

  /**
   * Enumerates all RuneLite/Leeuwbot/VitaLite windows currently running on the system.
   *
   * <p>This method scans all top-level windows and returns information about each window whose
   * title matches the pattern for supported clients.
   *
   * @return A list of WindowInfo objects containing details about each detected window
   */
  public static List<WindowInfo> getAllTargetWindows() {
    List<WindowInfo> windows = new ArrayList<>();
    User32 user32 = User32.INSTANCE;
    AtomicInteger index = new AtomicInteger(1);

    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] buffer = new byte[512];
          user32.GetWindowTextA(hwnd, buffer, 512);
          String title = Native.toString(buffer).trim();

          // Check if title matches any supported client (RuneLite, Leeuwbot, VitaLite)
          String titleLower = title.toLowerCase();
          String matchedClient = null;
          for (String client : SUPPORTED_CLIENTS) {
            if (titleLower.contains(client.toLowerCase())) {
              matchedClient = client;
              break;
            }
          }

          if (matchedClient != null) {
            // Extract account name from title (format: "Client - accountname" or just "Client")
            String accountName = null;
            int clientIndex = titleLower.indexOf(matchedClient.toLowerCase());
            if (clientIndex >= 0) {
              String suffix = title.substring(clientIndex + matchedClient.length()).trim();
              if (suffix.startsWith("-")) {
                accountName = suffix.substring(1).trim();
              }
            }

            // Get process ID
            int pid = getPid(hwnd);

            // Get window bounds
            Rectangle bounds = getWindowBounds(user32, hwnd);

            // Create WindowInfo with sequential ID
            WindowInfo windowInfo =
                new WindowInfo(String.valueOf(index.get()), hwnd, title, accountName, pid, bounds);
            windows.add(windowInfo);
            index.incrementAndGet();
          }
          return true; // continue enumeration
        },
        null);

    return windows;
  }

  /**
   * Returns the list of game client windows that should be exposed to the UI and selection API.
   *
   * <p>When a single process exposes both wrapper and render windows, this keeps the best capture
   * candidate for that PID rather than the largest top-level window.
   *
   * @return selectable target windows, one preferred candidate per PID
   */
  public static List<WindowInfo> getSelectableTargetWindows() {
    List<WindowInfo> filtered =
        getAllTargetWindows().stream().filter(WindowHandler::isGameClientWindow).toList();
    return deduplicateWindowsByPid(filtered);
  }

  /**
   * Helper method to get the bounds (rectangle) of a window.
   *
   * @param user32 The User32 interface
   * @param hwnd The window handle
   * @return The window bounds as a Rectangle
   */
  private static Rectangle getWindowBounds(User32 user32, HWND hwnd) {
    RECT rect = new RECT();
    user32.GetWindowRect(hwnd, rect);
    return new Rectangle(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
  }

  /**
   * Enumerates all Java/AWT windows currently running on the system.
   *
   * <p>This is more flexible than {@link #getAllTargetWindows()} as it detects any Java/AWT window
   * by checking window class names (e.g., "SunAwtFrame", "DlgFrame"), not just RuneLite windows.
   *
   * @return A list of WindowInfo objects containing details about each Java window
   */
  public static List<WindowInfo> getAllJavaWindows() {
    List<WindowInfo> windows = new ArrayList<>();
    User32 user32 = User32.INSTANCE;
    AtomicInteger index = new AtomicInteger(1);

    user32.EnumWindows(
        (hwnd, arg) -> {
          byte[] titleBuffer = new byte[512];
          user32.GetWindowTextA(hwnd, titleBuffer, 512);
          String title = Native.toString(titleBuffer).trim();

          byte[] classBuffer = new byte[512];
          user32.GetClassNameA(hwnd, classBuffer, 512);
          String className = Native.toString(classBuffer).trim();

          // Detect Java/AWT windows - they have class names like "SunAwtFrame", "DlgFrame", etc.
          // Also include RuneLite, Leeuwbot, and VitaLite windows for backward compatibility
          boolean isJavaWindow =
              className.contains("Awt") || className.contains("Sun") || className.contains("awt");

          // Check if title matches any supported client
          boolean isSupportedClient = false;
          String titleLower = title.toLowerCase();
          for (String client : SUPPORTED_CLIENTS) {
            if (titleLower.contains(client.toLowerCase())) {
              isSupportedClient = true;
              break;
            }
          }

          if ((isJavaWindow || isSupportedClient) && !title.isEmpty()) {
            int pid = getPid(hwnd);
            Rectangle bounds = getWindowBounds(user32, hwnd);

            WindowInfo windowInfo =
                new WindowInfo(
                    String.valueOf(index.get()),
                    hwnd,
                    title,
                    null, // No account name for generic Java windows
                    pid,
                    bounds);
            windows.add(windowInfo);
            index.incrementAndGet();
          }
          return true;
        },
        null);

    return windows;
  }

  /**
   * Gets the first available Java window for auto-detection fallback.
   *
   * <p>This is used as a last resort when the exact RuneLite window cannot be found.
   *
   * @return The HWND of the first Java window, or null if none found
   */
  public static HWND getFirstJavaWindow() {
    List<WindowInfo> windows = getAllJavaWindows();
    return windows.isEmpty() ? null : windows.get(0).getHwnd();
  }

  /**
   * Deduplicates windows by PID, keeping the best capture candidate per process.
   *
   * <p>This is useful when a client (like Leeuwbot) creates multiple windows for the same process.
   * We prefer the window most likely to contain the actual render canvas rather than the largest
   * wrapper window.
   *
   * @param windows the list of windows to deduplicate
   * @return a new list with one preferred window per PID, sorted by original ID order
   */
  public static List<WindowInfo> deduplicateWindowsByPid(List<WindowInfo> windows) {
    return deduplicateWindowsByPid(windows, WindowHandler::detectCaptureSignature);
  }

  static List<WindowInfo> deduplicateWindowsByPid(
      List<WindowInfo> windows, Predicate<WindowInfo> captureDetector) {
    Function<WindowInfo, CaptureSignature> signatureProvider =
        window ->
            captureDetector.test(window)
                ? new CaptureSignature(1, false, windowArea(window))
                : new CaptureSignature(0, false, windowArea(window));
    return deduplicateWindowsByPid(windows, signatureProvider);
  }

  static List<WindowInfo> deduplicateWindowsByPid(
      List<WindowInfo> windows, Function<WindowInfo, CaptureSignature> captureSignatureProvider) {
    if (windows == null || windows.isEmpty()) {
      return windows;
    }

    // Group windows by PID
    Map<Integer, List<WindowInfo>> windowsByPid =
        windows.stream().collect(Collectors.groupingBy(WindowInfo::getPid));

    // For each PID, keep only the best capture target for that process.
    List<WindowInfo> deduplicated = new ArrayList<>();
    for (List<WindowInfo> pidWindows : windowsByPid.values()) {
      WindowInfo bestWindow = selectBestWindow(pidWindows, captureSignatureProvider);
      if (bestWindow != null) {
        deduplicated.add(bestWindow);
      }
    }

    // Sort by original ID order
    return deduplicated.stream()
        .sorted(Comparator.comparingInt(w -> Integer.parseInt(w.getId())))
        .collect(Collectors.toList());
  }

  static WindowInfo selectBestWindow(
      List<WindowInfo> windows, Predicate<WindowInfo> captureDetector) {
    Function<WindowInfo, CaptureSignature> signatureProvider =
        window ->
            captureDetector != null && captureDetector.test(window)
                ? new CaptureSignature(1, false, windowArea(window))
                : new CaptureSignature(0, false, windowArea(window));
    return selectBestWindow(windows, signatureProvider);
  }

  static WindowInfo selectBestWindow(
      List<WindowInfo> windows, Function<WindowInfo, CaptureSignature> captureSignatureProvider) {
    if (windows == null || windows.isEmpty()) {
      return null;
    }

    Function<WindowInfo, CaptureSignature> signatureSource =
        captureSignatureProvider != null
            ? captureSignatureProvider
            : WindowHandler::detectCaptureSignature;
    Map<WindowInfo, CaptureSignature> signatureCache = new IdentityHashMap<>();
    Function<WindowInfo, CaptureSignature> signatures =
        window -> signatureCache.computeIfAbsent(window, signatureSource);

    boolean hasCaptureableCandidates =
        windows.stream().anyMatch(window -> signatures.apply(window).canvasCount() > 0);
    List<WindowInfo> captureableCandidates = preferCaptureableCandidates(windows, signatures);
    List<WindowInfo> candidates =
        hasCaptureableCandidates ? captureableCandidates : preferRenderableCandidates(windows);

    Comparator<WindowInfo> comparator =
        hasCaptureableCandidates
            ? Comparator.comparingInt((WindowInfo window) -> signatures.apply(window).canvasPriority())
                .thenComparingInt(window -> signatures.apply(window).canvasCount())
                .thenComparingInt(WindowHandler::renderClientPriority)
                .thenComparingInt(WindowHandler::clientTitlePriority)
                .thenComparingInt(window -> signatures.apply(window).area())
            : Comparator.comparingInt((WindowInfo window) -> renderClientPriority(window))
                .thenComparingInt(WindowHandler::clientTitlePriority)
                .thenComparingInt(window -> signatures.apply(window).area());

    return candidates.stream().max(comparator).orElse(null);
  }

  private static List<WindowInfo> preferCaptureableCandidates(
      List<WindowInfo> windows, Function<WindowInfo, CaptureSignature> signatures) {
    List<WindowInfo> captureableCandidates =
        windows.stream().filter(window -> signatures.apply(window).canvasCount() > 0).toList();
    return captureableCandidates.isEmpty() ? windows : captureableCandidates;
  }

  private static List<WindowInfo> preferRenderableCandidates(List<WindowInfo> windows) {
    List<WindowInfo> renderCandidates =
        windows.stream()
            .filter(window -> clientRole(window) == ClientWindowRole.RENDER_CLIENT)
            .toList();
    return renderCandidates.isEmpty() ? windows : renderCandidates;
  }

  private static CaptureSignature detectCaptureSignature(WindowInfo window) {
    if (window == null || window.getHwnd() == null) {
      return new CaptureSignature(0, false, windowArea(window));
    }

    int canvasCount = countChildWindowsByClass(window.getHwnd(), CANVAS_CLASS_NAME);
    return new CaptureSignature(canvasCount, canvasCount >= 2, windowArea(window));
  }

  private static int renderClientPriority(WindowInfo window) {
    return switch (clientRole(window)) {
      case RENDER_CLIENT -> 2;
      case WRAPPER -> 1;
      case OTHER -> 0;
    };
  }

  private static ClientWindowRole clientRole(WindowInfo window) {
    if (window == null || window.getTitle() == null) {
      return ClientWindowRole.OTHER;
    }

    String titleLower = window.getTitle().toLowerCase();
    if (titleLower.contains("runelite") || titleLower.contains("vitalite")) {
      return ClientWindowRole.RENDER_CLIENT;
    }
    if (titleLower.contains("leeuwbot")) {
      return ClientWindowRole.WRAPPER;
    }
    return ClientWindowRole.OTHER;
  }

  private static int clientTitlePriority(WindowInfo window) {
    if (window == null || window.getTitle() == null) {
      return 0;
    }

    String titleLower = window.getTitle().toLowerCase();
    if (titleLower.contains("runelite")) {
      return 3;
    }
    if (titleLower.contains("vitalite")) {
      return 2;
    }
    if (titleLower.contains("leeuwbot")) {
      return 1;
    }
    return 0;
  }

  private static int windowArea(WindowInfo window) {
    if (window == null || window.getBounds() == null) {
      return 0;
    }
    return window.getBounds().width * window.getBounds().height;
  }

  private static String describeSelectionReason(
      List<WindowInfo> candidates, WindowInfo selectedWindow, CaptureSignature signature) {
    if (selectedWindow == null) {
      return "no selection";
    }

    if (!preferCaptureableCandidates(candidates, WindowHandler::detectCaptureSignature)
        .equals(candidates)) {
      return "captureable window preferred over zero-canvas sibling";
    }
    if (!preferRenderableCandidates(candidates).equals(candidates)) {
      return "render-client preferred over wrapper sibling";
    }
    if (signature.hasSecondaryCanvas()) {
      return "secondary SunAwtCanvas available";
    }
    if (signature.canvasCount() > 0) {
      return "direct capture canvas available";
    }
    return "title/size fallback";
  }

  private static void logPreferredTargetSelection(
      WindowInfo window,
      CaptureSignature signature,
      String reason,
      boolean exactMatch,
      int totalWindows,
      List<String> sampleTitles) {
    String logKey =
        (window != null ? window.getPid() : -1)
            + "|"
            + (window != null ? window.getTitle() : "<none>")
            + "|"
            + reason
            + "|"
            + signature.canvasCount()
            + "|"
            + signature.hasSecondaryCanvas();

    String previous = lastPreferredWindowLogKey.getAndSet(logKey);
    if (!logKey.equals(previous)) {
      logger.info(
          "Preferred target window changed: title='{}', pid={}, role={}, canvases={}, secondaryCanvas={}, exactMatch={}, reason={}, enumeratedWindows={}, sampleTitles={}",
          window != null ? window.getTitle() : "<none>",
          window != null ? window.getPid() : -1,
          window != null ? clientRole(window) : ClientWindowRole.OTHER,
          signature.canvasCount(),
          signature.hasSecondaryCanvas(),
          exactMatch,
          reason,
          totalWindows,
          sampleTitles);
    }
  }

  private enum ClientWindowRole {
    RENDER_CLIENT,
    WRAPPER,
    OTHER
  }

  record CaptureSignature(int canvasCount, boolean hasSecondaryCanvas, int area) {
    int canvasPriority() {
      if (hasSecondaryCanvas) {
        return 2;
      }
      return canvasCount > 0 ? 1 : 0;
    }
  }
}
