package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.replay.conversion.*;
import com.chromascape.web.replay.curation.entity.*;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishedCurationSnapshotTest {
  private final ObjectMapper json = new ObjectMapper();
  private final CanonicalProjectionService canonical = new CanonicalProjectionService();

  private RecordingCuration row() {
    var row = new RecordingCuration();
    row.setId(UUID.fromString("b755d90e-013f-4d11-a678-142dd3cd9a1b"));
    row.setRecordingId("recording");
    row.setDebugSessionId("session");
    row.setCreatedBy("operator");
    row.setVersion(3);
    row.setState(CurationState.PUBLISHED);
    row.setRole(CurationRole.HELD_OUT);
    ObjectNode content = json.createObjectNode();
    content.putObject("tickRange").put("start", 5).put("end", 8);
    content.putObject("initialStateOverrides");
    content.putArray("actionLabels");
    row.setContentJson(content);
    row.setContentChecksum("sha256:legacy-serialization-order-address");
    return row;
  }

  private PublishedCurationSnapshot freeze(RecordingCuration row) {
    return new PublishedCurationSnapshot(row, "recording", "session", "operator", json, canonical);
  }

  private ReplayActionConversionResult raw() {
    var action = new RecordingAction();
    action.setTick(5);
    action.setSequence(0);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("stableId", "npc:spot"));
    action.setArguments(Map.of("option", "net"));
    return new ReplayActionConversionResult(
        "session",
        5,
        8,
        ConversionMode.AUTO,
        ConversionQuality.CERTIFIED,
        List.of(new ConvertedReplayAction(action, ActionProvenance.CAPTURED, "typed", null, 7L)),
        List.of(
            new ReplayGap(6, "MISSING_SOURCE", "original gap", ActionProvenance.CAPTURED, null)),
        List.of(),
        1,
        2,
        50,
        "sha256:raw");
  }

  private ObjectNode label(RecordingCuration row, long tick, int sequence, String status) {
    return ((com.fasterxml.jackson.databind.node.ArrayNode)
            row.getContentJson().path("actionLabels"))
        .addObject()
        .put("tick", tick)
        .put("sequence", sequence)
        .put("status", status);
  }

  @Test
  void freezesCompletePublishedContentWithoutRewritingLegacyAddressOrRows() {
    var row = row();
    var frozen = freeze(row);
    var original = row.getContentJson().deepCopy();
    assertThat(frozen.source().path("checksum").asText()).isEqualTo(row.getContentChecksum());
    assertThat(frozen.source().path("contentSnapshot")).isEqualTo(original);
    assertThat(frozen.source().path("canonicalContentChecksum").asText())
        .isEqualTo(canonical.sha256Hex(canonical.canonicalJsonBytes(original)));
    ((ObjectNode) row.getContentJson()).put("laterMutation", true);
    frozen.source().remove("contentSnapshot");
    assertThat(frozen.source().path("contentSnapshot")).isEqualTo(original);
  }

  @Test
  void ownershipRecordingSessionPublicationAndConverterRowsFailClosed() {
    for (int variant = 0; variant < 5; variant++) {
      var row = row();
      switch (variant) {
        case 0 -> row.setCreatedBy("other");
        case 1 -> row.setRecordingId("other");
        case 2 -> row.setDebugSessionId("other");
        case 3 -> row.setState(CurationState.DRAFT);
        default -> row.setCreatedBy(ScenarioManifestV2Compiler.CONVERSION_CREATED_BY);
      }
      assertThrows(ReplayException.class, () -> freeze(row));
    }
  }

  @Test
  void
      selectedLabelsReplaceExactKeysWithOperatorProvenanceWithoutImprovingRawCoverageOrClearingGaps() {
    var row = row();
    var label = label(row, 5, 0, "ACCEPTED_LABEL");
    label.put("semanticActionType", "CAST_SPELL").put("targetStableId", "inventory:3:1205");
    label.putObject("argumentsJson").put("spellId", 9111).put("slot", 3).put("itemId", 1205);
    label.putObject("evidenceRef").put("fixture", "authored-test-only");
    var before = raw();
    var result = freeze(row).apply(before);
    assertThat(result.actions()).hasSize(1);
    assertThat(result.actions().get(0).action().getActionType()).isEqualTo("CAST_SPELL");
    assertThat(result.actions().get(0).provenance()).isEqualTo(ActionProvenance.OPERATOR_SUPPLIED);
    assertThat(result.actions().get(0).provenanceReason()).endsWith("/content/actionLabels/0");
    assertThat(result.quality()).isEqualTo(ConversionQuality.BEST_EFFORT);
    assertThat(result.actionCoveragePct()).isEqualTo(50);
    assertThat(result.gaps()).isEqualTo(before.gaps());
    assertThat(before.actions().get(0).action().getActionType()).isEqualTo("INTERACT_NPC");
    assertThat(freeze(row).apply(before).conversionChecksum())
        .isEqualTo(result.conversionChecksum());
  }

  @Test
  void ignoredOrUnsupportedCapturedDispatchesRemainExplicitGaps() {
    for (String status : List.of("IGNORED", "UNSUPPORTED")) {
      var row = row();
      label(row, 5, 0, status);
      var result = freeze(row).apply(raw());
      assertThat(result.actions()).isEmpty();
      assertThat(result.gaps()).hasSize(2);
      assertThat(result.gaps().get(1).code()).isEqualTo("CURATION_" + status);
      assertThat(result.actionCoveragePct()).isEqualTo(50);
    }
  }

  @Test
  void duplicateReorderedAndOutOfRangeLabelsDoNotBecomeAnOrderedFiction() {
    for (int variant = 0; variant < 3; variant++) {
      var row = row();
      label(row, 6, 0, "IGNORED");
      label(row, variant == 0 ? 6 : variant == 1 ? 5 : 9, 0, "IGNORED");
      assertThrows(ReplayException.class, () -> freeze(row).apply(raw()));
    }
  }

  @Test
  void acceptedLabelsRequireAuditEvidenceAndExactArguments() {
    var row = row();
    var label = label(row, 5, 0, "ACCEPTED_LABEL");
    label.put("semanticActionType", "CAST_SPELL").put("targetStableId", "inventory:3:1205");
    label.putObject("argumentsJson").put("spellId", 9111);
    assertThrows(ReplayException.class, () -> freeze(row).apply(raw()));
  }

  @Test
  void operatorStateNeverRetainsTheCapturedOnlyQualityClaim() {
    var row = row();
    ((ObjectNode) row.getContentJson().path("initialStateOverrides"))
        .putObject("bank")
        .putArray("items");
    var result = freeze(row).apply(raw());
    assertThat(result.quality()).isEqualTo(ConversionQuality.BEST_EFFORT);
    assertThat(result.actionCoveragePct()).isEqualTo(raw().actionCoveragePct());
    assertThat(result.gaps()).isEqualTo(raw().gaps());
  }

  @Test
  void explicitOverridesMergeIntoCopyWithoutClobberingOtherRecordedFields() {
    var row = row();
    ((ObjectNode) row.getContentJson().path("initialStateOverrides"))
        .putObject("player")
        .putObject("skills")
        .putObject("magic")
        .put("level", 55)
        .put("xp", 166636);
    ObjectNode base = json.createObjectNode();
    base.putObject("player").putObject("skills").putObject("fishing").put("level", 1).put("xp", 0);
    var result = freeze(row).overrideInitialState(base);
    assertThat(result.path("player").path("skills").has("fishing")).isTrue();
    assertThat(result.path("player").path("skills").path("magic").path("level").asInt())
        .isEqualTo(55);
    assertThat(base.path("player").path("skills").has("magic")).isFalse();
    ((ObjectNode) row.getContentJson().path("initialStateOverrides"))
        .put("endpoint", "https://example.invalid");
    assertThrows(ReplayException.class, () -> freeze(row).overrideInitialState(base));
  }

  @Test
  void nativeWorldCurationIsClosedPinnedAndFrozenRatherThanAnUncheckedOverrideEscape() {
    var row = row();
    ObjectNode patch = (ObjectNode) row.getContentJson().path("initialStateOverrides");
    patch
        .putObject("nativeFishingWorld")
        .put("schema", "xrsps-native-fishing-world/v1")
        .put("catalogChecksum", "sha256:" + "a".repeat(64))
        .putArray("spawnIndices")
        .add(7);
    ObjectNode base = json.createObjectNode();
    base.putArray("npcs");
    base.put("untouched", true);
    var frozen = freeze(row);
    ObjectNode result = frozen.overrideInitialState(base);
    assertThat(result.path("untouched").asBoolean()).isTrue();
    assertThat(base.has("nativeFishingWorld")).isFalse();
    ((ObjectNode) patch.path("nativeFishingWorld")).put("script", "no");
    assertThat(frozen.overrideInitialState(base)).isEqualTo(result);
    assertThrows(ReplayException.class, () -> freeze(row).overrideInitialState(base));
    assertThat(frozen.apply(raw()).quality()).isEqualTo(ConversionQuality.BEST_EFFORT);
    assertThat(frozen.apply(raw()).gaps()).isEqualTo(raw().gaps());
  }
}
