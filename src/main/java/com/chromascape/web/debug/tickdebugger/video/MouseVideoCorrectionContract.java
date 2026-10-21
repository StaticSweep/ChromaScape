package com.chromascape.web.debug.tickdebugger.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Versioned, server-authoritative correction contract for external-video review.
 *
 * <p>The frontend is never the only validator. A correction is accepted only when its kind is
 * declared, every field is known, coordinates are finite and inside the declared source bounds, the
 * referenced frame ordinal exists in the frozen analysis frame index, and the payload stays inside
 * the count and encoded-byte caps. Fields that would invent native telemetry or physical input are
 * rejected outright: video review may record an estimate or an explicit unknown, never a fabricated
 * game tick, click or control action.
 */
public final class MouseVideoCorrectionContract {

  /** Stable contract identity persisted with every accepted revision. */
  public static final String VERSION = "corrections/v1";

  public static final int MAX_CORRECTIONS = 2_000;
  public static final int MAX_ENCODED_BYTES = 512 * 1024;
  public static final int MAX_NOTE_LENGTH = 280;
  public static final int MAX_LABEL_LENGTH = 80;

  public static final String KIND_CURSOR_VISIBILITY = "CURSOR_VISIBILITY";
  public static final String KIND_PAUSE = "PAUSE";
  public static final String KIND_SCENE_CUT = "SCENE_CUT";
  public static final String KIND_ACTION_INDICATOR = "ACTION_INDICATOR";
  public static final String KIND_TARGET_REGION = "TARGET_REGION";
  public static final String KIND_CAMERA_ACTIVITY = "CAMERA_ACTIVITY";

  private static final Set<String> KINDS =
      Set.of(
          KIND_CURSOR_VISIBILITY,
          KIND_PAUSE,
          KIND_SCENE_CUT,
          KIND_ACTION_INDICATOR,
          KIND_TARGET_REGION,
          KIND_CAMERA_ACTIVITY);

  private static final Set<String> VISIBILITY =
      Set.of("VISIBLE", "HIDDEN", "AMBIGUOUS", "UNSUPPORTED");

  private static final Set<String> INDICATORS =
      Set.of("MENU_OPEN", "MENU_CLOSE", "HIGHLIGHT_ON", "HIGHLIGHT_OFF", "CURSOR_STATE_CHANGE");

  private static final Set<String> ACTIVITY = Set.of("VIEWPORT_MOTION", "REGION_CHANGE", "NONE");

  private static final Set<String> COMMON_FIELDS = Set.of("kind", "note", "confidence");

  private static final Map<String, Set<String>> KIND_FIELDS =
      Map.of(
          KIND_CURSOR_VISIBILITY,
          Set.of("frameOrdinal", "visibility", "x", "y", "unknownReason"),
          KIND_PAUSE,
          Set.of("startFrameOrdinal", "endFrameOrdinal"),
          KIND_SCENE_CUT,
          Set.of("beforeFrameOrdinal", "afterFrameOrdinal"),
          KIND_ACTION_INDICATOR,
          Set.of("frameOrdinal", "indicator"),
          KIND_TARGET_REGION,
          Set.of("frameOrdinal", "x", "y", "width", "height", "label"),
          KIND_CAMERA_ACTIVITY,
          Set.of("frameOrdinal", "activity", "x", "y", "width", "height"));

  /**
   * Fields that would claim native or physical evidence this pipeline cannot observe. The list is
   * applied to every nested object so a caller cannot smuggle them through a nested blob.
   */
  private static final Set<String> REJECTED_FIELDS =
      Set.of(
          "tick",
          "gametick",
          "nativetick",
          "nativeticks",
          "physicalclick",
          "physicalinput",
          "mousebutton",
          "buttonpress",
          "pollingrate",
          "pollrate",
          "nativetelemetry",
          "exactticks",
          "controlaction",
          "controlallowed",
          "trainingeligible");

  /** Bounds a correction must respect: the frozen frame count and declared source dimensions. */
  public record Bounds(long frameCount, int maxWidth, int maxHeight) {}

  /** Normalized corrections in canonical order plus the digest used for retry comparison. */
  public record Validated(ArrayNode corrections, String digest, String version, int count) {}

