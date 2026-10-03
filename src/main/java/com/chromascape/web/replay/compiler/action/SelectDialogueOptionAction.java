package com.chromascape.web.replay.compiler.action;

/** Select one server-visible dialogue option by text, index, or both. */
public record SelectDialogueOptionAction(
    String targetStableId,
    String option,
    Integer optionIndex,
    Integer widgetId,
    Integer widgetIndex)
    implements SemanticAction {

  public SelectDialogueOptionAction(String targetStableId, String option, Integer optionIndex) {
    this(targetStableId, option, optionIndex, null, null);
  }

  public SelectDialogueOptionAction(String targetStableId, String option) {
    this(targetStableId, option, null, null, null);
  }

  public SelectDialogueOptionAction(String targetStableId, int optionIndex) {
    this(targetStableId, null, optionIndex, null, null);
  }

  @Override
  public String type() {
    return "SELECT_DIALOGUE_OPTION";
  }
}
