package com.chromascape.web.replay.compiler.action;

/** Wait until the kernel reaches the given tick. */
public record WaitUntilTickAction(long tick) implements SemanticAction {

  @Override
  public String type() {
    return "WAIT_UNTIL_TICK";
  }
}
