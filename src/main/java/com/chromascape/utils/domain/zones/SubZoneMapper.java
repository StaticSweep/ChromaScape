package com.chromascape.utils.domain.zones;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for mapping UI component zones within the client window.
 *
 * <p>Provides methods to derive sub-zones (like orbs, tabs, inventory slots) from known UI
 * containers such as the minimap, chatbox, control panel, and inventory, using fixed offsets for
 * consistent bounding boxes.
 */
public class SubZoneMapper {

  /**
   * Maps all major UI components in or derived solely from the resizable mode minimap area.
   *
   * @param zone The base minimap zone.
   * @return A map of component names to {@link Rectangle} bounds.
   */
  public static Map<String, Rectangle> mapMinimap(Rectangle zone) {
    if (zone != null) {

      Map<String, Rectangle> minimap = new HashMap<>();

      minimap.put("compass", new Rectangle(zone.x + 40, zone.y + 7, 24, 26));
      minimap.put("hpText", new Rectangle(zone.x + 4, zone.y + 60, 20, 13));
      minimap.put("prayerOrb", new Rectangle(zone.x + 30, zone.y + 86, 20, 20));
      minimap.put("prayerText", new Rectangle(zone.x + 4, zone.y + 94, 20, 13));
      minimap.put("runOrb", new Rectangle(zone.x + 39, zone.y + 118, 20, 20));
      minimap.put("runText", new Rectangle(zone.x + 14, zone.y + 126, 20, 13));
      minimap.put("specOrb", new Rectangle(zone.x + 62, zone.y + 144, 18, 20));
      minimap.put("specText", new Rectangle(zone.x + 36, zone.y + 151, 20, 13));
      minimap.put("minimap", new Rectangle(zone.x + 52, zone.y + 5, 154, 155));
      minimap.put("totalXP", new Rectangle(zone.x - 147, zone.y + 4, 104, 21));
      minimap.put("playerPos", new Rectangle(zone.x + 127, zone.y + 80, 4, 4));
      minimap.put("compassSimilarity", new Rectangle(zone.x + 33, zone.y + 2, 37, 37));
      return minimap;
    } else {
      System.out.println("No minimap found");
      return null;
    }
  }

  /**
   * Maps UI components in or derived solely from the fixed (non-resizable) mode minimap.
   *
   * @param zone The base minimap zone.
   * @return A map of minimap component rectangles.
   */
  public static Map<String, Rectangle> mapFixedMinimap(Rectangle zone) {
    if (zone != null) {

      Map<String, Rectangle> minimap = new HashMap<>();

      minimap.put("compass", new Rectangle(zone.x + 31, zone.y + 7, 24, 25));
      minimap.put("hpText", new Rectangle(zone.x + 4, zone.y + 55, 20, 13));
      minimap.put("minimap", new Rectangle(zone.x + 52, zone.y + 4, 147, 160));
      minimap.put("prayerOrb", new Rectangle(zone.x + 30, zone.y + 80, 19, 20));
      minimap.put("prayerText", new Rectangle(zone.x + 4, zone.y + 89, 20, 13));
      minimap.put("runOrb", new Rectangle(zone.x + 40, zone.y + 112, 19, 20));
      minimap.put("runText", new Rectangle(zone.x + 14, zone.y + 121, 20, 13));
      minimap.put("specOrb", new Rectangle(zone.x + 62, zone.y + 137, 19, 20));
      minimap.put("specText", new Rectangle(zone.x + 36, zone.y + 146, 20, 13));
      minimap.put("totalXP", new Rectangle(zone.x - 104, zone.y + 6, 104, 21));
      minimap.put("playerPos", new Rectangle(zone.x + 123, zone.y + 82, 4, 4));
      minimap.put("compassSimilarity", new Rectangle(zone.x + 27, zone.y + 2, 34, 35));
      return minimap;
    } else {
      System.out.println("No fixed minimap found");
      return null;
    }
  }