  private MouseVideoCorrectionContract() {}

  /**
   * Validates and normalizes a correction array against the frozen analysis bounds.
   *
   * @throws MouseVideoException {@code INVALID_REQUEST} for any unsupported, malformed or
   *     out-of-bounds correction
   */
  public static Validated validate(JsonNode corrections, Bounds bounds) {
    if (corrections == null || corrections.isNull()) {
      return new Validated(
          JsonNodeFactory.instance.arrayNode(),
          digestOf(JsonNodeFactory.instance.arrayNode()),
          VERSION,
          0);
    }
    if (!corrections.isArray()) {
      throw invalid("Corrections must be a JSON array");
    }
    if (corrections.isEmpty()) {
      ArrayNode none = JsonNodeFactory.instance.arrayNode();
      return new Validated(none, digestOf(none), VERSION, 0);
    }
    if (bounds == null || bounds.frameCount() <= 0) {
      throw invalid("Corrections require a completed frame index for this analysis");
    }
    if (corrections.size() > MAX_CORRECTIONS) {
      throw invalid("A review may carry at most " + MAX_CORRECTIONS + " corrections");
    }
    rejectInventedFields(corrections);
    ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (int index = 0; index < corrections.size(); index++) {
      JsonNode entry = corrections.get(index);
      if (entry == null || !entry.isObject()) {
        throw invalid("Correction " + index + " must be a JSON object");
      }
      ObjectNode shape = normalize(entry, index, bounds);
      String identity = shape.toString();
      if (!seen.add(identity)) {
        throw invalid("Correction " + index + " duplicates an earlier identical correction");
      }
      normalized.add(shape);
    }
    int encoded = normalized.toString().getBytes(StandardCharsets.UTF_8).length;
    if (encoded > MAX_ENCODED_BYTES) {
      throw invalid("Corrections exceed the " + MAX_ENCODED_BYTES + "-byte request bound");
    }
    return new Validated(normalized, digestOf(normalized), VERSION, normalized.size());
  }

