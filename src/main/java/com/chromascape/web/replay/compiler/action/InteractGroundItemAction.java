package com.chromascape.web.replay.compiler.action;

/** Interact with a ground-item stack by its recorded stable identity. */
public record InteractGroundItemAction(String targetStableId, String option, Integer itemId)
    implements SemanticAction {

  public InteractGroundItemAction(String targetStableId, String option) {
    this(targetStableId, option, null);
  }

  @Override
  public String type() {
    return "INTERACT_GROUND_ITEM";
  }
}
