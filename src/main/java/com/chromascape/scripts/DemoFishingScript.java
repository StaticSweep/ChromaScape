package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.actions.PointSelector;
import com.chromascape.utils.core.input.distribution.ClickDistribution;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.core.screen.topology.TemplateMatching;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A demo script. Created to show off how to use certain utilities, namely:
 *
 * <ul>
 *   <li>Ocr and how to read text in screen regions.
 *   <li>How to use the Idler (although a modified version)
 *   <li>Dropping items in a human-like way using the mouse
 *   <li>Template matching, its use of thresholds and how to search for images on-screen
 *   <li>Colour detection within the gameView
 *   <li>The use of {@link PointSelector} the new actions utility
 * </ul>
 *
 * <p>This is a DEMO script. It's not intended to be used, but rather as a reference. ChromaScape is
 * not liable for any damages incurred whilst using DEMO scripts. Images not included.
 */
public class DemoFishingScript extends BaseScript {

  // Only one type of rod and one bait is necessary to be downloaded
  private static final String fishingRod = "/images/user/Fishing_rod.png";
  private static final String flyFishingRod = "/images/user/Fly_fishing_rod.png";
  private static final String bait = "/images/user/Fishing_bait.png";
  private static final String feather = "/images/user/Feather.png";

  private static final Logger logger = LogManager.getLogger(DemoFishingScript.class);

  private static String lastMessage;

