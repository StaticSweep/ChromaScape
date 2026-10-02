package com.chromascape.web.replay.compiler.action;

/** Equip an inventory item through the server inventory/equipment service. */
public record EquipItemAction(
    String targetStableId, int slot, int itemId, String option, Integer equipmentSlot)
    implements SemanticAction {

  public EquipItemAction(String targetStableId, int slot, int itemId) {
    this(targetStableId, slot, itemId, null, null);
  }

  public EquipItemAction(String targetStableId, int slot, int itemId, String option) {
    this(targetStableId, slot, itemId, option, null);
  }

  @Override
  public String type() {
    return "EQUIP_ITEM";
  }
}
