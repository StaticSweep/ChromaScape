package com.chromascape.utils.actions;

import com.chromascape.base.BaseScript;
import com.chromascape.controller.Controller;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An actions utility - a utility that does commonly repeated tasks found in bot scripts. This
 * utility provides functionality for dropping items using human-like patterns.
 */
public class ItemDropper {

  private static final Logger logger = LogManager.getLogger(ItemDropper.class);

  // AWT Event IDs for Key Press/Release
  private static final int KEY_PRESS = 401;
  private static final int KEY_RELEASE = 402;

  private static final int INVENTORY_SIZE = 28;

  /** Defines the order in which items should be dropped. */
  public enum DropPattern {

    /** Drops items left-to-right, top-to-bottom (0, 1, 2...). */
    STANDARD,

    /**
     * Drops items using a "2-Row Vertical Strip" logic.
     *
     * <p>Drops pairs of items vertically (e.g., 0 then 4, 1 then 5) moving across, then moves to
     * the next set of two rows, imo this looks most human.
     */
    ZIGZAG
  }

  /**
   * Drops all items in the inventory using the default ZigZag (2-Row Strip) pattern.
   *
   * @param controller The BaseScript's {@link Controller} object.
   */
  public static void dropAll(Controller controller) {
    dropAll(controller, DropPattern.ZIGZAG);
  }

  /**
   * Drops all items in the inventory using a specified pattern.
   *
   * @param controller The BaseScript's {@link Controller} object.
   * @param pattern The {@link DropPattern} to use for index generation.
   */
  public static void dropAll(Controller controller, DropPattern pattern) {
    if (controller == null) {
      logger.error("Controller is null, cannot drop items.");
      return;
    }

    logger.info("Dropping all items using pattern: {}", pattern);

    List<Integer> slotsToDrop = generateSlotIndices(pattern);

    // Start Shift-Drop
    controller.keyboard().sendModifierKey(KEY_PRESS, "shift");
    BaseScript.waitRandomMillis(100, 250);

    try {
      for (int slotIndex : slotsToDrop) {
        if (slotIndex >= controller.zones().getInventorySlots().size()) {
          continue;
        }

        Rectangle slotZone = controller.zones().getInventorySlots().get(slotIndex);
        Point clickPoint = ClickDistribution.generateRandomPoint(slotZone);

        clickPoint(clickPoint, controller);
        BaseScript.waitRandomMillis(40, 90);
      }
    } finally {
      BaseScript.waitRandomMillis(100, 200);
      controller.keyboard().sendModifierKey(KEY_RELEASE, "shift");
    }
  }

  /**
   * Generates a list of inventory slot indices based on the selected pattern.
   *
   * @param pattern The pattern strategy.
   * @return A list of integers representing the order of slots to click.
   */
  private static List<Integer> generateSlotIndices(DropPattern pattern) {
    List<Integer> indices = new ArrayList<>();

    switch (pattern) {
      case ZIGZAG:
        // Process rows 0-1, then 2-3, then 4-5 in vertical pairs
        for (int rowGroup = 0; rowGroup < 3; rowGroup++) {
          int baseRowStart = rowGroup * 8; // 0, 8, 16
          for (int col = 0; col < 4; col++) {
            indices.add(baseRowStart + col); // Top of pair (e.g., 0)
            indices.add(baseRowStart + col + 4); // Bottom of pair (e.g., 4)
          }
        }
        // Handle the last row (6) linearly
        indices.add(24);
        indices.add(25);
        indices.add(26);
        indices.add(27);
        break;

      case STANDARD:
      default:
        indices = IntStream.range(0, INVENTORY_SIZE).boxed().collect(Collectors.toList());
        break;
    }
    return indices;
  }

  /**
   * Helper method to left-click a {@link Point} location on-screen.
   *
   * @param clickPoint The point to click.
   * @param controller The controller instance.
   */
  private static void clickPoint(Point clickPoint, Controller controller) {
    try {
      controller.mouse().moveTo(clickPoint, "fast");
      controller.mouse().leftClick();
    } catch (InterruptedException e) {
      logger.error("Mouse interrupted during drop operation", e);
      Thread.currentThread().interrupt();
    }
  }
}
