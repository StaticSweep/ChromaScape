package com.chromascape.web.replay.compiler.action;

/** Interact with an inventory item using its explicit slot and item identity. */
public record InteractInventoryItemAction(
    String targetStableId, int slot, int itemId, String option) implements SemanticAction {

  @Override
  public String type() {
    return "INTERACT_INVENTORY_ITEM";
  }
}
