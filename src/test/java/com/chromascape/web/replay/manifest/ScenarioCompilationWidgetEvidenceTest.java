package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.conversion.*;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ScenarioCompilationWidgetEvidenceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final ScenarioCompilationEvidence evidence =
      new ScenarioCompilationEvidence(json, new CanonicalProjectionService());

  private ObjectNode widget() {
    return json.createObjectNode()
        .put("stableId", "container:bank:1:3867")
        .put("widgetId", 786444)
        .put("widgetIndex", 1);
  }

  private ObjectNode world(ObjectNode widget, Object index) {
    var snapshot = new TickSnapshot();
    snapshot.setSessionId("authored-widget-source");
    snapshot.setTick(7L);
    var player = json.createObjectNode();
    player.putObject("position").put("x", 3090).put("y", 3243).put("plane", 0);
    snapshot.setPlayerState(player);
    snapshot.setWidgets(json.createArrayNode().add(widget));
    var action = new RecordingAction();
    action.setActionType("INTERACT_WIDGET");
    action.setTick(7);
    action.setSequence(3);
    action.setTarget(Map.of("stableId", "container:bank:1:3867"));
    action.setArguments(Map.of("widgetId", 786444, "widgetIndex", index, "option", "withdraw-1"));
    var conversion =
        new ReplayActionConversionResult(
            "authored-widget-source",
            7,
            7,
            ConversionMode.AUTO,
            ConversionQuality.BEST_EFFORT,
            List.of(
                new ConvertedReplayAction(
                    action, ActionProvenance.CAPTURED, "authored-test-only", null, null)),
            List.of(),
            List.of(),
            1,
            0,
            100,
            "sha256:authored-widget");
    return evidence.world(
        List.of(snapshot),
        conversion,
        CapabilityManifest.current(
            new CapabilityManifest.CapabilityModes(true, true, true),
            new CapabilityManifest.XrspsIdentity(
                "authored", "authored", "sha256:cache", "sha256:content"),
            false));
  }

  @Test
  void exactPrimaryOrLegacyIndexAliasKeepsOriginalSourceReference() {
    ObjectNode source = widget(), before = source.deepCopy();
    assertThat(world(source, 1).path("actionTargets").get(0).path("sourceRef").asText())
        .matches("snapshot:sha256:[0-9a-f]{64}/action:3");
    assertThat(source).isEqualTo(before);
    source.put("index", 1);
    world(source, 1);
    source.remove("widgetIndex");
    world(source, 1);
  }

  @Test
  void missingStaleFractionalConflictingOrOverflowingCapturedIndexFailsWithSource() {
    for (Consumer<ObjectNode> mutation :
        List.<Consumer<ObjectNode>>of(
            row -> row.remove("widgetIndex"), row -> row.put("widgetIndex", 0),
            row -> row.put("widgetIndex", 1.5), row -> row.put("widgetIndex", "1"),
            row -> row.put("widgetIndex", 4294967297L), row -> row.put("index", 0),
            row -> row.put("index", 1.5), row -> row.put("index", "1"))) {
      ObjectNode source = widget();
      mutation.accept(source);
      assertThatThrownBy(() -> world(source, 1))
          .isInstanceOf(ReplayException.class)
          .hasMessageContaining("exact unambiguous captured widget index")
          .hasMessageContaining("source=snapshot:sha256:");
    }
  }

  @Test
  void fractionalRequestedIndexIsNeverTruncated() {
    assertThatThrownBy(() -> world(widget(), 1.5))
        .isInstanceOf(ReplayException.class)
        .hasMessageContaining("exact unambiguous captured widget index");
  }

  @Test
  void capturedWidgetIdCannotWrapThroughIntNarrowing() {
    ObjectNode source = widget().put("widgetId", 4295753740L);
    assertThatThrownBy(() -> world(source, 1))
        .isInstanceOf(ReplayException.class)
        .hasMessageContaining("exact captured widget identity");
  }
}
