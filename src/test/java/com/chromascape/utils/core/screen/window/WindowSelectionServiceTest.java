package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.platform.win32.WinDef.HWND;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WindowSelectionServiceTest {

  private static final Instant START = Instant.ofEpochMilli(9_000L);

  private static WindowInfo neutralWindow(ProcessIdentity identity, String id) {
    return new WindowInfo(id, null, null, null, (int) identity.pid(), false, null, identity);
  }

  @Test
  void generationIncrementsOnSelectAndClear() {
    WindowSelectionService service = new WindowSelectionService();
    assertEquals(0L, service.getSelectedGeneration());

    service.setSelectedWindow(
        neutralWindow(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1"), "1"));
    assertEquals(1L, service.getSelectedGeneration());

    service.setSelectedWindow(
        neutralWindow(new ProcessIdentity(ProcessOrigin.LOCAL, "", 11L, START, "boot-1"), "2"));
    assertEquals(2L, service.getSelectedGeneration());

    service.clearSelection();
    assertEquals(3L, service.getSelectedGeneration());
    assertFalse(service.hasSelection());
    assertNull(service.getSelectedWindow());
  }

  @Test
  void isSelectedComparesNeutralIdentityAcrossReEnumeration() {
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(neutralWindow(identity, "1"));

    // A freshly enumerated but logically identical window must still be recognized as selected
    assertTrue(service.isSelected(neutralWindow(identity, "9")));
    assertFalse(
        service.isSelected(
            neutralWindow(
                new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START.plusSeconds(5), "boot-1"),
                "3")));
    assertFalse(
        service.isSelected(
            neutralWindow(
                new ProcessIdentity(ProcessOrigin.LOCAL, "", 11L, START, "boot-1"), "4")));
  }

  @Test
  void isSelectedFallsBackToReferenceEqualityWithoutIdentity() {
    WindowSelectionService service = new WindowSelectionService();
    WindowInfo first = new WindowInfo("1", null, "title", null, 10, true, null);
    WindowInfo second = new WindowInfo("1", null, "title", null, 10, true, null);
    service.setSelectedWindow(first);

    assertTrue(service.isSelected(first));
    // Pre-SC-1663 behavior preserved when neither side carries an identity
    assertFalse(service.isSelected(second));
  }

  @Test
  void isSelectedIsFalseWhenOnlyOneSideCarriesAnIdentity() {
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(neutralWindow(identity, "1"));

    assertFalse(service.isSelected(new WindowInfo("1", null, "title", null, 10, true, null)));
  }

  @Test
  void hwndAdapterYieldsNullForNeutralSelections() {
    ProcessIdentity identity = new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1");
    WindowSelectionService service = new WindowSelectionService();
    service.setSelectedWindow(neutralWindow(identity, "1"));

    assertNull(service.getSelectedHwnd());
    assertSame(identity, service.getSelectedIdentity());
  }

  @Test
  void hwndAdapterReturnsTheSelectedHandleWhenPresent() {
    WindowSelectionService service = new WindowSelectionService();
    HWND hwnd = new HWND();
    service.setSelectedWindow(new WindowInfo("1", hwnd, "title", null, 10, true, null));

    assertSame(hwnd, service.getSelectedHwnd());
    assertNull(service.getSelectedIdentity());
  }

  @Test
  void selectedWindowAccessorKeepsItsContract() {
    WindowSelectionService service = new WindowSelectionService();
    WindowInfo window =
        neutralWindow(new ProcessIdentity(ProcessOrigin.LOCAL, "", 10L, START, "boot-1"), "1");
    service.setSelectedWindow(window);

    assertSame(window, service.getSelectedWindow());
    assertTrue(service.hasSelection());
    service.clearSelection();
    assertNull(service.getSelectedWindow());
    assertFalse(service.hasSelection());
  }
}
