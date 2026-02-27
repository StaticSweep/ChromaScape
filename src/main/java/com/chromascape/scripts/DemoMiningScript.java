package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.Idler;
import com.chromascape.utils.actions.ItemDropper;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A demo script that automates basic mining behavior in the client.
 *
 * <p>The script demonstrates simple bot actions such as:
 *
 * <ul>
 *   <li>Clicking on ore rocks to mine
 *   <li>Detecting when the inventory is full
 *   <li>Dropping ore using shift-click
 *   <li>Idling until "You are now idle!" message appears
 * </ul>
 *
 * <p>This is intended as an example implementation built on top of {@link BaseScript}.
 */
public class DemoMiningScript extends BaseScript {

  private static final Logger logger = LogManager.getLogger(DemoMiningScript.class);
  private static final String ironOre = "/images/user/Iron_ore.png";

  /**
   * Executes one cycle of the script logic.
   *
   * <p>If the inventory is full, the script drops ore. Otherwise, it attempts to click an ore rock
   * and mine it, then idles briefly before repeating.
   */
  @Override
  protected void cycle() {
    if (isInventoryFull()) {
      ItemDropper.dropAll(this);
    }
    clickOre();
    waitRandomMillis(800, 1000);
    Idler.waitUntilIdle(this, 20);
  }

  /**
   * Attempts to locate and click on an ore rock in the game view.
   *
   * <p>If no suitable rock is found, the script stops.
   */
  private void clickOre() {
    BufferedImage gameView = controller().zones().getGameView();
    Point clickLoc = PointSelector.getRandomPointInColour(gameView, "Cyan", 15);
    if (clickLoc == null) {
      logger.error("Click location is null");
      stop();
      return;
    }
    controller().mouse().moveTo(clickLoc, "medium");
    controller().mouse().leftClick();
  }

  /**
   * Checks whether the player’s inventory is full by examining the final inventory slot for the
   * presence of an iron ore image.
   *
   * @return {@code true} if the inventory is full, otherwise {@code false}
   */
  private boolean isInventoryFull() {
    Rectangle invSlot = controller().zones().getInventorySlots().get(27);
    BufferedImage invSlotImg = ScreenManager.captureZone(invSlot);
    Rectangle match = TemplateMatching.match(ironOre, invSlotImg, 0.05).bounds();
    return match != null;
  }
}
