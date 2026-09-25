package com.chromascape.web.replay.compiler.action;

/** Walk to a specific position. */
public record WalkToAction(Position destination) implements SemanticAction {

  @Override
  public String type() {
    return "WALK_TO";
  }

  public record Position(int x, int y, int plane) {}
}
