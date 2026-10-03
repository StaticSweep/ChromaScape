package com.chromascape.web.replay.compiler.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Tagged union of semantic actions supported by the replay kernel. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = WaitUntilTickAction.class, name = "WAIT_UNTIL_TICK"),
  @JsonSubTypes.Type(value = InteractNpcAction.class, name = "INTERACT_NPC"),
  @JsonSubTypes.Type(value = InteractObjectAction.class, name = "INTERACT_OBJECT"),
  @JsonSubTypes.Type(value = InteractGroundItemAction.class, name = "INTERACT_GROUND_ITEM"),
  @JsonSubTypes.Type(value = InteractInventoryItemAction.class, name = "INTERACT_INVENTORY_ITEM"),
  @JsonSubTypes.Type(value = InteractWidgetAction.class, name = "INTERACT_WIDGET"),
  @JsonSubTypes.Type(value = SelectDialogueOptionAction.class, name = "SELECT_DIALOGUE_OPTION"),
  @JsonSubTypes.Type(value = CastSpellAction.class, name = "CAST_SPELL"),
  @JsonSubTypes.Type(value = EquipItemAction.class, name = "EQUIP_ITEM"),
  @JsonSubTypes.Type(value = WalkToAction.class, name = "WALK_TO"),
  @JsonSubTypes.Type(value = InventoryAction.class, name = "INVENTORY_ACTION"),
  @JsonSubTypes.Type(value = UseItemOnNpcAction.class, name = "USE_ITEM_ON_NPC"),
})
public sealed interface SemanticAction
    permits WaitUntilTickAction,
        InteractNpcAction,
        InteractObjectAction,
        InteractGroundItemAction,
        InteractInventoryItemAction,
        InteractWidgetAction,
        SelectDialogueOptionAction,
        CastSpellAction,
        EquipItemAction,
        WalkToAction,
        InventoryAction,
        UseItemOnNpcAction {

  /** The action type discriminator. */
  String type();
}
