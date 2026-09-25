package com.chromascape.web.replay.compiler.action;

/** Interact with an object by stable ID and option string. */
public record InteractObjectAction(String targetStableId, String option) implements SemanticAction {

  @Override
  public String type() {
    return "INTERACT_OBJECT";
  }
}
