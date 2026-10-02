package com.chromascape.web.replay.compiler.action;

/** Cast a pinned spell through the server spell/rune service. */
public record CastSpellAction(
    String targetStableId, Integer spellId, String option, Integer widgetId, Integer widgetIndex)
    implements SemanticAction {

  public CastSpellAction(String targetStableId, int spellId) {
    this(targetStableId, spellId, null, null, null);
  }

  public CastSpellAction(String targetStableId, int spellId, String option) {
    this(targetStableId, spellId, option, null, null);
  }

  @Override
  public String type() {
    return "CAST_SPELL";
  }
}
