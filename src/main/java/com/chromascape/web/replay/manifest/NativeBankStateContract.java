package com.chromascape.web.replay.manifest;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Closed native bank inputs, not a claim that a world or gameplay method is qualified. */
final class NativeBankStateContract {
  private static final Set<String> BANK_FIELDS =
      Set.of(
          "capacity",
          "slots",
          "withdrawNotes",
          "insertMode",
          "placeholderMode",
          "quantityMode",
          "customQuantity",
          "currentTab",
          "tabDisplayMode");
  private static final Set<String> SLOT_FIELDS =
      Set.of("slot", "itemId", "quantity", "placeholder", "filler", "tab");

  private NativeBankStateContract() {}

  static void validateInitialState(JsonNode state) {
    if (state.has("travelProfile")
        && (!state.path("travelProfile").isTextual()
            || !"jewelry-v1".equals(state.path("travelProfile").asText())
            || state.hasNonNull("questProfile"))) {
      fail("travel requires the exact jewelry-v1 profile without a quest composition");
    }
    if (state.has("bankOpen") && (!state.path("bankOpen").isBoolean() || !state.has("bank"))) {
      fail("bankOpen requires explicit boolean evidence and a complete bank");
    }
    if (!state.has("bank")) {
      return;
    }
    JsonNode bank = state.path("bank");
    if (state.hasNonNull("questProfile")
        || !exact(bank, BANK_FIELDS)
        || !integer(bank.path("capacity"), 1, 1410)
        || !bank.path("slots").isArray()
        || bank.path("slots").size() > bank.path("capacity").asInt()
        || !integer(bank.path("quantityMode"), 0, 5)
        || !integer(bank.path("customQuantity"), 0, Integer.MAX_VALUE)
        || !integer(bank.path("currentTab"), 0, 9)
        || !integer(bank.path("tabDisplayMode"), 0, 3)
        || !bank.path("withdrawNotes").isBoolean()
        || !bank.path("insertMode").isBoolean()
        || !bank.path("placeholderMode").isBoolean()) {
      fail("complete closed native bank state is required");
    }
    Set<Integer> slots = new HashSet<>();
    for (JsonNode slot : bank.path("slots")) {
      if (!exact(slot, SLOT_FIELDS)
          || !integer(slot.path("slot"), 0, bank.path("capacity").asInt() - 1)
          || !slots.add(slot.path("slot").asInt())
          || !integer(slot.path("itemId"), 1, Integer.MAX_VALUE)
          || !integer(slot.path("quantity"), 0, Integer.MAX_VALUE)
          || !integer(slot.path("tab"), 0, 9)
          || !slot.path("placeholder").isBoolean()
          || !slot.path("filler").isBoolean()
          || slot.path("quantity").asLong() == 0
              && !slot.path("placeholder").asBoolean()
              && !slot.path("filler").asBoolean()) {
        fail(
            "bank slots need unique exact indices, items, quantities and placeholder/filler state");
      }
    }
    JsonNode objects = state.path("objects");
    if (!objects.isArray() || objects.size() > 64) {
      fail("bank requires a bounded array of pinned source objects");
    }
    Set<String> identities = new HashSet<>();
    for (JsonNode object : objects) {
      if (!object.isObject()
          || !identity(object.path("stableId"))
          || !identities.add(object.path("stableId").asText())
          || !integer(object.path("locId"), 1, Integer.MAX_VALUE)
          || !position(object.path("position"))
          || !integer(object.path("shape"), 0, 22)
          || !integer(object.path("rotation"), 0, 3)) {
        fail(
            "bank objects require unique source identities, cache locId, position, shape and"
                + " rotation");
      }
    }
  }

  private static boolean exact(JsonNode value, Set<String> fields) {
    if (!value.isObject() || value.size() != fields.size()) {
      return false;
    }
    for (String field : fields) {
      if (!value.has(field)) {
        return false;
      }
    }
    return true;
  }

  private static boolean integer(JsonNode value, long min, long max) {
    return value.isIntegralNumber()
        && value.canConvertToLong()
        && value.asLong() >= min
        && value.asLong() <= max;
  }

  private static boolean position(JsonNode value) {
    return value.isObject()
        && integer(value.path("x"), 0, 16383)
        && integer(value.path("y"), 0, 16383)
        && integer(value.path("plane"), 0, 3);
  }

  private static boolean identity(JsonNode value) {
    if (!value.isTextual()) {
      return false;
    }
    String text = value.asText();
    return !text.isBlank()
        && text.length() <= 128
        && text.equals(text.strip())
        && text.chars().noneMatch(Character::isISOControl);
  }

  private static void fail(String reason) {
    throw ReplayException.invalid(
        ReplayErrorCode.VALIDATION_ERROR, "NATIVE_BANK_STATE_INVALID: " + reason);
  }
}
