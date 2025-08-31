package com.chromascape.utils.domain.walker;

import com.chromascape.api.Dax;
import com.chromascape.controller.Controller;
import com.chromascape.utils.core.input.Sleeper;
import com.chromascape.utils.core.screen.colour.ColourInstances;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.domain.ocr.Ocr;
import com.chromascape.web.logs.LogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Provides high-level pathfinding and walking functionality for the bot.
 *
 * <p>The {@code Walker} integrates with the {@link Dax} pathfinding API, in-game OCR, and the
 * minimap/compass systems to move the player character to a given destination tile. It has access
 * to the {@link Controller}, granting it access to screen zones, the virtual mouse, and other
 * utilities.
 *
 * <p>Walking is achieved by:
 *
 * <ul>
 *   <li>Using OCR to read the player's current position from the game client.
 *   <li>Querying the DAX API for a path between the current position and the destination.
 *   <li>Projecting intermediate path tiles onto the minimap using pixel-per-tile scaling and
 *       compass rotation.
 *   <li>Issuing randomized mouse clicks on the minimap to simulate human-like input.
 *   <li>Polling player movement until the character stops, recalculating the path if necessary.
 * </ul>
 *
 * <p>The {@code Walker} assumes:
 *
 * <ul>
 *   <li>The minimap is at the default zoom level.
 *   <li>OCR can reliably extract the player's current coordinates from the Tile zone.
 *   <li>The compass direction is available and accurate for rotation calculations.
 * </ul>
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * controller().walker.pathTo(new Point(3200, 3200), true);
 * }</pre>
 *
 * <p>This will walk the player to the given tile, respecting camera rotation and randomized path
 * horizons, while logging progress to the provided {@link LogService}.
 */
public class Walker {

  private final Controller controller;
  private final LogService logger;
  private final Dax dax;
  private final ObjectMapper objectMapper;
  private final Compass compass;
  private final Random random;
  private CompletableFuture<Point> pointFuture;

  /**
   * Creates a new Walker for controlling player movement. Initializes dependencies including
   * controller access, logging, DAX API, and compass handling.
   *
   * @param controller The bot's controller
   * @param logger The {@link LogService} to be able to let the user know what's happening.
   */
  public Walker(Controller controller, LogService logger) {
    this.controller = controller;
    this.logger = logger;
    this.dax = new Dax();
    this.objectMapper = new ObjectMapper();
    this.random = new Random();
    this.compass = new Compass(controller, logger);
    this.pointFuture = new CompletableFuture<>();
  }

  /**
   * Gets the player's position by using runtime OCR on the GridInfo's "Tile" zone.
   *
   * @return An integer array with 3 elements - x, y and z.
   * @throws IOException Due to runtime OCR and font-loading.
   */
  public Tile getPlayerPosition() throws IOException {
    Rectangle zone = controller.zones().getGridInfo().get("Tile");
    ColourObj colour = ColourInstances.getByName("White");
    // Extracts the position using OCR and splits it into a 3 value list (x, y, z)
    List<String> stringPos =
        Arrays.asList(Ocr.extractText(zone, "Plain 12", colour, true).split(","));
    return new Tile(
        Integer.parseInt(stringPos.get(0)),
        Integer.parseInt(stringPos.get(1)),
        Integer.parseInt(stringPos.get(2)));
  }

  /**
   * Sends a payload to the DAX API with start/end positions and members availability. In return -
   * receives a path that it deserializes and turns into {@link Tile} objects.
   *
   * @param destination A {@link Point} object defining the co-ordinates of your destination.
   * @param isMembers A boolean dictating whether your character is a member or free to play.
   * @return A {@link List} list of {@link Tile} objects with the first tile being your current
   *     position.
   * @throws IOException Due to runtime OCR, font-loading and calling the API
   * @throws InterruptedException If interrupted during the API call.
   */
  private List<Tile> getPath(Point destination, boolean isMembers)
      throws IOException, InterruptedException {
    Tile position = getPlayerPosition();
    String rawPath;
    DaxPath daxPath = null;
    int retries = 20;
    int attempt = 0;

    while (attempt < retries) {
      attempt++;
      // Call DAX API
      rawPath = dax.generatePath(new Point(position.x(), position.y()), destination, isMembers);
      if ("RATE_LIMIT_EXCEEDED".equals(rawPath)) {
        // Wait a bit before retrying to avoid hammering the API
        Sleeper.waitRandomMillis(600, 700);
        continue; // retry
      }
      try {
        // Deserialize the raw JSON
        daxPath = objectMapper.readValue(rawPath, DaxPath.class);
        break; // success
      } catch (IOException e) {
        // Log and retry on deserialization failure
        logger.addLog("Failed to deserialize DAX response: " + e.getMessage());
      }
    }
    if (daxPath == null) {
      throw new IOException(
          "Failed to get a successful path from DAX after " + retries + " retries.");
    }
    return daxPath.path();
  }

