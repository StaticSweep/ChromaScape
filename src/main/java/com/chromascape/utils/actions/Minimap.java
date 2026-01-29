package com.chromascape.utils.actions;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.utils.domain.ocr.Ocr;
import java.awt.Rectangle;
import java.io.IOException;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Class for retrieving information from the minimap area, such as orb data (HP, prayer, run, and
 * spec) and XP data.
 */
public class Minimap {

  private static final ColourObj textColour =
      new ColourObj("green", new Scalar(0, 254, 254, 0), new Scalar(60, 255, 255, 0));

  private static final ColourObj white =
      new ColourObj("White", new Scalar(0, 0, 255, 0), new Scalar(0, 0, 255, 0));

  /**
   * Returns the character's current hitpoints, or -1 if not found.
   *
   * @param script The current running script (typically pass {@code this})
   * @throws IOException if the OCR failed to load the font
   */
  public static int getHp(BaseScript script) throws IOException {
    Rectangle textArea = script.controller().zones().getMinimap().get("hpText");
    String hpText = Ocr.extractText(textArea, "Plain 11", textColour, true);
    if (hpText.isEmpty()) {
      return -1;
    }
    return Integer.parseInt(hpText);
  }

  /**
   * Returns the character's current prayer, or -1 if not found.
   *
   * @param script The current running script (typically pass {@code this})
   * @throws IOException if the OCR failed to load the font
   */
  public static int getPrayer(BaseScript script) throws IOException {
    Rectangle textArea = script.controller().zones().getMinimap().get("prayerText");
    String prayerText = Ocr.extractText(textArea, "Plain 11", textColour, true);
    if (prayerText.isEmpty()) {
      return -1;
    }
    return Integer.parseInt(prayerText);
  }

  /**
   * Returns the character's current run energy, or -1 if not found.
   *
   * @param script The current running script (typically pass {@code this})
   * @throws IOException if the OCR failed to load the font
   */
  public static int getRun(BaseScript script) throws IOException {
    Rectangle textArea = script.controller().zones().getMinimap().get("runText");
    String runText = Ocr.extractText(textArea, "Plain 11", textColour, true);
    if (runText.isEmpty()) {
      return -1;
    }
    return Integer.parseInt(runText);
  }

  /**
   * Returns the character's current special attack energy, or -1 if not found.
   *
   * @param script The current running script (typically pass {@code this})
   * @throws IOException if the OCR failed to load the font
   */
  public static int getSpec(BaseScript script) throws IOException {
    Rectangle textArea = script.controller().zones().getMinimap().get("specText");
    String specText = Ocr.extractText(textArea, "Plain 11", textColour, true);
    if (specText.isEmpty()) {
      return -1;
    }
    return Integer.parseInt(specText);
  }

  /**
   * Retrieves the current XP from beside the minimap UI element.
   *
   * <p>It is highly recommended to set the XP bar to permanent, as seen here:
   * https://github.com/StaticSweep/ChromaScape/wiki/Requirements
   *
   * @param script The current running script (typically pass {@code this})
   * @return the XP integer, or empty if not found
   * @throws IOException if the OCR failed to load the font
   */
  public static int getXp(BaseScript script) throws IOException {
    Rectangle xpZone = script.controller().zones().getMinimap().get("totalXP");
    String xpText = Ocr.extractText(xpZone, "Plain 12", white, true);
    return Integer.parseInt(xpText.trim().replace(",", ""));
  }
}
