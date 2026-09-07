package com.chromascape.web.replay.compiler.action;

/** Interact with a visible widget, retaining optional server widget pins. */
public record InteractWidgetAction(
    String targetStableId, String option, Integer widgetId, Integer widgetIndex)
    implements SemanticAction {

  public InteractWidgetAction(String targetStableId, String option) {
    this(targetStableId, option, null, null);
  }

  @Override
  public String type() {
    return "INTERACT_WIDGET";
  }
}
