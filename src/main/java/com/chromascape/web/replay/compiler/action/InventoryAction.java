package com.chromascape.web.replay.compiler.action;

/** Perform an inventory action on a specific slot/item. */
public record InventoryAction(int slot, int itemId, String option) implements SemanticAction {

  @Override
  public String type() {
    return "INVENTORY_ACTION";
  }
}
