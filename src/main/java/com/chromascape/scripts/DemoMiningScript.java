package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.Idler;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
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

  private static final Logger logger = LogManager.getLogger(DemoMiningScript.class.getName());
  private static final String ironOre = "/images/user/Iron_ore.png";

  /**
   * Constructs a new mining script.
   *
   * @param isFixed whether the client UI is fixed or resizable
   */
  public DemoMiningScript(boolean isFixed) {
    super(isFixed);
  }

  /**
   * Executes one cycle of the script logic.
   *
   * <p>If the inventory is full, the script drops ore. Otherwise, it attempts to click an ore rock
   * and mine it, then idles briefly before repeating.
   */
  @Override
  protected void cycle() {
    if (isInventoryFull()) {
      dropIronOre();
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
    try {
      BufferedImage gameView = controller().zones().getGameView();
      Point clickLoc = PointSelector.getRandomPointInColour(gameView, "Cyan", 15);
      if (clickLoc == null) {
        stop();
        return;
      }
      controller().mouse().moveTo(clickLoc, "medium");
      controller().mouse().leftClick();
    } catch (Exception e) {
      logger.error(e);
      logger.error(e.getStackTrace());
    }
  }

  /**
   * Checks whether the player’s inventory is full by examining the final inventory slot for the
   * presence of an iron ore image.
   *
   * @return {@code true} if the inventory is full, otherwise {@code false}
   */
  private boolean isInventoryFull() {
    try {
      Rectangle invSlot = controller().zones().getInventorySlots().get(27);
      BufferedImage invSlotImg = ScreenManager.captureZone(invSlot);
      Rectangle match = TemplateMatching.match(ironOre, invSlotImg, 0.05, false);
      return match != null;
    } catch (Exception e) {
      logger.error(e);
    }
    return false;
  }

  /**
   * Drops all iron ore in the inventory using shift-click.
   *
   * <p>Iterates through all 28 slots, clicking each one while the shift modifier is held down.
   */
  private void dropIronOre() {
    try {
      controller().keyboard().sendModifierKey(401, "shift");
      waitRandomMillis(800, 1200);
      for (int i = 0; i < 28; i++) {
        Rectangle invSlot = controller().zones().getInventorySlots().get(i);
        Point clickLoc = ClickDistribution.generateRandomPoint(invSlot);
        controller().mouse().moveTo(clickLoc, "medium");
        controller().mouse().leftClick();
        waitRandomMillis(200, 650);
      }
      waitRandomMillis(600, 800);
      controller().keyboard().sendModifierKey(402, "shift");
    } catch (Exception e) {
      logger.error(e);
      logger.error(e.getStackTrace());
    }
  }
}
