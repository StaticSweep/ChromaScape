package com.chromascape.web.recording.config;

public enum TaskType {
  COMBAT("Combat", "Combat encounters, PVP, bosses"),
  WOODCUTTING("Woodcutting", "Chopping trees, woodcutting guild"),
  MINING("Mining", "Mining rocks, motherload"),
  FISHING("Fishing", "Catching fish, fishing spots"),
  COOKING("Cooking", "Cooking food on ranges/fires"),
  SMITHING("Smithing", "Smelting, forging, smithing"),
  CRAFTING("Crafting", "Making items, crafting guild"),
  THIEVING("Thieving", "Pickpocketing, stalls"),
  FARMING("Farming", "Farming patches, tithe farm"),
  QUESTING("Questing", "Quest activities"),
  BOSSING("Bossing", "Boss fights, raids"),
  OTHER("Other", "Miscellaneous activities");

  private final String displayName;
  private final String description;

  TaskType(String displayName, String description) {
    this.displayName = displayName;
    this.description = description;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }
}
