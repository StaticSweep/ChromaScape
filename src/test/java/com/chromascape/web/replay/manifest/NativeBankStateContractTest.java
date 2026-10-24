package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NativeBankStateContractTest {
  private final ObjectMapper json = new ObjectMapper();

  private ObjectNode state() throws Exception {
    return (ObjectNode)
        json.readTree(
            """
      {"bank":{"capacity":800,"slots":[{"slot":0,"itemId":303,"quantity":2,
      "placeholder":false,"filler":false,"tab":0}],
      "withdrawNotes":false,"insertMode":false,"placeholderMode":false,"quantityMode":0,
      "customQuantity":0,"currentTab":0,"tabDisplayMode":0},"objects":[]}
      """);
  }

  private void invalid(ObjectNode state) {
    assertThatThrownBy(() -> NativeBankStateContract.validateInitialState(state))
        .isInstanceOf(ReplayException.class)
        .hasMessageContaining("NATIVE_BANK_STATE_INVALID");
  }

  @Test
  void bankIsClosedAndEveryPreferenceIsExplicit() throws Exception {
    ObjectNode state = state();
    NativeBankStateContract.validateInitialState(state);
    var fields = state.path("bank").fieldNames();
    while (fields.hasNext()) {
      ObjectNode missing = state.deepCopy();
      ((ObjectNode) missing.path("bank")).remove(fields.next());
      invalid(missing);
    }
    ObjectNode unknown = state.deepCopy();
    ((ObjectNode) unknown.path("bank")).put("catalogPath", "untrusted");
    invalid(unknown);
    for (Consumer<ObjectNode> mutation :
        List.<Consumer<ObjectNode>>of(
            bank -> bank.put("capacity", 0), bank -> bank.put("capacity", 1411),
            bank -> bank.put("quantityMode", 6), bank -> bank.put("customQuantity", 2147483648L),
            bank -> bank.put("withdrawNotes", "false"), bank -> bank.put("currentTab", 0.5),
            bank -> bank.put("tabDisplayMode", 4), bank -> bank.putNull("insertMode"))) {
      ObjectNode bad = state.deepCopy();
      mutation.accept((ObjectNode) bad.path("bank"));
      invalid(bad);
    }
  }

  @Test
  void slotsPreserveExactEmptyPlaceholderOrFillerButNotInventedItems() throws Exception {
    ObjectNode state = state();
    ObjectNode slot = (ObjectNode) state.path("bank").path("slots").get(0);
    slot.put("quantity", 0);
    invalid(state);
    slot.put("placeholder", true);
    NativeBankStateContract.validateInitialState(state);
    slot.put("placeholder", false).put("filler", true);
    NativeBankStateContract.validateInitialState(state);
    for (Consumer<ObjectNode> mutation :
        List.<Consumer<ObjectNode>>of(
            row -> row.remove("quantity"), row -> row.put("slot", 800),
            row -> row.put("slot", 0.5), row -> row.put("itemId", 0),
            row -> row.put("quantity", 2147483648L), row -> row.put("placeholder", 1),
            row -> row.put("tab", 10), row -> row.put("sourceUrl", "untrusted"))) {
      ObjectNode bad = state();
      mutation.accept((ObjectNode) bad.path("bank").path("slots").get(0));
      invalid(bad);
    }
    ObjectNode duplicate = state();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("bank").path("slots"))
        .add(duplicate.path("bank").path("slots").get(0).deepCopy());
    invalid(duplicate);
  }

  @Test
  void slotsCannotExceedCapacityOrShareAnIndex() throws Exception {
    ObjectNode state = state();
    ((ObjectNode) state.path("bank")).put("capacity", 1);
    ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("bank").path("slots"))
        .add(state.path("bank").path("slots").get(0).deepCopy());
    invalid(state);
  }

  @Test
  void bankObjectsNeedExplicitCachePlacementAndUniqueSourceIdentity() throws Exception {
    ObjectNode state = state();
    var object =
        state
            .withArray("objects")
            .addObject()
            .put("stableId", "loc:100:3092:3244:0")
            .put("locId", 100)
            .put("shape", 10)
            .put("rotation", 0);
    object.putObject("position").put("x", 3092).put("y", 3244).put("plane", 0);
    NativeBankStateContract.validateInitialState(state);
    for (Consumer<ObjectNode> mutation :
        List.<Consumer<ObjectNode>>of(
            row -> row.remove("locId"),
            row -> row.put("shape", 23),
            row -> row.put("rotation", 4),
            row -> row.put("stableId", " padded "),
            row -> ((ObjectNode) row.path("position")).remove("plane"),
            row -> ((ObjectNode) row.path("position")).put("x", "3092"))) {
      ObjectNode bad = state.deepCopy();
      mutation.accept((ObjectNode) bad.path("objects").get(0));
      invalid(bad);
    }
    state.withArray("objects").add(object.deepCopy());
    invalid(state);
  }

  @Test
  void bankOpenNeverDefaultsAMissingBankOrAcceptsAnUntypedFlag() throws Exception {
    ObjectNode state = json.createObjectNode().put("bankOpen", false);
    invalid(state);
    state = state();
    state.put("bankOpen", false);
    NativeBankStateContract.validateInitialState(state);
    state.put("bankOpen", true);
    NativeBankStateContract.validateInitialState(state);
    state.put("bankOpen", 1);
    invalid(state);
  }

  @Test
  void travelProfileAndUnsupportedQuestCompositionsAreNotInferred() throws Exception {
    ObjectNode state = state().put("travelProfile", "jewelry-v1");
    NativeBankStateContract.validateInitialState(state);
    state.put("travelProfile", "generic");
    invalid(state);
    state.put("travelProfile", "jewelry-v1").put("questProfile", "rune_mysteries");
    invalid(state);
    state.remove("travelProfile");
    invalid(state);
  }
}
