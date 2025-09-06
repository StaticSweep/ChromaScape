package com.chromascape.utils.domain.zones;

import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * Manages the detection and mapping of key UI zones within the RuneLite client window, including
 * the minimap, control panel, chat tabs, and inventory slots.
 *
 * <p>Supports both fixed and resizable window modes, adjusting the mapped regions accordingly. Uses
 * template matching to locate UI elements within the game window for accurate zone detection.
 */
public class ZoneManager {

  /** Flag indicating whether the client window is in fixed (non-resizable) mode. */
  private final boolean isFixed;

  /** Map of minimap subcomponent names to their bounding rectangles. */
  private Map<String, Rectangle> minimap;

  /** Map of control panel tab names to their bounding rectangles. */
  private Map<String, Rectangle> ctrlPanel;

  /** Map of chat tab names to their bounding rectangles. */
  private Map<String, Rectangle> chatTabs;

  /** List of rectangles representing individual inventory slot locations. */
  private List<Rectangle> inventorySlots;

  /** Map of Rectangles defining the grid info box's location info. */
  private Map<String, Rectangle> gridInfo;

  /** Rectangle defining the location of the mouse-over text. */
  private Rectangle mouseOver;

  /** File paths to template images used for UI element detection. */
  private final String[] zoneTemplates = {
    "/images/ui/minimap.png",
    "/images/ui/inv.png",
    "/images/ui/chat.png",
    "/images/ui/minimap_fixed.png"
  };

  /** Threshold values corresponding to template matching sensitivity for each UI element. */
  private final double[] zoneThresholds = {0.025, 0.100, 0.035, 0.020};

  /**
   * Constructs a new ZoneManager configured for either fixed or resizable mode.
   *
   * @param isFixed true if the client is in fixed mode, false otherwise.
   */
  public ZoneManager(boolean isFixed) {
    this.isFixed = isFixed;
    mapper();
  }

  /**
   * Performs template matching to locate UI elements and maps their respective zones.
   *
   * <p>Populates the minimap, control panel, chat tabs, and inventory slots with their bounding
   * rectangles based on current window mode.
   *
   * <p>Any exceptions during mapping are caught and logged to standard error.
   */
  public void mapper() {
    try {
      // The chat's location is used to define the location of certain elements
      Rectangle chatLocation = locateUiElement(zoneTemplates[2], zoneThresholds[2]);
      chatTabs = SubZoneMapper.mapChat(chatLocation);

      ctrlPanel = SubZoneMapper.mapCtrlPanel(locateUiElement(zoneTemplates[1], zoneThresholds[1]));

      inventorySlots =
          SubZoneMapper.mapInventory(locateUiElement(zoneTemplates[1], zoneThresholds[1]));
      // The minimap's location is used in conjunction to the chat's location to define certain
      // elements
      if (isFixed) {
        Rectangle minimapLocation = locateUiElement(zoneTemplates[3], zoneThresholds[3]);
        minimap = SubZoneMapper.mapFixedMinimap(minimapLocation);
        mouseOver = new Rectangle(chatLocation.x + 1, minimapLocation.y + 3, 407, 26);
        gridInfo =
            SubZoneMapper.mapGridInfo(
                new Rectangle(chatLocation.x + 6, minimapLocation.y + 23, 129, 56));
      } else {
        Rectangle minimapLocation = locateUiElement(zoneTemplates[0], zoneThresholds[0]);
        minimap = SubZoneMapper.mapMinimap(minimapLocation);
        mouseOver = new Rectangle(chatLocation.x - 3, minimapLocation.y - 2, 407, 26);
        gridInfo =
            SubZoneMapper.mapGridInfo(
                new Rectangle(chatLocation.x + 2, minimapLocation.y + 18, 129, 56));
      }
    } catch (Exception e) {
      System.err.println("[ZoneManager] Mapping failed: " + e.getMessage());
    }
  }