  /** Canonical digest over the contract version and a key-sorted correction projection. */
  public static String digestOf(JsonNode corrections) {
    String canonical = VERSION + '\n' + canonical(corrections);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:"
          + HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  private static void rejectInventedFields(JsonNode node) {
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (REJECTED_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
          throw invalid(
              "Field '"
                  + field.getKey()
                  + "' would claim native or physical evidence that video review cannot observe");
        }
        rejectInventedFields(field.getValue());
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        rejectInventedFields(child);
      }
    }
  }

  private static ObjectNode normalize(JsonNode entry, int index, Bounds bounds) {
    String kind = text(entry, "kind", index);
    if (kind == null) {
      throw invalid("Correction " + index + " requires a kind");
    }
    String upper = kind.toUpperCase(Locale.ROOT);
    if (!KINDS.contains(upper)) {
      throw invalid("Correction " + index + " has an unsupported kind '" + kind + "'");
    }
    Set<String> allowed = new LinkedHashSet<>(COMMON_FIELDS);
    allowed.addAll(KIND_FIELDS.get(upper));
    var names = entry.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!allowed.contains(name)) {
        throw invalid("Correction " + index + " carries unknown field '" + name + "'");
      }
    }
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", upper);
    switch (upper) {
      case KIND_CURSOR_VISIBILITY -> normalizeCursor(entry, index, bounds, node);
      case KIND_PAUSE -> normalizePause(entry, index, bounds, node);
      case KIND_SCENE_CUT -> normalizeCut(entry, index, bounds, node);
      case KIND_ACTION_INDICATOR -> normalizeIndicator(entry, index, bounds, node);
      case KIND_TARGET_REGION -> normalizeRegion(entry, index, bounds, node);
      case KIND_CAMERA_ACTIVITY -> normalizeActivity(entry, index, bounds, node);
      default -> throw invalid("Correction " + index + " has an unsupported kind");
    }
    node.put("confidence", confidence(entry, index));
    String note = optionalText(entry, "note", index, MAX_NOTE_LENGTH);
    if (note != null) {
      node.put("note", note);
    }
    return node;
  }

  private static void normalizeCursor(JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    long ordinal = ordinal(entry, "frameOrdinal", index, bounds);
    node.put("frameOrdinal", ordinal);
    String visibility = text(entry, "visibility", index);
    if (visibility == null) {
      throw invalid("Correction " + index + " requires a visibility");
    }
    String upper = visibility.toUpperCase(Locale.ROOT);
    if (!VISIBILITY.contains(upper)) {
      throw invalid("Correction " + index + " has an unsupported visibility '" + visibility + "'");
    }
    node.put("visibility", upper);
    if ("VISIBLE".equals(upper)) {
      node.put("x", coordinate(entry, "x", index, bounds.maxWidth()));
      node.put("y", coordinate(entry, "y", index, bounds.maxHeight()));
    } else {
      rejectPresent(entry, index, "x", "y");
      String unknownReason = optionalText(entry, "unknownReason", index, MAX_NOTE_LENGTH);
      if (unknownReason == null) {
        throw invalid("Correction " + index + " must state a reason for a non-visible frame");
      }
      node.put("unknownReason", unknownReason);
    }
  }

  private static void normalizePause(JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    long start = ordinal(entry, "startFrameOrdinal", index, bounds);
    long end = ordinal(entry, "endFrameOrdinal", index, bounds);
    if (end < start) {
      throw invalid("Correction " + index + " pause ends before it starts");
    }
    node.put("startFrameOrdinal", start);
    node.put("endFrameOrdinal", end);
  }

  private static void normalizeCut(JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    long before = ordinal(entry, "beforeFrameOrdinal", index, bounds);
    long after = ordinal(entry, "afterFrameOrdinal", index, bounds);
    if (after != before + 1) {
      throw invalid("Correction " + index + " must reference adjacent frames for a cut");
    }
    node.put("beforeFrameOrdinal", before);
    node.put("afterFrameOrdinal", after);
  }

  private static void normalizeIndicator(
      JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    node.put("frameOrdinal", ordinal(entry, "frameOrdinal", index, bounds));
    String indicator = text(entry, "indicator", index);
    if (indicator == null) {
      throw invalid("Correction " + index + " requires an indicator");
    }
    String upper = indicator.toUpperCase(Locale.ROOT);
    if (!INDICATORS.contains(upper)) {
      throw invalid("Correction " + index + " has an unsupported indicator '" + indicator + "'");
    }
    node.put("indicator", upper);
  }

  private static void normalizeRegion(JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    long ordinal = ordinal(entry, "frameOrdinal", index, bounds);
    node.put("frameOrdinal", ordinal);
    putRegion(entry, index, bounds, node);
    String label = optionalText(entry, "label", index, MAX_LABEL_LENGTH);
    if (label != null) {
      node.put("label", label);
    }
  }

  private static void normalizeActivity(JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    long ordinal = ordinal(entry, "frameOrdinal", index, bounds);
    node.put("frameOrdinal", ordinal);
    String activity = text(entry, "activity", index);
    if (activity == null) {
      throw invalid("Correction " + index + " requires an activity");
    }
    String upper = activity.toUpperCase(Locale.ROOT);
    if (!ACTIVITY.contains(upper)) {
      throw invalid("Correction " + index + " has an unsupported activity '" + activity + "'");
    }
    node.put("activity", upper);
    if ("NONE".equals(upper)) {
      rejectPresent(entry, index, "x", "y", "width", "height");
    } else {
      putRegion(entry, index, bounds, node);
    }
  }

  private static void putRegion(JsonNode entry, int index, Bounds bounds, ObjectNode node) {
    int x = coordinate(entry, "x", index, bounds.maxWidth());
    int y = coordinate(entry, "y", index, bounds.maxHeight());
    int width = size(entry, "width", index, bounds.maxWidth());
    int height = size(entry, "height", index, bounds.maxHeight());
    if (x + width > bounds.maxWidth() || y + height > bounds.maxHeight()) {
      throw invalid("Correction " + index + " region exceeds the declared source bounds");
    }
    node.put("x", x);
    node.put("y", y);
    node.put("width", width);
    node.put("height", height);
  }

  private static void rejectPresent(JsonNode entry, int index, String... names) {
    for (String name : names) {
      if (entry.has(name) && !entry.get(name).isNull()) {
        throw invalid("Correction " + index + " must not carry '" + name + "' for this kind");
      }
    }
  }

  private static long ordinal(JsonNode entry, String name, int index, Bounds bounds) {
    JsonNode value = entry.get(name);
    if (value == null || !value.isIntegralNumber()) {
      throw invalid("Correction " + index + " requires integer '" + name + "'");
    }
    long ordinal = value.longValue();
    if (ordinal < 0 || ordinal >= bounds.frameCount()) {
      throw invalid(
          "Correction "
              + index
              + " references frame "
              + ordinal
              + " outside the indexed range 0.."
              + (bounds.frameCount() - 1));
    }
    return ordinal;
  }

  private static int coordinate(JsonNode entry, String name, int index, int limit) {
    JsonNode value = entry.get(name);
    if (value == null || !value.isNumber()) {
      throw invalid("Correction " + index + " requires numeric '" + name + "'");
    }
    double number = value.doubleValue();
    if (!Double.isFinite(number) || number < 0 || number >= limit) {
      throw invalid("Correction " + index + " requires '" + name + "' inside 0.." + (limit - 1));
    }
    return (int) Math.round(number);
  }

  private static int size(JsonNode entry, String name, int index, int limit) {
    JsonNode value = entry.get(name);
    if (value == null || !value.isNumber()) {
      throw invalid("Correction " + index + " requires numeric '" + name + "'");
    }
    double number = value.doubleValue();
    if (!Double.isFinite(number) || number <= 0 || number > limit) {
      throw invalid("Correction " + index + " requires '" + name + "' inside 1.." + limit);
    }
    return (int) Math.round(number);
  }

  private static double confidence(JsonNode entry, int index) {
    JsonNode value = entry.get("confidence");
    if (value == null || value.isNull()) {
      return 1.0d;
    }
    if (!value.isNumber()) {
      throw invalid("Correction " + index + " requires numeric 'confidence'");
    }
    double number = value.doubleValue();
    if (!Double.isFinite(number) || number < 0.0d || number > 1.0d) {
      throw invalid("Correction " + index + " requires confidence inside 0..1");
    }
    return number;
  }

  private static String text(JsonNode entry, String name, int index) {
    JsonNode value = entry.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw invalid("Correction " + index + " requires textual '" + name + "'");
    }
    String text = value.textValue().trim();
    return text.isEmpty() ? null : text;
  }

  private static String optionalText(JsonNode entry, String name, int index, int maxLength) {
    String text = text(entry, name, index);
    if (text == null) {
      return null;
    }
    if (text.length() > maxLength) {
      throw invalid(
          "Correction " + index + " field '" + name + "' exceeds " + maxLength + " characters");
    }
    return text;
  }

  /** Deterministic key-sorted serialization used for the retry digest. */
  private static String canonical(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    if (node.isObject()) {
      Map<String, String> sorted = new TreeMap<>();
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        sorted.put(field.getKey(), canonical(field.getValue()));
      }
      StringBuilder builder = new StringBuilder("{");
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, String> entry : sorted.entrySet()) {
        parts.add(quote(entry.getKey()) + ':' + entry.getValue());
      }
      builder.append(String.join(",", parts)).append('}');
      return builder.toString();
    }
    if (node.isArray()) {
      StringBuilder builder = new StringBuilder("[");
      List<String> parts = new ArrayList<>();
      for (JsonNode child : node) {
        parts.add(canonical(child));
      }
      builder.append(String.join(",", parts)).append(']');
      return builder.toString();
    }
    if (node.isNumber()) {
      try {
        return new java.math.BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
      } catch (NumberFormatException notDecimal) {
        return node.asText();
      }
    }
    if (node.isBoolean()) {
      return Boolean.toString(node.booleanValue());
    }
    return quote(node.asText());
  }

  private static String quote(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 2);
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (character < 0x20) {
            builder.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            builder.append(character);
          }
        }
      }
    }
    return builder.append('"').toString();
  }

  private static MouseVideoException invalid(String message) {
    return new MouseVideoException(MouseVideoException.CODE_INVALID_REQUEST, message);
  }

  /** Ordered view used by docs and tests: declared kinds in contract order. */
  public static List<String> declaredKinds() {
    return List.copyOf(new LinkedHashMap<>(KIND_FIELDS).keySet());
  }
}
