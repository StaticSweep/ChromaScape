package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.aiplayer.prototype.FishingActionNormalizer.ActionClassification;
import com.chromascape.web.aiplayer.prototype.FishingActionNormalizer.ActionClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests action normalization and classification. */
class FishingActionNormalizerTest {

  @Test
  @DisplayName("RuneLite markup, nbsp, and whitespace normalize to the canonical target")
  void normalizesTargetMarkupAndWhitespace() {
    assertThat(FishingActionNormalizer.canonicalize("<col=ffff00>Fishing\u00a0 spot"))
        .isEqualTo("fishing spot");
    assertThat(FishingActionNormalizer.canonicalize("  Fishing   Spot  ")).isEqualTo("fishing spot");
    assertThat(FishingActionNormalizer.canonicalize("<col=00ff00>Net")).isEqualTo("net");
    assertThat(FishingActionNormalizer.canonicalize("")).isNull();
    assertThat(FishingActionNormalizer.canonicalize(null)).isNull();
  }

  @Test
  @DisplayName("supported fishing options normalize")
  void normalizesSupportedOptions() {
    assertThat(FishingActionNormalizer.normalizeOption("Net")).isEqualTo("net");
    assertThat(FishingActionNormalizer.normalizeOption("BAIT")).isEqualTo("bait");
    assertThat(FishingActionNormalizer.normalizeOption("Lure")).isEqualTo("lure");
    assertThat(FishingActionNormalizer.normalizeOption("cage")).isEqualTo("cage");
    assertThat(FishingActionNormalizer.normalizeOption("Harpoon")).isEqualTo("harpoon");
    assertThat(FishingActionNormalizer.normalizeOption("Chop")).isNull();
    assertThat(FishingActionNormalizer.normalizeOption("Drop")).isNull();
  }

  @Test
  @DisplayName("supported fishing NPC clicks are accepted")
  void supportedFishingActionAccepted() {
    PlayerAction action =
        new PlayerAction(
            100, 0, "Net", "<col=ffff00>Fishing spot", "NPC_FIRST_OPTION", 17, 17, 0, 100);
    ActionClassification classification = FishingActionNormalizer.classify(action);
    assertThat(classification.actionClass()).isEqualTo(ActionClass.SUPPORTED_FISHING);
    assertThat(classification.semanticAction())
        .isEqualTo(SemanticAction.interactNpc("net", "fishing spot"));
  }

  @Test
  @DisplayName("all five NPC option families are accepted")
  void allNpcOptionFamiliesAccepted() {
    String[] families = {
      "NPC_FIRST_OPTION", "NPC_SECOND_OPTION", "NPC_THIRD_OPTION", "NPC_FOURTH_OPTION",
      "NPC_FIFTH_OPTION"
    };
    for (String family : families) {
      PlayerAction action =
          new PlayerAction(100, 0, "Net", "Fishing spot", family, 17, 17, 0, 100);
      assertThat(FishingActionNormalizer.classify(action).actionClass())
          .as(family)
          .isEqualTo(ActionClass.SUPPORTED_FISHING);
    }
  }

  @Test
  @DisplayName("CANCEL actions are ignored")
  void cancelIgnored() {
    PlayerAction cancelAction =
        new PlayerAction(100, 0, "Cancel", "", "CANCEL", 0, 0, 0, 100);
    assertThat(FishingActionNormalizer.classify(cancelAction).actionClass())
        .isEqualTo(ActionClass.IGNORED);

    PlayerAction cancelOption =
        new PlayerAction(100, 0, "Cancel", "Fishing spot", "NPC_FIRST_OPTION", 17, 17, 0, 100);
    assertThat(FishingActionNormalizer.classify(cancelOption).actionClass())
        .isEqualTo(ActionClass.IGNORED);
  }

  @Test
  @DisplayName("walking, banking, inventory, object, and unrelated NPC actions are unsupported")
  void unsupportedActionsRejected() {
    PlayerAction walk =
        new PlayerAction(100, 0, "Walk here", "Somewhere", "WALK", 0, 100, 100, 100);
    PlayerAction drop =
        new PlayerAction(100, 0, "Drop", "Shrimps", "ITEM_FIFTH_OPTION", 1, 1, 0, 100);
    PlayerAction bank =
        new PlayerAction(100, 0, "Bank", "Bank booth", "GAME_OBJECT_FIRST_OPTION", 1, 1, 0, 100);
    PlayerAction useItem =
        new PlayerAction(100, 0, "Use", "Net", "ITEM_USE", 1, 1, 0, 100);
    PlayerAction unrelatedNpc =
        new PlayerAction(100, 0, "Attack", "Goblin", "NPC_FIRST_OPTION", 17, 17, 0, 100);

    assertThat(FishingActionNormalizer.classify(walk).actionClass()).isEqualTo(ActionClass.UNSUPPORTED);
    assertThat(FishingActionNormalizer.classify(drop).actionClass()).isEqualTo(ActionClass.UNSUPPORTED);
    assertThat(FishingActionNormalizer.classify(bank).actionClass()).isEqualTo(ActionClass.UNSUPPORTED);
    assertThat(FishingActionNormalizer.classify(useItem).actionClass()).isEqualTo(ActionClass.UNSUPPORTED);
    assertThat(FishingActionNormalizer.classify(unrelatedNpc).actionClass()).isEqualTo(ActionClass.UNSUPPORTED);
  }

  @Test
  @DisplayName("a fishing option on a non-fishing target is unsupported")
  void fishingOptionOnWrongTargetUnsupported() {
    PlayerAction action =
        new PlayerAction(100, 0, "Net", "Goblin", "NPC_FIRST_OPTION", 17, 17, 0, 100);
    assertThat(FishingActionNormalizer.classify(action).actionClass())
        .isEqualTo(ActionClass.UNSUPPORTED);
  }
}
