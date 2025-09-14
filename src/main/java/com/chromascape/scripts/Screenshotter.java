package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
  private final Logger logger = LogManager.getLogger(Screenshotter.class);

  public static final String ORIGINAL_IMAGE_PATH = "output/original.png";

  /**
   * Same constructor as super (BaseScript).
   *
   * @param isFixed whether the client is in classic fixed or classic resizable
   */
  public Screenshotter(boolean isFixed) {
    super(isFixed);
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
      logger.error(e.getMessage());
    }
    stop();
  }
}
