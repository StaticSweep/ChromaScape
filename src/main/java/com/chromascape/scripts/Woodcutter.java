package com.chromascape.scripts;

import com.chromascape.base.BaseScript;
import com.chromascape.web.logs.LogService;

/** A script for woodcutting automation. */
public class Woodcutter extends BaseScript {

  /**
   * Constructs a new Woodcutter script.
   *
   * @param isFixed whether the script is fixed
   * @param duration the duration of the script
   * @param logger the logger service
   */
  public Woodcutter(boolean isFixed, int duration, LogService logger) {
    super(isFixed, duration, logger);
  }

  @Override
  protected void cycle() {
    // TODO: Implement woodcutting logic
  }
}
