package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ExecutionLimitContract;
import com.chromascape.web.replay.manifest.TerminationPolicyContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Set;

/**
 * Pure native run-state v2 response validator for the Java manifest-source driver.
 *
 * <p>Not a capability negotiation, predicate evaluator, restore authority or certification. Callers
 * must separately validate the manifest checksum/import provenance and negotiate this protocol.
 * Only fully validated responses replace the trusted prefix; rejection leaves it unchanged. The
 * production worker must not select this boundary merely because a response contains runState.
 */
public final class NativeRunLedger {
  public static final String SCHEMA = "xrsps-run-state/v2";
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "status",
          "reason",
          "outcome",
          "conditionId",
          "startTick",
          "tick",
          "elapsedTicks",
          "acceptedActions",
          "acceptedSteps",
          "terminalTick",
          "limits");
  private static final Map<String, String> CONDITION_CATEGORIES =
      Map.of("SUCCESS", "success", "FAILURE", "failure", "STOPPED", "stop");

  private final JsonNode actions;
  private final JsonNode policy;
  private final ObjectNode limits;
  private final long startTick;
  private final long endTick;
  private final boolean timed;
  private final String manifestChecksum;
  private final JsonNode manifestDocument;
  private final ArrayNode acceptedPrefix = JsonNodeFactory.instance.arrayNode();
  private JsonNode trusted;

  public NativeRunLedger(JsonNode manifest) {
    if (manifest == null || !manifest.isObject()) fail("manifest object required");
    ExecutionLimitContract.validate(manifest);
    TerminationPolicyContract.validateManifest(manifest);
    manifestDocument = manifest.deepCopy();
    startTick = number(manifest.path("recording").path("startTick"), 0, 10_000_000);
    endTick = number(manifest.path("recording").path("endTick"), startTick, 10_000_000);
    manifestChecksum = manifest.path("manifestChecksum").asText();
    if (!manifestChecksum.matches("sha256:[0-9a-f]{64}")) fail("manifest checksum required");
    timed = new ReplaySourceSchedule(manifest).timed();
    actions = manifest.path("actions").deepCopy();
    policy = manifest.path("termination").deepCopy();
    limits =
        manifest.has("execution")
            ? ((ObjectNode) manifest.get("execution")).deepCopy()
            : ExecutionLimitContract.defaults(
                new com.fasterxml.jackson.databind.ObjectMapper(), startTick, endTick);
    if (manifest.path("initialState").path("combat").isObject())
      limits.put("maximumSteps", Math.min(limits.path("maximumSteps").asLong(), 1024));
  }

  /** Validate a reset result. A second reset requires a new validator, never a prefix rewind. */
  public void acceptReset(JsonNode result) {
    if (trusted != null) fail("reset would replace an existing prefix");
    if (result == null || !result.isObject()) fail("reset result object required");
    if (number(result.path("initialTick"), startTick, startTick) != startTick)
      fail("reset tick differs from manifest");
    JsonNode next = validate(result.path("runState"), startTick, 0, 0);
    trusted = next.deepCopy();
  }

  /**
   * Validate one accepted scheduler request/result pair. A transport error/refusal is not an
   * accepted response and must never be passed here. This boundary does not dispatch requests.
   */
  public void acceptStep(JsonNode params, JsonNode result) {
    requireTrusted();
    if (terminal()) fail("step after terminal");
    if (params == null || !params.isObject() || result == null || !result.isObject())
      fail("step request/result objects required");
    boolean action = params.has("action");
    exact(
        params,
        action
            ? Set.of("expectedTick", "maxTicks", "action", "sourceActionIndex")
            : Set.of("expectedTick", "maxTicks"));
    long before = trusted.path("tick").asLong();
    number(params.path("expectedTick"), before, before);
    long bound = number(params.path("maxTicks"), action && timed ? 0 : 1, 10_000);
    long consumed = trusted.path("acceptedActions").asLong();
    if (action) {
      number(params.path("sourceActionIndex"), consumed, consumed);
      if (consumed >= actions.size()
          || !ReplaySourceSchedule.semanticAction(actions.get((int) consumed))
              .equals(params.path("action"))) fail("step action differs from next manifest source");
      if (timed && (nextSourceTick(consumed) != before || bound != 0))
        fail("timed source action requires its exact zero-tick receipt");
    } else if (timed && nextSourceTick(consumed) == before)
      fail("actionless step would skip a due source action");
    JsonNode applied = result.path("actionApplied");
    if (!applied.isBoolean() || applied.asBoolean() != action)
      fail("accepted action marker differs from request");
    long max = Math.min(before + bound, startTick + limits.path("maximumTicks").asLong());
    if (timed && !action && consumed < actions.size())
      max = Math.min(max, nextSourceTick(consumed));
    long tick = number(result.path("terminalTick"), before, max);
    JsonNode next =
        validate(
            result.path("runState"),
            tick,
            consumed + (action ? 1 : 0),
            trusted.path("acceptedSteps").asLong() + 1);
    if (!action && tick == before && !"TERMINAL".equals(next.path("status").asText()))
      fail("actionless step made no progress");
    ObjectNode receipt =
        acceptedPrefix
            .addObject()
            .put("expectedTick", before)
            .put("maxTicks", bound)
            .put("terminalTick", tick);
    receipt.set(
        "action", action ? params.path("action").deepCopy() : JsonNodeFactory.instance.nullNode());
    if (action) receipt.put("sourceActionIndex", consumed);
    else receipt.putNull("sourceActionIndex");
    trusted = next.deepCopy();
  }

  /**
   * A snapshot may preserve only the exact accepted request prefix, never invent a restore history.
   */
  public void requireSnapshot(JsonNode snapshot) {
    requireTrusted();
    if (!snapshot.isObject()
        || !snapshot.path("snapshotVersion").isInt()
        || snapshot.path("snapshotVersion").asInt() != 1
        || !snapshot.path("contentState").isArray()
        || snapshot.path("contentState").size() != 1) fail("native snapshot shape is incompatible");
    number(snapshot.path("tick"), trusted.path("tick").asLong(), trusted.path("tick").asLong());
    JsonNode descriptor = snapshot.path("contentState").get(0);
    if (!"authoritative-reconstruction-v1".equals(descriptor.path("kind").asText())
        || !descriptor.path("descriptorVersion").isInt()
        || descriptor.path("descriptorVersion").asInt() != 4
        || !descriptor.path("runEventSchema").isInt()
        || descriptor.path("runEventSchema").asInt() != 2
        || !"offline".equals(descriptor.path("resetMode").asText())
        || descriptor.has("sessionScoped")
        || !manifestChecksum.equals(descriptor.path("manifestChecksum").asText())
        || !sameJson(manifestDocument, descriptor.path("manifest"))
        || !sameJson(acceptedPrefix, descriptor.path("steps")))
      fail("native snapshot differs from manifest or accepted source prefix");
    number(descriptor.path("tick"), trusted.path("tick").asLong(), trusted.path("tick").asLong());
    requireUnchanged(descriptor.path("runState"));
  }

  private static boolean sameJson(JsonNode left, JsonNode right) {
    return left.equals(
        (a, b) ->
            a.isIntegralNumber()
                    && b.isIntegralNumber()
                    && a.canConvertToLong()
                    && b.canConvertToLong()
                ? Long.compare(a.asLong(), b.asLong())
                : a.equals(b) ? 0 : 1,
        right);
  }

  /** Read/snapshot evidence may confirm, but cannot replace, a validated ledger prefix. */
  public void requireUnchanged(JsonNode state) {
    requireTrusted();
    JsonNode checked =
        validate(
            state,
            trusted.path("tick").asLong(),
            trusted.path("acceptedActions").asLong(),
            trusted.path("acceptedSteps").asLong());
    if (!trusted.equals(checked)) fail("read evidence differs from trusted prefix");
  }

  public boolean terminal() {
    requireTrusted();
    return "TERMINAL".equals(trusted.path("status").asText());
  }

  /** Defensive copy; gameplay outcome remains separate from recording conformance. */
  public JsonNode state() {
    requireTrusted();
    return trusted.deepCopy();
  }

  /** Defensive copy of receipts accepted by this Java driver, not declarations from a snapshot. */
  JsonNode prefix() {
    requireTrusted();
    return acceptedPrefix.deepCopy();
  }

  /**
   * Structural rehydration only. The caller MUST first validate the independently persisted native
   * checkpoint context. This does not evaluate historical gameplay predicates: the fresh native
   * worker must replay the prefix and confirm its result before the ledger may drive a suffix.
   */
  static NativeRunLedger fromAnchoredSnapshot(JsonNode manifest, JsonNode snapshot) {
    NativeRunLedger ledger = new NativeRunLedger(manifest);
    JsonNode descriptor = snapshot.path("contentState").path(0);
    JsonNode receipts = descriptor.path("steps");
    if (!receipts.isArray() || receipts.size() > ledger.limits.path("maximumSteps").asLong())
      fail("restore prefix exceeds step bound");
    long tick = ledger.startTick, consumed = 0, count = 0;
    for (JsonNode receipt : receipts) {
      exact(
          receipt,
          Set.of("expectedTick", "maxTicks", "terminalTick", "action", "sourceActionIndex"));
      if (ledger.ceiling(tick, consumed, count) != null) fail("restore receipt after ceiling");
      number(receipt.path("expectedTick"), tick, tick);
      boolean action = !receipt.path("action").isNull();
      long bound = number(receipt.path("maxTicks"), action && ledger.timed ? 0 : 1, 10_000);
      if (action) {
        number(receipt.path("sourceActionIndex"), consumed, consumed);
        if (consumed >= ledger.actions.size()
            || !sameJson(
                ReplaySourceSchedule.semanticAction(ledger.actions.get((int) consumed)),
                receipt.path("action"))) fail("restore action differs from next manifest source");
        if (ledger.timed && (ledger.nextSourceTick(consumed) != tick || bound != 0))
          fail("restore timed action requires its exact receipt");
      } else if (!receipt.path("sourceActionIndex").isNull()
          || ledger.timed && ledger.nextSourceTick(consumed) == tick)
        fail("restore actionless receipt skips a due source");
      long max =
          Math.min(tick + bound, ledger.startTick + ledger.limits.path("maximumTicks").asLong());
      if (ledger.timed && !action && consumed < ledger.actions.size())
        max = Math.min(max, ledger.nextSourceTick(consumed));
      long nextTick = number(receipt.path("terminalTick"), tick, max);
      count++;
      if (!action
          && nextTick == tick
          && (count != receipts.size()
              || !"TERMINAL".equals(descriptor.path("runState").path("status").asText())))
        fail("restore actionless receipt made no progress");
      consumed += action ? 1 : 0;
      if (consumed > ledger.limits.path("maximumActions").asLong())
        fail("restore action limit exceeded");
      tick = nextTick;
      ledger.acceptedPrefix.add(receipt.deepCopy());
    }
    ledger.trusted = ledger.validate(descriptor.path("runState"), tick, consumed, count).deepCopy();
    ledger.requireSnapshot(snapshot);
    return ledger;
  }

  /** Explicit suffix coordinates, not an assertion that an early-terminated recording completed. */
  public ObjectNode evidence() {
    requireTrusted();
    ObjectNode evidence = JsonNodeFactory.instance.objectNode();
    evidence.put("schema", "xrsps-native-outcome-evidence/v1");
    evidence.put("manifestChecksum", manifestChecksum);
    evidence.set("runState", state());
    ObjectNode prefix = evidence.putObject("sourcePrefix");
    int consumed = trusted.path("acceptedActions").asInt();
    prefix.put("totalActions", actions.size());
    prefix.put("consumedActions", consumed);
    prefix.put("unconsumedStartIndex", consumed);
    prefix.put("unconsumedCount", actions.size() - consumed);
    prefix.put("allActionsConsumed", consumed == actions.size());
    prefix.put("recordingEndReached", trusted.path("tick").asLong() == endTick);
    return evidence;
  }

  private JsonNode validate(JsonNode state, long tick, long acceptedActions, long acceptedSteps) {
    exact(state, FIELDS);
    if (!SCHEMA.equals(state.path("schema").asText())) fail("unsupported run-state schema");
    number(state.path("startTick"), startTick, startTick);
    number(state.path("tick"), tick, tick);
    number(state.path("elapsedTicks"), tick - startTick, tick - startTick);
    number(state.path("acceptedActions"), acceptedActions, acceptedActions);
    number(state.path("acceptedSteps"), acceptedSteps, acceptedSteps);
    if (acceptedActions > actions.size()
        || acceptedActions > limits.path("maximumActions").asLong()
        || acceptedActions > acceptedSteps
        || acceptedSteps > limits.path("maximumSteps").asLong()
        || tick < startTick
        || tick > startTick + limits.path("maximumTicks").asLong())
      fail("prefix exceeds manifest bounds");
    exact(state.path("limits"), Set.of("schema", "maximumTicks", "maximumActions", "maximumSteps"));
    if (!limits.path("schema").equals(state.path("limits").path("schema")))
      fail("limit schema differs from manifest");
    for (String field : Set.of("maximumTicks", "maximumActions", "maximumSteps")) {
      long expected = limits.path(field).asLong();
      number(state.path("limits").path(field), expected, expected);
    }
    String status = state.path("status").asText();
    String ceiling = ceiling(tick, acceptedActions, acceptedSteps);
    if ("RUNNING".equals(status)) {
      if (!state.path("reason").isNull()
          || !state.path("outcome").isNull()
          || !state.path("conditionId").isNull()
          || !state.path("terminalTick").isNull()
          || ceiling != null) fail("running marker conflicts with prefix");
    } else if ("TERMINAL".equals(status)) {
      number(state.path("terminalTick"), tick, tick);
      String reason = state.path("reason").asText();
      String outcome = state.path("outcome").asText();
      if ("CONDITION".equals(reason)) {
        String category = CONDITION_CATEGORIES.get(outcome);
        boolean declared = false;
        if (category != null && state.path("conditionId").isTextual())
          for (JsonNode clause : policy.path(category))
            declared |= clause.path("id").equals(state.path("conditionId"));
        if (!declared) fail("outcome does not name a declared condition in its category");
      } else if ("CONDITION_WORK_LIMIT".equals(reason)) {
        if (!hasConditions()
            || !"INCONCLUSIVE".equals(outcome)
            || !state.path("conditionId").isNull()) fail("invalid condition work-limit outcome");
      } else if (ceiling == null
          || !ceiling.equals(reason)
          || !"INCONCLUSIVE".equals(outcome)
          || !state.path("conditionId").isNull())
        fail("terminal marker conflicts with ceiling precedence");
    } else fail("invalid run status");
    // JSON has one integral number type; do not treat an IntNode/LongNode storage
    // choice as a wire-level change when comparing read evidence.
    ObjectNode normalized = ((ObjectNode) state).deepCopy();
    for (String field :
        Set.of("startTick", "tick", "elapsedTicks", "acceptedActions", "acceptedSteps"))
      normalized.put(field, state.path(field).asLong());
    if (!state.path("terminalTick").isNull()) normalized.put("terminalTick", tick);
    normalized.set("limits", limits.deepCopy());
    return normalized;
  }

  private boolean hasConditions() {
    return !policy.path("success").isEmpty()
        || !policy.path("failure").isEmpty()
        || !policy.path("stop").isEmpty();
  }

  private String ceiling(long tick, long acceptedActions, long acceptedSteps) {
    if (acceptedSteps == limits.path("maximumSteps").asLong()) return "STEP_LIMIT";
    if (acceptedActions == limits.path("maximumActions").asLong()) return "ACTION_LIMIT";
    if (tick == startTick + limits.path("maximumTicks").asLong()
        && nextSourceTick(acceptedActions) != tick)
      return tick == endTick ? "RECORDING_END" : "TICK_LIMIT";
    return null;
  }

  private long nextSourceTick(long consumed) {
    return timed && consumed < actions.size()
        ? actions.get((int) consumed).path("sourceActionTick").asLong()
        : -1;
  }

  private void requireTrusted() {
    if (trusted == null) fail("validated reset required");
  }

  private static void exact(JsonNode value, Set<String> fields) {
    if (value == null || !value.isObject() || value.size() != fields.size())
      fail("closed object shape required");
    for (String field : fields) if (!value.has(field)) fail("required field missing");
  }

  private static long number(JsonNode value, long min, long max) {
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.asLong() < min
        || value.asLong() > max) fail("integral coordinate outside expected range");
    return value.asLong();
  }

  private static void fail(String reason) {
    throw ReplayException.invalid(
        ReplayErrorCode.XRSPS_PROTOCOL_ERROR, "NATIVE_RUN_STATE_INVALID: " + reason);
  }
}