  /**
   * Captures a screenshot of the current game viewport area.
   *
   * <p>Captures the full window and masks out UI zones such as minimap, control panel, and chat to
   * isolate the game viewport.
   *
   * <p>You are intended to use template matching on this image directly for sprite matching You are
   * also intended to use this as the image for colour detection.
   *
   * @return A {@link BufferedImage} representing the game viewport screenshot.
   */
  public BufferedImage getGameView() throws Exception {
    BufferedImage gameViewMask = ScreenManager.captureWindow();

    if (isFixed) {
      // inv (1), chat (2), minimap_fixed (3)
      int[] fixedIndices = {1, 2, 3};
      for (int i : fixedIndices) {
        Rectangle element = locateUiElement(zoneTemplates[i], zoneThresholds[i]);
        gameViewMask = MaskZones.maskZones(gameViewMask, ScreenManager.toClientBounds(element));
      }
    } else {
      // inv (1), chat (2), minimap (0)
      int[] resizableIndices = {1, 2, 0};
      for (int i : resizableIndices) {
        Rectangle element = locateUiElement(zoneTemplates[i], zoneThresholds[i]);
        gameViewMask = MaskZones.maskZones(gameViewMask, ScreenManager.toClientBounds(element));
      }
    }

    return gameViewMask;
  }

  /**
   * Locates the bounding rectangle of a UI element by matching a template image within the current
   * game window capture.
   *
   * @param templatePath The file path to the template image to match.
   * @param threshold The matching threshold (lower values mean stricter matching).
   * @return A {@link Rectangle} representing the bounds of the matched UI element.
   * @throws Exception if the template matching fails or no match is found.
   */
  public Rectangle locateUiElement(String templatePath, double threshold) throws Exception {
    return TemplateMatching.match(templatePath, ScreenManager.captureWindow(), threshold, false);
  }

  /**
   * Returns the map of minimap zones and their bounding rectangles. See {@link SubZoneMapper} for
   * keys.
   *
   * @return A map where keys are minimap component names and values are their rectangles.
   */
  public Map<String, Rectangle> getMinimap() {
    return minimap;
  }

  /**
   * Returns the map of control panel tabs and their bounding rectangles. See {@link SubZoneMapper}
   * for keys.
   *
   * @return A map where keys are control panel tab names and values are their rectangles.
   */
  public Map<String, Rectangle> getCtrlPanel() {
    return ctrlPanel;
  }

  /**
   * Returns the map of chat tabs and their bounding rectangles. See {@link SubZoneMapper} for keys.
   *
   * @return A map where keys are chat tab names and values are their rectangles.
   */
  public Map<String, Rectangle> getChatTabs() {
    return chatTabs;
  }

  /**
   * Returns the list of rectangles corresponding to each inventory slot. You are intended to use
   * {@link ScreenManager} to take screenshots and template match against them. These slots are
   * mapped 0-27, left to right - top to bottom.
   *
   * @return A list of {@link Rectangle} objects representing inventory slot bounds.
   */
  public List<Rectangle> getInventorySlots() {
    return inventorySlots;
  }

  /**
   * Returns the list of rectangles corresponding to fields in the Grid info area that contain
   * location data. Useful for knowing the player's location in the game. Meant to be used by the
   * Walker utility.
   *
   * @return {@link Rectangle} of the Grid info area.
   */
  public Map<String, Rectangle> getGridInfo() {
    return gridInfo;
  }

  /**
   * Returns the mouse-over zone, where text will show if the user hovers over an interactable
   * object. Intended to be used alongside the OCR engine.
   *
   * @return {@link Rectangle} of the mouse-over area.
   */
  public Rectangle getMouseOver() {
    return mouseOver;
  }

  /**
   * {@link Boolean} defining whether the client is in fixed or resizable mode.
   *
   * @return True if fixed, false if resizable.
   */
  public boolean getIsFixed() {
    return isFixed;
  }
}
