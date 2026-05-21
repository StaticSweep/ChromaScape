package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.Idler;
import com.chromascape.utils.actions.ItemDropper;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.MatchResult;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A demo script. Created to show off how to use certain utilities, namely:
 *
 * <ul>
 *   <li>Ocr and how to read text in screen regions.
 *   <li>How to use the Idler
 *   <li>Dropping items in a human like manner using the mouse
 *   <li>Template matching, how to use the MatchResult, and how to search for images on-screen
 *   <li>Colour detection within the gameView
 *   <li>The use of the {@link PointSelector} actions utility
 * </ul>
 *
 * <p>This is a DEMO script. It's not intended to be used, but rather as a reference. ChromaScape is
 * not liable for any damages incurred whilst using DEMO scripts. Images not included.
 */
public class DemoFishingScript extends BaseScript {

  private static final String flyFishingRod = "/images/user/Fly_fishing_rod.png";
  private static final String feather = "/images/user/Feather.png";

  private static final Logger logger = LogManager.getLogger(DemoFishingScript.class);

  private static final int IDLE_TIMEOUT_SECONDS = 300;
  private static final int WALK_TIMEOUT_SECONDS = 17;

  /**
   * Overridden cycle. Repeats all tasks within, until stop() is called from either the Web UI, or
   * from within the script.
   */
  @Override
  protected void cycle() {
    if (!checkIfCorrectInventoryLayout()) {
      logger.warn("Fly-fishing rod must be in inventory slot 27 / idx 26");
      logger.warn("Feathers must be in inventory slot 28 / idx 27");
      logger.info("The top of feather image should be cropped by 10 px");
      stop();
    }

    clickFishingSpot();

    waitUntilStoppedMoving();

    waitUntilStoppedFishing();
    logger.info("Is idle");

    // If ran out of bait, stop
    if (checkChatPopup("have")) {
      logger.warn("Ran out of bait!");
      stop();
    }

    // If inventory full, drop all fish
    if (checkChatPopup("carry")) {
      logger.warn("Pop-up found");
      dropAllFish();
    }
  }

  /**
   * Queries {@link DemoFishingScript#getCurrentWorldPos()} every tick until the player has stopped
   * moving or the WALK_TIMEOUT_SECONDS is reached. Blocks execution of the script until either
   * condition is met.
   */
  private void waitUntilStoppedMoving() {
    LocalDateTime end = LocalDateTime.now().plusSeconds(WALK_TIMEOUT_SECONDS);
    String currentTile = getCurrentWorldPos();
    while (LocalDateTime.now().isBefore(end)) {
      waitMillis(650);
      if (currentTile.equals(getCurrentWorldPos())) {
        return;
      }
      currentTile = getCurrentWorldPos();
    }
  }

  /**
   * Uses Ocr on the Grid Info box's Tile subzone.
   *
   * @return the tile position as a String.
   */
  private String getCurrentWorldPos() {
    Rectangle zone = controller().zones().getGridInfo().get("Tile");
    ColourObj colour = ColourInstances.getByName("White");
    return Ocr.extractText(zone, "Plain 12", colour, true);
  }

  /**
   * Checks if the inventory layout is as expected. The inventory layout needs to be in a specific
   * format to ensure that the dropping of items looks human. Will check for a fly-fishing rod in
   * index 26 and feathers in index 27.
   *
   * @return {@code boolean} true if correct, false if not.
   */
  private boolean checkIfCorrectInventoryLayout() {
    logger.info("Checking if inventory layout is valid");

    Rectangle invSlot27 = controller().zones().getInventorySlots().get(26);
    Rectangle invSlot28 = controller().zones().getInventorySlots().get(27);

    BufferedImage invSlot27Image = ScreenManager.captureZone(invSlot27);
    BufferedImage invSlot28Image = ScreenManager.captureZone(invSlot28);

    MatchResult slot27Match = TemplateMatching.match(flyFishingRod, invSlot27Image, 0.15);
    MatchResult slot28Match = TemplateMatching.match(feather, invSlot28Image, 0.15);

    if (!slot27Match.success()) {
      logger.error("Slot 27 / idx 26 does not contain a fly fishing rod.");
      return false;
    }

    if (!slot28Match.success()) {
      logger.error("Slot 28 / idx 27 does not contain feathers.");
      return false;
    }

    return true;
  }

  /**
   * Drops all fish in the inventory in a human-like manner. Designed to only be called when the
   * inventory is full, because it doesn't check whether there is an item in any specific slot.
   */
  private void dropAllFish() {
    logger.info("Dropping all fish");

    int[] excludeSlots = {26, 27};
    ItemDropper.dropAll(this, ItemDropper.DropPattern.ZIGZAG, excludeSlots);
  }

  /**
   * Checks if the chat contains a specified phrase in the font {@code Quill 8}. Uses the Ocr module
   * to look for the phrase in the {@code Chat} zone.
   *
   * @param phrase The phrase to look for in the chat.
   * @return true if found, else false.
   */
  private boolean checkChatPopup(String phrase) {
    Rectangle chat = controller().zones().getChatTabs().get("Chat");
    ColourObj black = ColourInstances.getByName("Black");
    String extraction = Ocr.extractText(chat, "Quill 8", black, true);
    return extraction.contains(phrase);
  }

  /**
   * Clicks the {@code Cyan} colour which denotes a fishing spot within the GameView {@link
   * BufferedImage}. Generates a random click point to click within the contour of the found {@code
   * Cyan} object.
   */
  private void clickFishingSpot() {
    logger.info("Clicking fishing spot");
    BufferedImage gameView = controller().zones().getGameView();

    Point clickLocation = PointSelector.getRandomPointInColour(gameView, "Cyan", 15);
    if (clickLocation == null) {
      logger.error("clickLocation is null!");
      stop();
    }
    controller().mouse().moveTo(clickLocation, "medium");
    controller().mouse().leftClick();
  }

  /**
   * Iterates over checking for idle, if the player can't carry any more fish, and if the player has
   * run out of bait. Blocks the main thread until one of these events occurs or the TIMEOUT_SECONDS
   * have elapsed.
   */
  private void waitUntilStoppedFishing() {
    LocalDateTime end = LocalDateTime.now().plusSeconds(IDLE_TIMEOUT_SECONDS);
    while (LocalDateTime.now().isBefore(end)) {
      if (Idler.waitUntilIdle(this, 3)) {
        return;
      }
      if (checkChatPopup("carry")) {
        return;
      }
      if (checkChatPopup("have")) {
        return;
      }
    }
  }
}
