package com.chromascape.web.replay.compiler.action;

/** Interact with an NPC by stable ID and option string. */
public record InteractNpcAction(String targetStableId, String option) implements SemanticAction {

  @Override
  public String type() {
    return "INTERACT_NPC";
  }
}
