package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Canonical, normalized semantic action emitted by the deterministic fishing policy.
 *
 * @param type the semantic action family (prototype one only emits {@code INTERACT_NPC})
 * @param option the canonicalized fishing option (net, bait, lure, cage, harpoon)
 * @param target the canonicalized target name (prototype one only emits {@code fishing spot})
 */
public record SemanticAction(
    @JsonProperty("type") String type,
    @JsonProperty("option") String option,
    @JsonProperty("target") String target) {

  public static final String TYPE_INTERACT_NPC = "INTERACT_NPC";

  /** Creates an NPC interaction semantic action. */
  public static SemanticAction interactNpc(String option, String target) {
    return new SemanticAction(TYPE_INTERACT_NPC, option, target);
  }

  /** Returns true when this action is semantically equal to another (all fields equal). */
  public boolean matches(SemanticAction other) {
    return other != null
        && java.util.Objects.equals(type, other.type)
        && java.util.Objects.equals(option, other.option)
        && java.util.Objects.equals(target, other.target);
  }
}