  /**
   * Overridden cycle. Repeats all tasks within, until stop() is called from either the Web UI, or
   * from within the script.
   */
  @Override
  protected void cycle() {
    if (!checkIfCorrectInventoryLayout()) {
      logger.warn("Normal or fly-fishing rod must be in inventory slot 27");
      logger.warn("Bait/feathers must be in inventory slot 28");
      logger.info("The top of your bait or feather images should be cropped by 10 px");
      stop();
    }

    clickFishingSpot();

    // Waiting, if the player needs to walk - adjust as needed
    waitRandomMillis(5000, 6000);

    // Check if stopped fishing
    waitUntilStoppedFishing(300);
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
   * Checks if the inventory layout is as expected. The inventory layout needs to be in a specific
   * format to ensure that the dropping of items looks human.
   *
   * @return {@code boolean} true if correct, false if not.
   */
  private boolean checkIfCorrectInventoryLayout() {
    logger.info("Checking if inventory layout is valid");
    Rectangle invSlot27 = controller().zones().getInventorySlots().get(26);
    Rectangle invSlot28 = controller().zones().getInventorySlots().get(27);
    BufferedImage invSlot27Image = ScreenManager.captureZone(invSlot27);
    BufferedImage invSlot28Image = ScreenManager.captureZone(invSlot28);
    Rectangle slot27Match;
    Rectangle slot28Match;

    try {
      slot27Match = TemplateMatching.match(fishingRod, invSlot27Image, 0.15, false);
      slot28Match = TemplateMatching.match(bait, invSlot28Image, 0.15, false);

      if (slot27Match != null && slot28Match != null) {
        logger.info("Inventory layout matched");
        return true;
      }

      slot27Match = TemplateMatching.match(flyFishingRod, invSlot27Image, 0.05, false);
      slot28Match = TemplateMatching.match(feather, invSlot28Image, 0.05, false);

      if (slot27Match != null && slot28Match != null) {
        logger.info("Inventory layout matched");
        return true;
      } else {
        return false;
      }
    } catch (Exception e) {
      logger.error(
          "checkIfCorrectInventoryLayout() template matching failed: {}", String.valueOf(e));
    }
    return false;
  }

  /**
   * Drops all fish in the inventory in a human-like manner. Designed to only be called when the
   * inventory is full, because it doesn't check whether there is an item in any specific slot.
   */
  private void dropAllFish() {
    logger.info("Dropping all fish");
    int currentSlot = 0;
    int alternateSlot = 4;
    controller().keyboard().sendModifierKey(401, "shift");
    waitRandomMillis(100, 250);
    for (int i = 0; i < 3; i++) {
      for (int j = 0; j < 4; j++) {
        clickPoint(
            ClickDistribution.generateRandomPoint(
                controller().zones().getInventorySlots().get(currentSlot)));
        waitRandomMillis(20, 50);
        clickPoint(
            ClickDistribution.generateRandomPoint(
                controller().zones().getInventorySlots().get(alternateSlot)));
        waitRandomMillis(40, 80);
        currentSlot++;
        alternateSlot = currentSlot + 4;
      }
      waitRandomMillis(100, 200);
      currentSlot = currentSlot + 4;
      alternateSlot = currentSlot + 4;
    }
    clickPoint(
        ClickDistribution.generateRandomPoint(controller().zones().getInventorySlots().get(24)));
    clickPoint(
        ClickDistribution.generateRandomPoint(controller().zones().getInventorySlots().get(25)));
    waitRandomMillis(100, 250);
    controller().keyboard().sendModifierKey(402, "shift");
  }

  /**
   * Helper method to left-click a {@link Point} location on-screen.
   *
   * @param clickPoint The point to click.
   */
  private void clickPoint(Point clickPoint) {
    try {
      controller().mouse().moveTo(clickPoint, "medium");
    } catch (InterruptedException e) {
      logger.error("Mouse interrupted: {}", String.valueOf(e));
    }
    controller().mouse().leftClick();
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
    String extraction = null;
    try {
      extraction = Ocr.extractText(chat, "Quill 8", black, true);
    } catch (IOException e) {
      logger.error("OCR failed while loading font: {}", String.valueOf(e));
    }
    return extraction != null && extraction.contains(phrase);
  }

  /**
   * Clicks the {@code Cyan} colour which denotes a fishing spot within the GameView {@link
   * BufferedImage}. Generates a random click point to click within the contour of the found {@code
   * Cyan} object.
   */
  private void clickFishingSpot() {
    logger.info("Clicking fishing spot");
    BufferedImage gameView = null;
    try {
      gameView = controller().zones().getGameView();
    } catch (Exception e) {
      logger.error("gameView not found: {}", String.valueOf(e));
    }

    Point clickLocation = PointSelector.getRandomPointInColour(gameView, "Cyan", 15);
    if (clickLocation == null) {
      logger.error("clickLocation is null!");
      stop();
    }
    clickPoint(clickLocation);
  }

  /**
   * A modified version of the {@link com.chromascape.utils.actions.Idler#waitUntilIdle(BaseScript,
   * int) waitUntilIdle} method. Altered to also check if the inventory is full and or if the user
   * has run out of bait/feathers.
   *
   * @param timeoutSeconds The maximum number of seconds to remain idle before continuing.
   */
  private void waitUntilStoppedFishing(int timeoutSeconds) {
    logger.info("Waiting until stopped fishing");
    checkInterrupted();
    try {
      Instant start = Instant.now();
      Instant deadline = start.plus(Duration.ofSeconds(timeoutSeconds));
      while (Instant.now().isBefore(deadline)) {
        Rectangle latestMessage = controller().zones().getChatTabs().get("Latest Message");
        ColourObj red = ColourInstances.getByName("ChatRed");
        ColourObj black = ColourInstances.getByName("Black");
        String idleText = Ocr.extractText(latestMessage, "Plain 12", red, true);
        String timeStamp = Ocr.extractText(latestMessage, "Plain 12", black, true);
        if ((idleText.contains("moving") || idleText.contains("idle"))
            && !timeStamp.equals(lastMessage)) {
          lastMessage = timeStamp;
          return;
        } else if (checkChatPopup("carry")) {
          return;
        } else if (checkChatPopup("have")) {
          return;
        }
      }
    } catch (Exception e) {
      logger.error("Error while waiting for idle", e);
    }
  }
}
