package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Server-side correction validation: declared kinds, known fields, finite bounds, frame member. */
class MouseVideoCorrectionContractTest {

  private static final MouseVideoCorrectionContract.Bounds BOUNDS =
      new MouseVideoCorrectionContract.Bounds(100L, 1920, 1080);

  private final ObjectMapper mapper = new ObjectMapper();

  private static ObjectNode cursorCorrection() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "cursor_visibility");
    node.put("frameOrdinal", 12);
    node.put("visibility", "visible");
    node.put("x", 640);
    node.put("y", 360);
    return node;
  }

  @Test
  void provenanceIsVersionedPreservedAndNeverCertifiesIndependence() {
    ObjectNode legacy = cursorCorrection();
    var original =
        MouseVideoCorrectionContract.validate(
            JsonNodeFactory.instance.arrayNode().add(legacy), BOUNDS);
    assertThat(original.version()).isEqualTo("corrections/v1");
    assertThat(original.digest())
        .isEqualTo("sha256:e40cfeacfd457f25bf9398502fc0f64dde7811338433738a521bc9b45a08eea9");
    for (String provenance : new String[] {"UNKNOWN", "BLIND_UNVERIFIED", "DETECTOR_ASSISTED"}) {
      ObjectNode entry = cursorCorrection();
      entry.put("provenance", provenance);
      var validated =
          MouseVideoCorrectionContract.validate(
              JsonNodeFactory.instance.arrayNode().add(entry), BOUNDS);
      assertThat(validated.version()).isEqualTo("corrections/v2");
      assertThat(validated.corrections().get(0).path("provenance").asText()).isEqualTo(provenance);
      assertThat(validated.digest()).isNotEqualTo(original.digest());
    }
    for (String invalid : new String[] {"INDEPENDENT", "independent", "", "CERTIFIED"}) {
      ObjectNode entry = cursorCorrection();
      entry.put("provenance", invalid);
      assertInvalid(entry);
    }
    ObjectNode malformed = cursorCorrection();
    malformed.put("provenance", true);
    assertInvalid(malformed);
    assertThat(
            MouseVideoCorrectionContract.validate(
                    JsonNodeFactory.instance.arrayNode().add(legacy), BOUNDS)
                .digest())
        .isEqualTo(original.digest());
  }

  @Test
  void normalizesDeclaredKindAndDefaultsConfidence() {
    ArrayNode corrections = JsonNodeFactory.instance.arrayNode().add(cursorCorrection());

    MouseVideoCorrectionContract.Validated validated =
        MouseVideoCorrectionContract.validate(corrections, BOUNDS);

    JsonNode entry = validated.corrections().get(0);
    assertThat(entry.get("kind").asText()).isEqualTo("CURSOR_VISIBILITY");
    assertThat(entry.get("visibility").asText()).isEqualTo("VISIBLE");
    assertThat(entry.get("confidence").asDouble()).isEqualTo(1.0d);
    assertThat(validated.count()).isEqualTo(1);
    assertThat(validated.digest()).startsWith("sha256:");
  }

  @Test
  void emptyCorrectionArrayIsAcceptedWithoutFrameIndex() {
    MouseVideoCorrectionContract.Validated validated =
        MouseVideoCorrectionContract.validate(JsonNodeFactory.instance.arrayNode(), null);

    assertThat(validated.count()).isZero();
    assertThat(validated.corrections().isEmpty()).isTrue();
  }

  @Test
  void nonEmptyCorrectionsRequireAFrameIndex() {
    ArrayNode corrections = JsonNodeFactory.instance.arrayNode().add(cursorCorrection());

    assertThatThrownBy(() -> MouseVideoCorrectionContract.validate(corrections, null))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("frame index");
  }

  @Test
  void rejectsUnsupportedKind() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "MOVE_CURSOR_TO");
    node.put("frameOrdinal", 1);

    assertInvalid(node);
  }

  @Test
  void rejectsUnknownField() {
    ObjectNode node = cursorCorrection();
    node.put("velocity", 12.5d);

    assertInvalid(node);
  }

  @Test
  void rejectsInventedNativeEvidence() {
    for (String field : new String[] {"tick", "physicalClick", "nativeTelemetry", "pollingRate"}) {
      ObjectNode node = cursorCorrection();
      node.put(field, 1);
      assertInvalid(node);
    }
  }

  @Test
  void rejectsFrameOrdinalOutsideTheFrozenIndex() {
    ObjectNode node = cursorCorrection();
    node.put("frameOrdinal", 100);

    assertInvalid(node);
  }

  @Test
  void rejectsNonFiniteOrOutOfBoundsCoordinates() {
    ObjectNode negative = cursorCorrection();
    negative.put("x", -1);
    assertInvalid(negative);

    ObjectNode beyondWidth = cursorCorrection();
    beyondWidth.put("x", 1920);
    assertInvalid(beyondWidth);

    ObjectNode beyondHeight = cursorCorrection();
    beyondHeight.put("y", 1080);
    assertInvalid(beyondHeight);
  }

  @Test
  void rejectsNonVisibleFrameWithoutAReason() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "CURSOR_VISIBILITY");
    node.put("frameOrdinal", 3);
    node.put("visibility", "HIDDEN");

    assertInvalid(node);

    node.put("unknownReason", "cursor occluded by chatbox");
    MouseVideoCorrectionContract.Validated validated =
        MouseVideoCorrectionContract.validate(
            JsonNodeFactory.instance.arrayNode().add(node), BOUNDS);
    assertThat(validated.corrections().get(0).get("unknownReason").asText())
        .isEqualTo("cursor occluded by chatbox");
  }

  @Test
  void rejectsRegionThatExceedsDeclaredSourceBounds() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "TARGET_REGION");
    node.put("frameOrdinal", 4);
    node.put("x", 1900);
    node.put("y", 100);
    node.put("width", 100);
    node.put("height", 50);

    assertInvalid(node);
  }

  @Test
  void rejectsCutThatDoesNotReferenceAdjacentFrames() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", "SCENE_CUT");
    node.put("beforeFrameOrdinal", 5);
    node.put("afterFrameOrdinal", 9);

    assertInvalid(node);
  }

  @Test
  void rejectsDuplicateCorrections() {
    ArrayNode corrections =
        JsonNodeFactory.instance.arrayNode().add(cursorCorrection()).add(cursorCorrection());

    assertThatThrownBy(() -> MouseVideoCorrectionContract.validate(corrections, BOUNDS))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("duplicates");
  }

  @Test
  void rejectsUndersizedAndOversizedConfidence() {
    ObjectNode below = cursorCorrection();
    below.put("confidence", -0.1d);
    assertInvalid(below);

    ObjectNode above = cursorCorrection();
    above.put("confidence", 1.1d);
    assertInvalid(above);
  }

  @Test
  void digestIsIndependentOfObjectKeyOrder() throws Exception {
    String first =
        "{\"kind\":\"CURSOR_VISIBILITY\",\"frameOrdinal\":7,\"visibility\":\"VISIBLE\","
            + "\"x\":10,\"y\":20,\"confidence\":0.5}";
    String second =
        "{\"confidence\":0.5,\"y\":20,\"x\":10,\"visibility\":\"VISIBLE\","
            + "\"frameOrdinal\":7,\"kind\":\"CURSOR_VISIBILITY\"}";

    MouseVideoCorrectionContract.Validated left =
        MouseVideoCorrectionContract.validate(array(first), BOUNDS);
    MouseVideoCorrectionContract.Validated right =
        MouseVideoCorrectionContract.validate(array(second), BOUNDS);

    assertThat(left.digest()).isEqualTo(right.digest());
  }

  @Test
  void digestChangesWhenThePayloadChanges() {
    ObjectNode changed = cursorCorrection();
    changed.put("x", 641);

    MouseVideoCorrectionContract.Validated left =
        MouseVideoCorrectionContract.validate(
            JsonNodeFactory.instance.arrayNode().add(cursorCorrection()), BOUNDS);
    MouseVideoCorrectionContract.Validated right =
        MouseVideoCorrectionContract.validate(
            JsonNodeFactory.instance.arrayNode().add(changed), BOUNDS);

    assertThat(left.digest()).isNotEqualTo(right.digest());
  }

  @Test
  void rejectsNonArrayPayload() throws Exception {
    assertThatThrownBy(
            () ->
                MouseVideoCorrectionContract.validate(
                    mapper.readTree("{\"kind\":\"SCENE_CUT\"}"), BOUNDS))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
  }

  @Test
  void rejectsMoreThanTheDeclaredCorrectionCount() {
    ArrayNode corrections = JsonNodeFactory.instance.arrayNode();
    for (int index = 0; index <= MouseVideoCorrectionContract.MAX_CORRECTIONS; index++) {
      ObjectNode node = cursorCorrection();
      node.put("frameOrdinal", index % 100);
      node.put("x", index % 100);
      corrections.add(node);
    }

    assertThatThrownBy(() -> MouseVideoCorrectionContract.validate(corrections, BOUNDS))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("at most");
  }

  private ArrayNode array(String json) throws Exception {
    JsonNode node = mapper.readTree(json);
    ArrayNode array = JsonNodeFactory.instance.arrayNode();
    array.add(node);
    return array;
  }

  private static void assertInvalid(ObjectNode node) {
    assertThatThrownBy(
            () ->
                MouseVideoCorrectionContract.validate(
                    JsonNodeFactory.instance.arrayNode().add(node), BOUNDS))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
  }
}
