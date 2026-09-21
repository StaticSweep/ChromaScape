package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class WindowHandlerTest {

  @Test
  void extractAccountNameSupportsVersionedLeeuwbotTitles() {
    assertEquals(
        "bigGreenMeat",
        WindowHandler.extractAccountName("Leeuwbot V2.0.61 - bigGreenMeat", "Leeuwbot"));
    assertEquals("alpha", WindowHandler.extractAccountName("RuneLite - alpha", "RuneLite"));
    assertEquals(
        "bigGreenMeat", WindowHandler.extractAccountName("Leeuwbot - bigGreenMeat", "Leeuwbot"));
    assertNull(WindowHandler.extractAccountName("Leeuwbot V2.0.61", "Leeuwbot"));
  }

  @Test
  void selectBestWindowPrefersCaptureableRuneliteWindowOverLargerLeeuwbotWrapper() {
    WindowInfo wrapper = window("1", "Leeuwbot V2.0.61 - bigGreenMeat", 26304, 850, 542);
    WindowInfo runelite = window("3", "- RuneLite", 26304, 495, 518);

    WindowInfo best =
        WindowHandler.selectBestWindow(
            List.of(wrapper, runelite),
            (Predicate<WindowInfo>) window -> "3".equals(window.getId()));

    assertEquals("3", best.getId());
    assertEquals("- RuneLite", best.getTitle());
  }

  @Test
  void selectBestWindowFallsBackToRuneliteTitleWhenNoCanvasIsDetected() {
    WindowInfo wrapper = window("1", "Leeuwbot V2.0.61 - bigGreenMeat", 26304, 850, 542);
    WindowInfo runelite = window("3", "- RuneLite", 26304, 495, 518);

    WindowInfo best =
        WindowHandler.selectBestWindow(
            List.of(wrapper, runelite), (Predicate<WindowInfo>) window -> false);

    assertEquals("3", best.getId());
  }

  @Test
  void selectBestWindowPrefersVisibleWindowOverHiddenSibling() {
    WindowInfo hiddenWrapper = window("1", "Leeuwbot", 26304, false, 850, 542);
    WindowInfo visibleWrapper = window("2", "Leeuwbot", 26304, true, 850, 542);

    WindowInfo best =
        WindowHandler.selectBestWindow(
            List.of(hiddenWrapper, visibleWrapper), (Predicate<WindowInfo>) window -> false);

    assertEquals("2", best.getId());
  }

  @Test
  void selectBestWindowPrefersCaptureableWrapperOverZeroCanvasRenderClient() {
    WindowInfo wrapper = window("1", "Leeuwbot V2.0.61 - bigGreenMeat", 26304, 850, 542);
    WindowInfo runelite = window("3", "- RuneLite", 26304, 495, 518);

    WindowInfo best =
        WindowHandler.selectBestWindow(
            List.of(wrapper, runelite),
            (Function<WindowInfo, WindowHandler.CaptureSignature>)
                window ->
                    "1".equals(window.getId())
                        ? new WindowHandler.CaptureSignature(2, true, area(window))
                        : new WindowHandler.CaptureSignature(0, false, area(window)));

    assertEquals("1", best.getId());
    assertEquals("Leeuwbot V2.0.61 - bigGreenMeat", best.getTitle());
  }

  @Test
  void deduplicateWindowsByPidKeepsBestWindowPerProcess() {
    WindowInfo wrapper = window("1", "Leeuwbot V2.0.61 - bigGreenMeat", 26304, 850, 542);
    WindowInfo otherProcess = window("2", "RuneLite - second", 30000, 600, 600);
    WindowInfo runelite = window("3", "- RuneLite", 26304, 495, 518);

    List<WindowInfo> deduplicated =
        WindowHandler.deduplicateWindowsByPid(
            List.of(wrapper, otherProcess, runelite),
            (Predicate<WindowInfo>) window -> "3".equals(window.getId()));

    assertEquals(2, deduplicated.size());
    assertIterableEquals(List.of("2", "3"), deduplicated.stream().map(WindowInfo::getId).toList());
  }

  @Test
  void deduplicateWindowsByPidFallsBackToWrapperWhenNoRenderClientExists() {
    WindowInfo wrapper = window("1", "Leeuwbot V2.0.61 - bigGreenMeat", 26304, 850, 542);
    WindowInfo secondWrapper = window("2", "Leeuwbot V2.0.61 - alt", 26304, 700, 500);

    List<WindowInfo> deduplicated =
        WindowHandler.deduplicateWindowsByPid(
            List.of(wrapper, secondWrapper),
            (Function<WindowInfo, WindowHandler.CaptureSignature>)
                window ->
                    "2".equals(window.getId())
                        ? new WindowHandler.CaptureSignature(2, true, area(window))
                        : new WindowHandler.CaptureSignature(1, false, area(window)));

    assertEquals(1, deduplicated.size());
    assertEquals("2", deduplicated.get(0).getId());
  }

  @Test
  void selectBestWindowAmongRenderClientsUsesCaptureSignatureBeforeArea() {
    WindowInfo firstRunelite = window("1", "RuneLite - alpha", 26304, 850, 542);
    WindowInfo secondRunelite = window("2", "RuneLite - beta", 26305, 600, 500);

    WindowInfo best =
        WindowHandler.selectBestWindow(
            List.of(firstRunelite, secondRunelite),
            (Function<WindowInfo, WindowHandler.CaptureSignature>)
                window ->
                    "2".equals(window.getId())
                        ? new WindowHandler.CaptureSignature(2, true, area(window))
                        : new WindowHandler.CaptureSignature(1, false, area(window)));

    assertEquals("2", best.getId());
  }

  @Test
  void uiCandidatesFallBackToJavaWindowListWhenStrictTargetListIsEmpty() {
    WindowInfo wrapper = window("1", "Leeuwbot V2.0.61 - bigGreenMeat", 26304, 850, 542);

    List<WindowHandler.WindowCandidate> candidates =
        WindowHandler.buildUiWindowCandidates(List.of(), List.of(wrapper));

    assertEquals(1, candidates.size());
    assertEquals("1", candidates.get(0).window().getId());
    assertEquals("bigGreenMeat", candidates.get(0).window().getAccountName());
    assertEquals("java-fallback", candidates.get(0).source());
  }

  @Test
  void supportedClientTitleClassifierAcceptsLeeuwbotButRejectsBrowserTabs() {
    assertTrue(WindowHandler.isSupportedClientWindowTitle("Leeuwbot"));
    assertTrue(WindowHandler.isSupportedClientWindowTitle("Leeuwbot V2.0.61 - bigGreenMeat"));
    assertTrue(WindowHandler.isSupportedClientWindowTitle("- RuneLite"));
    assertTrue(WindowHandler.isSupportedClientWindowTitle("-  RuneLite"));
    assertTrue(WindowHandler.isSupportedClientWindowTitle("RuneLite  -  TheSomaliMan"));
    assertTrue(WindowHandler.isSupportedClientWindowTitle("Leeuwbot  V0.0.0"));
    assertFalse(
        WindowHandler.isSupportedClientWindowTitle(
            "Merge pull request #1811 from bgatfa/fix/walker-opens-nonblocking-doors · infuse21/Microbot@b0a4607 - Google Chrome"));
    assertFalse(
        WindowHandler.isSupportedClientWindowTitle("runelite – gradle.properties [runelite]"));
  }

  private static WindowInfo window(String id, String title, int pid, int width, int height) {
    return window(id, title, pid, true, width, height);
  }

  private static WindowInfo window(
      String id, String title, int pid, boolean visible, int width, int height) {
    String matchedClient = WindowHandler.findMatchedSupportedClient(title.toLowerCase());
    return new WindowInfo(
        id,
        null,
        title,
        WindowHandler.extractAccountName(title, matchedClient),
        pid,
        visible,
        new Rectangle(0, 0, width, height));
  }

  private static int area(WindowInfo window) {
    return window.getBounds().width * window.getBounds().height;
  }
}