  /**
   * Maps control panel UI tab buttons and the inventory area.
   *
   * @param zone The bounding zone for the control panel.
   * @return A map of control panel component rectangles.
   */
  public static Map<String, Rectangle> mapCtrlPanel(Rectangle zone) {
    if (zone != null) {

      Map<String, Rectangle> ctrlPanel = new HashMap<>();
      // Top row
      ctrlPanel.put("combatTab", new Rectangle(zone.x + 7, zone.y + 6, 26, 24));
      ctrlPanel.put("skillsTab", new Rectangle(zone.x + 41, zone.y + 2, 26, 28));
      ctrlPanel.put("summaryTab", new Rectangle(zone.x + 74, zone.y + 2, 26, 28));
      ctrlPanel.put("inventoryTab", new Rectangle(zone.x + 107, zone.y + 2, 26, 28));
      ctrlPanel.put("equipmentTab", new Rectangle(zone.x + 140, zone.y + 2, 26, 28));
      ctrlPanel.put("prayerTab", new Rectangle(zone.x + 173, zone.y + 2, 26, 28));
      ctrlPanel.put("spellbookTab", new Rectangle(zone.x + 206, zone.y + 6, 27, 24));

      // Bottom row
      ctrlPanel.put("channelTab", new Rectangle(zone.x + 7, zone.y + 300, 28, 25));
      ctrlPanel.put("friendsTab", new Rectangle(zone.x + 41, zone.y + 300, 26, 30));
      ctrlPanel.put("accountTab", new Rectangle(zone.x + 74, zone.y + 300, 26, 30));
      ctrlPanel.put("logoutTab", new Rectangle(zone.x + 107, zone.y + 300, 26, 30));
      ctrlPanel.put("settingsTab", new Rectangle(zone.x + 140, zone.y + 300, 26, 30));
      ctrlPanel.put("emotesTab", new Rectangle(zone.x + 173, zone.y + 300, 26, 30));
      ctrlPanel.put("musicTab", new Rectangle(zone.x + 206, zone.y + 300, 27, 25));

      // Main inventory area
      ctrlPanel.put("inventoryPanel", new Rectangle(zone.x + 28, zone.y + 35, 183, 261));
      return ctrlPanel;
    } else {
      System.out.println("No ctrlPanel found");
      return null;
    }
  }

  /**
   * Maps the layout of the chat tabs and main chat display area.
   *
   * @param zone The bounding box for the chat region.
   * @return A map of tab names and their rectangles.
   */
  public static Map<String, Rectangle> mapChat(Rectangle zone) {
    if (zone != null) {

      Map<String, Rectangle> chatTabs = new HashMap<>();

      String[] tabNames = {"All", "Game", "Public", "Private", "Channel", "Clan", "Group"};

      int x = 5;
      int y = 143;
      for (int i = 0; i < 7; i++) {
        chatTabs.put(tabNames[i], new Rectangle(zone.x + x, zone.y + y, 52, 19));
        x += 62;
      }
      chatTabs.put("Chat", new Rectangle(zone.x + 5, zone.y + 5, 506, 129));
      return chatTabs;
    } else {
      System.out.println("No Chat found");
      return null;
    }
  }

  /**
   * Maps out the three fields contained in the Grid Info box. These fields are meant to be used
   * with OCR to extract player location data.
   *
   * @param zone The bounding box of the parent zone. (Where the box is).
   * @return A list of {@link Rectangle} subzones (Tile, ChunkID, RegionID).
   */
  public static Map<String, Rectangle> mapGridInfo(Rectangle zone) {
    if (zone != null) {
      Map<String, Rectangle> gridInfo = new HashMap<>();
      gridInfo.put("Tile", new Rectangle(zone.x + 39, zone.y, 89, 22));
      gridInfo.put("ChunkID", new Rectangle(zone.x + 74, zone.y + 20, 54, 19));
      gridInfo.put("RegionID", new Rectangle(zone.x + 84, zone.y + 36, 45, 19));
      return gridInfo;
    } else {
      System.out.println("No Grid found");
      return null;
    }
  }

  /**
   * Generates bounding rectangles for each inventory slot in a 4x7 grid.
   *
   * @param zone The top-left bounding box of the inventory panel.
   * @return A list of inventory slot rectangles.
   */
  public static List<Rectangle> mapInventory(Rectangle zone) {
    if (zone != null) {

      List<Rectangle> inventorySlots = new ArrayList<>();

      int slotWidth = 36;
      int slotHeight = 32;
      int gapX = 6;
      int gapY = 4;

      int y = zone.y + 44;
      for (int i = 0; i < 7; i++) {
        int x = zone.x + 40;
        for (int j = 0; j < 4; j++) {
          inventorySlots.add(new Rectangle(x, y, slotWidth, slotHeight));
          x += slotWidth + gapX;
        }
        y += slotHeight + gapY;
      }
      return inventorySlots;
    } else {
      System.out.println("No Inventory found");
      return null;
    }
  }
}
