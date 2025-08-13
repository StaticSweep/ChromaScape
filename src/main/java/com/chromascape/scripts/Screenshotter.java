package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.web.logs.LogService;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Creates screenshots of the client and stores it in an external folder "screenshots". Screenshots
 * are saved as "original.png". Screenshots taken by this class are used by the colour picker in the
 * web UI. This is a single cycle program that exits out immediately after completion. Served as a
 * "user script" in the web UI.
 */
public class Screenshotter extends BaseScript {

  /**
   * The logger is specially initialised to be used in this program. This is exactly how you should
   * access it in a user script.
   */
  private final LogService logger;

  public static final String ORIGINAL_IMAGE_PATH = "output/original.png";

  /**
   * Same constructor as super (BaseScript) but logger is saved as a global variable.
   *
   * @param isFixed whether the client is in classic fixed or classic resizable
   * @param duration the total runtime of the script in minutes
   * @param logger the logging service for recording events and progress
   */
  public Screenshotter(boolean isFixed, int duration, LogService logger) {
    super(isFixed, duration, logger);
    this.logger = logger;
  }

  /**
   * Takes a screenshot and saves it in the "screenshots" folder outside the project directory.
   * Although in the BaseScript - this function is repeated until the specified time duration is met
   * - Here because this is a one time task it exits out early by calling "stop()".
   */
  @Override
  protected void cycle() {
    BufferedImage sc = ScreenManager.captureWindow();
    System.out.println("Screenshotter cycle");
    try {
      ImageIO.write(sc, "png", new File(ORIGINAL_IMAGE_PATH));
    } catch (Exception e) {
      logger.addLog(e.getMessage());
    }
    stop();
  }
}