  /**
   * Walks the player to a given destination tile using intermediate clicks on the minimap, while
   * asynchronously precomputing the next click point to improve responsiveness.
   *
   * <p>This approach ensures that the next click location is calculated while waiting, reducing
   * idle time and keeping movement smooth and efficient. The path list is modified in-place by
   * {@link #chooseNextTarget(List, int, int)}.
   *
   * @param destination the destination {@link Point} to walk to
   * @param isMembers whether the player is a members account, affecting path calculation
   * @throws IOException if OCR or path retrieval from DAX fails
   * @throws InterruptedException if the thread is interrupted while waiting for player movement
   * @throws ExecutionException if the asynchronous computation of the next click point fails
   */
  public void pathTo(Point destination, boolean isMembers)
      throws IOException, InterruptedException, ExecutionException {
    List<Tile> path = getPath(destination, isMembers);
    // How far away from the current tile the bot should click
    int maxHorizon = 10;
    int minHorizon = 8;
    // Synchronously path once
    Tile target = chooseNextTarget(path, minHorizon, maxHorizon);
    logger.addLog("Synchronously clicking once at " + target.x() + ", " + target.y());
    controller.mouse().moveTo(getClickLocation(target, getPlayerPosition()), "medium");
    controller.mouse().leftClick();
    // Looping until at destination
    while (getPlayerPosition().x() != destination.getX()
        || getPlayerPosition().y() != destination.getY()) {
      if (path.isEmpty()) {
        break;
      }
      // Effectively final variables for the lambda function.
      Tile newTarget = chooseNextTarget(path, minHorizon, maxHorizon);
      Tile oldTarget = target;
      // Async precomputing the next click point while waiting for the bot to stop
      pointFuture = CompletableFuture.supplyAsync(() -> getClickLocation(newTarget, oldTarget));
      // This blocks the main thread, but the next point is being computed already.
      logger.addLog("Precomputing next click at " + newTarget.x() + ", " + newTarget.y());
      waitToStop();
      // Recalculate path and cancel async if not at expected location
      Tile position = getPlayerPosition();
      Point clickpoint;
      if (position.x() != target.x() || position.y() != target.y()) {
        logger.addLog("Veered off path, recalculating...");
        pointFuture.cancel(false);
        try {
          pointFuture.join();
        } catch (CancellationException | CompletionException e) {
          logger.addLog("Async task was cancelled and thread joined");
        }
        // If the path is out of range recalculate whole path
        target = chooseNextTarget(path, 5, 7);
        if (Math.abs(position.x() - target.x()) > 7 || Math.abs(position.y() - target.y()) > 7) {
          logger.addLog("Too far from path, calling Dax...");
          path = getPath(destination, isMembers);
          target = chooseNextTarget(path, minHorizon, maxHorizon);
        }
        clickpoint = getClickLocation(target, getPlayerPosition());
      } else {
        clickpoint = pointFuture.get();
        // Update target
        target = newTarget;
      }
      // Both scenarios saved as the clickPoint
      controller.mouse().moveTo(clickpoint, "medium");
      controller.mouse().leftClick();
    }
  }

  /**
   * Selects the next intermediate target tile from the given path for the bot to click on the
   * minimap.
   *
   * <p>The method randomly chooses a target a few tiles ahead of the player's current position
   * (between {@code minHorizon} and {@code maxHorizon}) to simulate human-like movement and avoid
   * predictable straight-line clicking.
   *
   * <p>If the path is shorter than the randomly selected horizon, the last tile in the path is
   * chosen. Once a target is chosen, all preceding tiles up to the chosen target are removed from
   * the path, effectively updating the path for the next iteration.
   *
   * @param path the list of {@link Tile} objects representing the remaining path to the
   *     destination; this list will be modified by removing tiles up to the chosen target
   * @return the {@link Tile} selected as the next click target
   */
  private Tile chooseNextTarget(List<Tile> path, int minHorizon, int maxHorizon) {
    int targetPos = random.nextInt(minHorizon, maxHorizon + 1);
    Tile target;
    // If we're about to overshoot the last tile, just click the last tile
    if (path.size() > targetPos) {
      target = path.get(targetPos);
      path.subList(0, targetPos).clear();
    } else {
      target = path.get(path.size() - 1);
      path.clear();
    }
    return target;
  }

  /**
   * Uses the given players position and the position of the target {@link Tile} to project the
   * click location of the given {@link Tile} onto the minimap. Uses the compass' angle to rotate
   * the click location so it can conform to any camera position. Requires that the minimap be at
   * default zoom level.
   *
   * @param target The target {@link Tile} to path to.
   * @param playerPosition The position of the player to calculate form.
   * @return Returns the {@link Point} click location to click.
   * @implNote Requires default minimap zoom. Other zoom levels will misalign tile clicks.
   */
  private Point getClickLocation(Tile target, Tile playerPosition) {
    // 4 pixels per tile at normal zoom
    int pixelsPerTile = 4;
    // Save player position
    int x = playerPosition.x();
    int y = playerPosition.y();
    // Calculating distance from the player to the target, in pixels
    // and adding an offset of a few pixels to click randomly within the tile
    double dx = ((target.x() - x) * pixelsPerTile);
    double dy = ((y - target.y()) * pixelsPerTile);
    // Locating the player's tile on the minimap
    Rectangle playerMinimap = controller.zones().getMinimap().get("playerPos");
    // Origins dictate the perfect center - used to rotate the click location
    double originX = playerMinimap.x + ((double) (pixelsPerTile - 1) / 2);
    double originY = playerMinimap.y + ((double) (pixelsPerTile - 1) / 2);
    // Calculate the radian based on compass rotation
    double theta = Math.toRadians(compass.getCompassAngle());
    // Calculate rotated x and y
    double rotX = Math.cos(theta) * dx - Math.sin(theta) * dy;
    double rotY = Math.sin(theta) * dx + Math.cos(theta) * dy;
    // Generate the rotated point
    return new Point((int) Math.round(originX + rotX), (int) Math.round(originY + rotY));
  }

  /**
   * Polls the player's position to check if the player has stopped moving. Exits out when stopped.
   *
   * @throws IOException Due to runtime OCR and font-loading.
   */
  private void waitToStop() throws IOException {
    // Ticks on some worlds can vary, it's usual on world 302 to be 0.618 per tick
    long tick = 650;
    Tile position = getPlayerPosition();
    Sleeper.waitMillis(tick);
    while (true) {
      if (position.equals(getPlayerPosition())) {
        return;
      } else {
        position = getPlayerPosition();
        Sleeper.waitMillis(tick);
      }
    }
  }
}
