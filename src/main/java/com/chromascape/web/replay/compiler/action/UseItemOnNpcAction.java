package com.chromascape.web.replay.compiler.action;

/** Use an inventory item on an NPC. */
public record UseItemOnNpcAction(int slot, int itemId, String targetStableId)
    implements SemanticAction {

  @Override
  public String type() {
    return "USE_ITEM_ON_NPC";
  }
}
