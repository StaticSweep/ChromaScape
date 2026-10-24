package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure Java contract tests, not native execution, scheduler integration or database durability. */
class NativeRunLedgerTest {
  private final ObjectMapper json = new ObjectMapper();

  private ObjectNode manifest() {
    ObjectNode manifest =
        json.createObjectNode()
            .put("manifestVersion", 2)
            .put("manifestChecksum", "sha256:" + "a".repeat(64));
    manifest.putObject("recording").put("startTick", 10).put("endTick", 20);
    manifest
        .putObject("execution")
        .put("schema", "xrsps-execution-limits/v1")
        .put("maximumTicks", 10)
        .put("maximumActions", 10)
        .put("maximumSteps", 10);
    var actions = manifest.putArray("actions");
    for (int i = 0; i < 2; i++)
      actions
          .addObject()
          .put("type", "INTERACT_NPC")
          .put("targetStableId", "spot-" + i)
          .put("option", "net")
          .put("sourceActionTick", 10 + i * 5)
          .put("sourceActionSequence", 0);
    ObjectNode policy =
        manifest.putObject("termination").put("schema", "xrsps-termination-policy/v1");
    for (String category : List.of("failure", "success", "stop")) {
      ObjectNode clause = policy.putArray(category).addObject().put("id", category + "-id");
      clause.putObject("predicate").put("op", "exists").put("pointer", "/known");
    }
    bindPolicy(manifest);
    return manifest;
  }

  private void bindPolicy(ObjectNode manifest) {
    manifest
        .putObject("curation")
        .putObject("sourceCuration")
        .putObject("contentSnapshot")
        .set("termination", manifest.path("termination").deepCopy());
  }

  private ObjectNode state(ObjectNode manifest, long tick, int actions, int steps) {
    ObjectNode state =
        json.createObjectNode()
            .put("schema", NativeRunLedger.SCHEMA)
            .put("status", "RUNNING")
            .putNull("reason")
            .putNull("outcome")
            .putNull("conditionId")
            .put("startTick", 10)
            .put("tick", tick)
            .put("elapsedTicks", tick - 10)
            .put("acceptedActions", actions)
            .put("acceptedSteps", steps)
            .putNull("terminalTick");
    state.set("limits", manifest.path("execution").deepCopy());
    return state;
  }

  private ObjectNode terminal(ObjectNode state, String reason, String outcome, String id) {
    state.put("status", "TERMINAL").put("reason", reason).put("outcome", outcome);
    if (id == null) state.putNull("conditionId");
    else state.put("conditionId", id);
    state.put("terminalTick", state.path("tick").asLong());
    return state;
  }

  private ObjectNode reset(ObjectNode state) {
    return json.createObjectNode().put("initialTick", 10).set("runState", state);
  }

  private ObjectNode result(ObjectNode state, boolean applied) {
    return json.createObjectNode()
        .put("terminalTick", state.path("tick").asLong())
        .put("actionApplied", applied)
        .set("runState", state);
  }

  private ObjectNode request(ObjectNode manifest, int index, long tick) {
    return new ReplaySourceSchedule(manifest).next(index, tick, 100);
  }

  private NativeRunLedger running(ObjectNode manifest) {
    var ledger = new NativeRunLedger(manifest);
    ledger.acceptReset(reset(state(manifest, 10, 0, 0)));
    return ledger;
  }

  private void rejectedUnchanged(NativeRunLedger ledger, Runnable call) {
    JsonNode before = ledger.state();
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ReplayException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ReplayErrorCode.XRSPS_PROTOCOL_ERROR))
        .hasMessageContaining("NATIVE_RUN_STATE_INVALID");
    assertThat(ledger.state()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS", "FAILURE", "STOPPED"})
  void initialDeclaredOutcomeIsTerminalWithAnExplicitWholeUnconsumedSuffix(String outcome) {
    ObjectNode manifest = manifest();
    var ledger = new NativeRunLedger(manifest);
    String category =
        "STOPPED".equals(outcome) ? "stop" : outcome.toLowerCase(java.util.Locale.ROOT);
    ledger.acceptReset(
        reset(terminal(state(manifest, 10, 0, 0), "CONDITION", outcome, category + "-id")));
    assertThat(ledger.terminal()).isTrue();
    assertThat(ledger.evidence().at("/sourcePrefix/consumedActions").asInt()).isZero();
    assertThat(ledger.evidence().at("/sourcePrefix/unconsumedCount").asInt()).isEqualTo(2);
    assertThat(ledger.evidence().at("/sourcePrefix/allActionsConsumed").asBoolean()).isFalse();
    rejectedUnchanged(
        ledger,
        () -> ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true)));
  }

  @Test
  void zeroTickActionOutcomeConsumesOnlyItsExactReceiptAndThenReadEvidenceIsImmutable() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode state = terminal(state(manifest, 10, 1, 1), "CONDITION", "SUCCESS", "success-id");
    ledger.acceptStep(request(manifest, 0, 10), result(state, true));
    ledger.requireUnchanged(state);
    assertThat(ledger.evidence().at("/sourcePrefix/unconsumedStartIndex").asInt()).isEqualTo(1);
    assertThat(ledger.evidence().at("/sourcePrefix/unconsumedCount").asInt()).isEqualTo(1);
    state.put("conditionId", "failure-id");
    rejectedUnchanged(ledger, () -> ledger.requireUnchanged(state));
    ((ObjectNode) ledger.state()).put("acceptedActions", 1000);
    ledger.evidence().with("runState").put("outcome", "FAILURE");
    assertThat(ledger.state().path("outcome").asText()).isEqualTo("SUCCESS");
  }

  @Test
  void tickConditionBeforeNextActionLeavesItsSuffixUnconsumed() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true));
    ledger.acceptStep(
        request(manifest, 1, 10),
        result(terminal(state(manifest, 12, 1, 2), "CONDITION", "STOPPED", "stop-id"), false));
    assertThat(ledger.state().path("tick").asLong()).isEqualTo(12);
    assertThat(ledger.evidence().at("/sourcePrefix/unconsumedCount").asInt()).isEqualTo(1);
    assertThat(ledger.evidence().at("/sourcePrefix/recordingEndReached").asBoolean()).isFalse();
  }

  @Test
  void sourceClockReceiptsAndRecordingEndAreNotGameplaySuccess() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true));
    ledger.acceptStep(request(manifest, 1, 10), result(state(manifest, 15, 1, 2), false));
    ledger.acceptStep(request(manifest, 1, 15), result(state(manifest, 15, 2, 3), true));
    ledger.acceptStep(
        new ReplaySourceSchedule(manifest).tail(15, 100),
        result(terminal(state(manifest, 20, 2, 4), "RECORDING_END", "INCONCLUSIVE", null), false));
    assertThat(ledger.terminal()).isTrue();
    assertThat(ledger.evidence().at("/sourcePrefix/allActionsConsumed").asBoolean()).isTrue();
    assertThat(ledger.evidence().at("/sourcePrefix/recordingEndReached").asBoolean()).isTrue();
    assertThat(ledger.state().path("outcome").asText()).isEqualTo("INCONCLUSIVE");
  }

  @Test
  void inclusiveEndTickAllowsAllSameTickReceiptsBeforeRecordingEnd() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("recording")).put("endTick", 10);
    ((ObjectNode) manifest.path("execution")).put("maximumTicks", 0);
    ((ObjectNode) manifest.path("actions").get(1))
        .put("sourceActionTick", 10)
        .put("sourceActionSequence", 1);
    var ledger = running(manifest);
    ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true));
    assertThat(ledger.terminal()).isFalse();
    ledger.acceptStep(
        request(manifest, 1, 10),
        result(terminal(state(manifest, 10, 2, 2), "RECORDING_END", "INCONCLUSIVE", null), true));
    assertThat(ledger.terminal()).isTrue();
  }

  @Test
  void tickCeilingKeepsDueReceiptButStopsBeforeLaterSourceAction() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("execution")).put("maximumTicks", 0);
    var ledger = running(manifest);
    ledger.acceptStep(
        request(manifest, 0, 10),
        result(terminal(state(manifest, 10, 1, 1), "TICK_LIMIT", "INCONCLUSIVE", null), true));
    assertThat(ledger.evidence().at("/sourcePrefix/unconsumedCount").asInt()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"STEP_LIMIT", "ACTION_LIMIT", "CONDITION", "CONDITION_WORK_LIMIT"})
  void declaredConditionThenStepThenActionCeilingPrecedence(String reason) {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("execution"))
        .put("maximumActions", 1)
        .put("maximumSteps", "ACTION_LIMIT".equals(reason) ? 2 : 1);
    var ledger = running(manifest);
    String outcome = "CONDITION".equals(reason) ? "FAILURE" : "INCONCLUSIVE";
    ledger.acceptStep(
        request(manifest, 0, 10),
        result(
            terminal(
                state(manifest, 10, 1, 1),
                reason,
                outcome,
                "CONDITION".equals(reason) ? "failure-id" : null),
            true));
    assertThat(ledger.state().path("reason").asText()).isEqualTo(reason);
  }

  @Test
  void resetAtRecordingEndWithoutActionsIsInconclusiveAndCannotRun() {
    ObjectNode manifest = manifest();
    manifest.putArray("actions");
    ((ObjectNode) manifest.path("recording")).put("endTick", 10);
    ((ObjectNode) manifest.path("execution")).put("maximumTicks", 0);
    var ledger = new NativeRunLedger(manifest);
    assertThatThrownBy(() -> ledger.acceptReset(reset(state(manifest, 10, 0, 0))))
        .hasMessageContaining("running marker");
    ledger.acceptReset(
        reset(terminal(state(manifest, 10, 0, 0), "RECORDING_END", "INCONCLUSIVE", null)));
    rejectedUnchanged(ledger, () -> ledger.acceptReset(reset(state(manifest, 10, 0, 0))));
  }

  @Test
  void emptyPoliciesCannotProduceDeclaredConditionOrConditionWorkLimit() {
    ObjectNode manifest = manifest();
    for (String category : List.of("success", "failure", "stop"))
      ((ObjectNode) manifest.path("termination")).putArray(category);
    bindPolicy(manifest);
    var ledger = running(manifest);
    rejectedUnchanged(
        ledger,
        () ->
            ledger.acceptStep(
                request(manifest, 0, 10),
                result(
                    terminal(state(manifest, 10, 1, 1), "CONDITION", "SUCCESS", "success-id"),
                    true)));
    rejectedUnchanged(
        ledger,
        () ->
            ledger.acceptStep(
                request(manifest, 0, 10),
                result(
                    terminal(
                        state(manifest, 10, 1, 1), "CONDITION_WORK_LIMIT", "INCONCLUSIVE", null),
                    true)));
  }

  @Test
  void inputsAreDetachedAndUnboundSelectedPolicyIsRejected() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode original = manifest.deepCopy();
    ((ObjectNode) manifest.path("actions").get(0)).put("option", "attack");
    ((ObjectNode) manifest.path("execution")).put("maximumActions", 1);
    ((ObjectNode) manifest.path("termination").path("success").get(0)).put("id", "forged");
    ledger.acceptStep(request(original, 0, 10), result(state(original, 10, 1, 1), true));
    assertThat(ledger.terminal()).isFalse();
    assertThatThrownBy(() -> new NativeRunLedger(manifest))
        .hasMessageContaining("TERMINATION_POLICY_INVALID");
  }

  @Test
  void defaultLimitsAndCombatStepCapMatchNativeEffectiveLimits() {
    ObjectNode manifest = manifest();
    manifest.remove("execution");
    manifest.putObject("initialState").putObject("combat");
    var ledger = new NativeRunLedger(manifest);
    ObjectNode state = state(manifest(), 10, 0, 0);
    state
        .putObject("limits")
        .put("schema", "xrsps-execution-limits/v1")
        .put("maximumTicks", 10)
        .put("maximumActions", 10_000)
        .put("maximumSteps", 1024);
    ledger.acceptReset(reset(state));
    assertThat(ledger.state().at("/limits/maximumSteps").asInt()).isEqualTo(1024);
    ObjectNode forged = state.deepCopy();
    ((ObjectNode) forged.path("limits")).put("maximumSteps", 100_000);
    rejectedUnchanged(ledger, () -> ledger.requireUnchanged(forged));
  }

  @Test
  void resetIsRequiredAndCannotBeSubstitutedWithReadEvidence() {
    ObjectNode manifest = manifest();
    var ledger = new NativeRunLedger(manifest);
    assertThatThrownBy(ledger::state).hasMessageContaining("validated reset");
    assertThatThrownBy(ledger::evidence).hasMessageContaining("validated reset");
    assertThatThrownBy(ledger::terminal).hasMessageContaining("validated reset");
    assertThatThrownBy(() -> ledger.requireUnchanged(state(manifest, 10, 0, 0)))
        .hasMessageContaining("validated reset");
    assertThatThrownBy(
            () ->
                ledger.acceptStep(
                    request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true)))
        .hasMessageContaining("validated reset");
  }

  static Stream<Arguments> malformedStates() {
    List<Arguments> cases = new ArrayList<>();
    for (String field :
        List.of(
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
            "limits")) {
      cases.add(Arguments.of("missing " + field, (Consumer<ObjectNode>) s -> s.remove(field)));
    }
    cases.add(Arguments.of("extra field", (Consumer<ObjectNode>) s -> s.put("extra", true)));
    for (String field :
        List.of("startTick", "tick", "elapsedTicks", "acceptedActions", "acceptedSteps")) {
      cases.add(Arguments.of("string " + field, (Consumer<ObjectNode>) s -> s.put(field, "1")));
      cases.add(Arguments.of("fraction " + field, (Consumer<ObjectNode>) s -> s.put(field, 1.5)));
      cases.add(Arguments.of("negative " + field, (Consumer<ObjectNode>) s -> s.put(field, -1)));
      cases.add(
          Arguments.of(
              "overflow " + field,
              (Consumer<ObjectNode>)
                  s -> s.put(field, new java.math.BigInteger("18446744073709551616"))));
    }
    cases.add(
        Arguments.of(
            "old schema", (Consumer<ObjectNode>) s -> s.put("schema", "xrsps-run-state/v1")));
    cases.add(Arguments.of("unknown status", (Consumer<ObjectNode>) s -> s.put("status", "DONE")));
    cases.add(
        Arguments.of("replayed steps", (Consumer<ObjectNode>) s -> s.put("acceptedSteps", 0)));
    cases.add(Arguments.of("skipped steps", (Consumer<ObjectNode>) s -> s.put("acceptedSteps", 2)));
    cases.add(
        Arguments.of("skipped actions", (Consumer<ObjectNode>) s -> s.put("acceptedActions", 2)));
    cases.add(
        Arguments.of("replayed actions", (Consumer<ObjectNode>) s -> s.put("acceptedActions", 0)));
    cases.add(Arguments.of("wrong tick", (Consumer<ObjectNode>) s -> s.put("tick", 11)));
    cases.add(Arguments.of("wrong elapsed", (Consumer<ObjectNode>) s -> s.put("elapsedTicks", 1)));
    cases.add(
        Arguments.of("running reason", (Consumer<ObjectNode>) s -> s.put("reason", "CONDITION")));
    cases.add(
        Arguments.of("running outcome", (Consumer<ObjectNode>) s -> s.put("outcome", "SUCCESS")));
    cases.add(
        Arguments.of(
            "running condition", (Consumer<ObjectNode>) s -> s.put("conditionId", "success-id")));
    cases.add(
        Arguments.of(
            "running terminal tick", (Consumer<ObjectNode>) s -> s.put("terminalTick", 10)));
    for (String field : List.of("maximumTicks", "maximumActions", "maximumSteps")) {
      cases.add(
          Arguments.of(
              "changed " + field,
              (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("limits")).put(field, 1)));
      cases.add(
          Arguments.of(
              "string " + field,
              (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("limits")).put(field, "10")));
    }
    cases.add(
        Arguments.of(
            "extra limit",
            (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("limits")).put("wallTime", 1)));
    cases.add(
        Arguments.of(
            "wrong limit schema",
            (Consumer<ObjectNode>) s -> ((ObjectNode) s.path("limits")).put("schema", "wrong")));
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedStates")
  void malformedStepStateIsInert(String name, Consumer<ObjectNode> mutation) {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode state = state(manifest, 10, 1, 1);
    mutation.accept(state);
    rejectedUnchanged(
        ledger, () -> ledger.acceptStep(request(manifest, 0, 10), result(state, true)));
    // The exact same legitimate receipt is still eligible after rejecting the forged response.
    ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true));
  }

  static Stream<Arguments> forgedTerminals() {
    return Stream.of(
        Arguments.of("CONDITION", "SUCCESS", "unknown"),
        Arguments.of("CONDITION", "SUCCESS", "failure-id"),
        Arguments.of("CONDITION", "FAILURE", "success-id"),
        Arguments.of("CONDITION", "STOPPED", "success-id"),
        Arguments.of("CONDITION", "INCONCLUSIVE", "success-id"),
        Arguments.of("CONDITION", "SUCCESS", null),
        Arguments.of("CONDITION_WORK_LIMIT", "SUCCESS", null),
        Arguments.of("CONDITION_WORK_LIMIT", "INCONCLUSIVE", "success-id"),
        Arguments.of("RECORDING_END", "SUCCESS", null),
        Arguments.of("RECORDING_END", "INCONCLUSIVE", null),
        Arguments.of("TICK_LIMIT", "INCONCLUSIVE", null),
        Arguments.of("ACTION_LIMIT", "INCONCLUSIVE", null),
        Arguments.of("STEP_LIMIT", "INCONCLUSIVE", null),
        Arguments.of("UNKNOWN", "INCONCLUSIVE", null));
  }

  @ParameterizedTest
  @MethodSource("forgedTerminals")
  void forgedTerminalReasonOutcomeOrCategoryIsInert(String reason, String outcome, String id) {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    rejectedUnchanged(
        ledger,
        () ->
            ledger.acceptStep(
                request(manifest, 0, 10),
                result(terminal(state(manifest, 10, 1, 1), reason, outcome, id), true)));
  }

  static Stream<Arguments> badRequests() {
    return Stream.of(
        Arguments.of("stale tick", (Consumer<ObjectNode>) r -> r.put("expectedTick", 9)),
        Arguments.of("string bound", (Consumer<ObjectNode>) r -> r.put("maxTicks", "0")),
        Arguments.of("negative bound", (Consumer<ObjectNode>) r -> r.put("maxTicks", -1)),
        Arguments.of("large bound", (Consumer<ObjectNode>) r -> r.put("maxTicks", 10_001)),
        Arguments.of("timed advancing receipt", (Consumer<ObjectNode>) r -> r.put("maxTicks", 1)),
        Arguments.of("wrong index", (Consumer<ObjectNode>) r -> r.put("sourceActionIndex", 1)),
        Arguments.of("fraction index", (Consumer<ObjectNode>) r -> r.put("sourceActionIndex", 0.5)),
        Arguments.of("missing index", (Consumer<ObjectNode>) r -> r.remove("sourceActionIndex")),
        Arguments.of(
            "invented option",
            (Consumer<ObjectNode>) r -> ((ObjectNode) r.path("action")).put("option", "attack")),
        Arguments.of("null action", (Consumer<ObjectNode>) r -> r.putNull("action")),
        Arguments.of("extra request", (Consumer<ObjectNode>) r -> r.put("allowUnsafe", true)),
        Arguments.of(
            "actionless receipt",
            (Consumer<ObjectNode>)
                r -> {
                  r.remove(List.of("action", "sourceActionIndex"));
                  r.put("maxTicks", 1);
                }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badRequests")
  void wrongSourceRequestIsInert(String name, Consumer<ObjectNode> mutation) {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode request = request(manifest, 0, 10);
    mutation.accept(request);
    rejectedUnchanged(
        ledger, () -> ledger.acceptStep(request, result(state(manifest, 10, 1, 1), true)));
  }

  @Test
  void returnedCoordinatesAndAppliedMarkerMustMatchBeforeLedgerCommit() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    for (long tick : new long[] {9, 11}) {
      rejectedUnchanged(
          ledger,
          () ->
              ledger.acceptStep(
                  request(manifest, 0, 10), result(state(manifest, tick, 1, 1), true)));
    }
    for (JsonNode bad :
        List.of(
            json.nullNode(),
            json.getNodeFactory().textNode("true"),
            json.getNodeFactory().booleanNode(false))) {
      ObjectNode response = result(state(manifest, 10, 1, 1), true);
      response.set("actionApplied", bad);
      rejectedUnchanged(ledger, () -> ledger.acceptStep(request(manifest, 0, 10), response));
    }
    ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true));
    rejectedUnchanged(
        ledger,
        () ->
            ledger.acceptStep(request(manifest, 1, 10), result(state(manifest, 16, 1, 2), false)));
    rejectedUnchanged(
        ledger,
        () ->
            ledger.acceptStep(request(manifest, 1, 10), result(state(manifest, 10, 1, 2), false)));
    rejectedUnchanged(
        ledger,
        () -> ledger.acceptStep(request(manifest, 1, 10), result(state(manifest, 11, 2, 2), true)));
    ledger.acceptStep(request(manifest, 1, 10), result(state(manifest, 15, 1, 2), false));
  }

  @Test
  void nonterminalCeilingOrWrongCeilingPrecedenceIsInert() {
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("execution")).put("maximumActions", 1).put("maximumSteps", 1);
    var ledger = running(manifest);
    rejectedUnchanged(
        ledger,
        () -> ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 10, 1, 1), true)));
    rejectedUnchanged(
        ledger,
        () ->
            ledger.acceptStep(
                request(manifest, 0, 10),
                result(
                    terminal(state(manifest, 10, 1, 1), "ACTION_LIMIT", "INCONCLUSIVE", null),
                    true)));
    ObjectNode terminal = terminal(state(manifest, 10, 1, 1), "STEP_LIMIT", "INCONCLUSIVE", null);
    terminal.put("terminalTick", 11);
    rejectedUnchanged(
        ledger, () -> ledger.acceptStep(request(manifest, 0, 10), result(terminal, true)));
  }

  @Test
  void advancingToInclusiveEndWaitsForTheDueActionInsteadOfPrematureRecordingEnd() {
    ObjectNode manifest = manifest();
    ObjectNode only = ((ObjectNode) manifest.path("actions").get(0)).deepCopy();
    only.put("sourceActionTick", 20);
    manifest.putArray("actions").add(only);
    var ledger = running(manifest);
    ObjectNode atEnd = state(manifest, 20, 0, 1);
    ledger.acceptStep(request(manifest, 0, 10), result(atEnd, false));
    assertThat(ledger.terminal()).isFalse();
    assertThat(ledger.evidence().at("/sourcePrefix/recordingEndReached").asBoolean()).isTrue();
    assertThat(ledger.evidence().at("/sourcePrefix/allActionsConsumed").asBoolean()).isFalse();
    ledger.acceptStep(
        request(manifest, 0, 20),
        result(terminal(state(manifest, 20, 1, 2), "RECORDING_END", "INCONCLUSIVE", null), true));
    assertThat(ledger.terminal()).isTrue();
  }

  @Test
  void conditionlessLegacyManifestUsesDefaultsWithoutRequiringAnInventedPolicy() {
    ObjectNode manifest = manifest();
    manifest.remove(List.of("termination", "curation", "execution"));
    ObjectNode initial = state(manifest(), 10, 0, 0);
    initial
        .putObject("limits")
        .put("schema", "xrsps-execution-limits/v1")
        .put("maximumTicks", 10)
        .put("maximumActions", 10_000)
        .put("maximumSteps", 100_000);
    var ledger = new NativeRunLedger(manifest);
    ledger.acceptReset(reset(initial));
    ledger.requireUnchanged(initial);
    assertThat(ledger.terminal()).isFalse();
    ObjectNode forged = terminal(initial.deepCopy(), "CONDITION_WORK_LIMIT", "INCONCLUSIVE", null);
    rejectedUnchanged(ledger, () -> ledger.requireUnchanged(forged));
  }

  @Test
  void untimedLegacySourceStillRequiresExactIndexedActionsButAllowsBoundedAdvance() {
    ObjectNode manifest = manifest();
    for (JsonNode action : manifest.path("actions"))
      ((ObjectNode) action).remove(List.of("sourceActionTick", "sourceActionSequence"));
    var ledger = running(manifest);
    ledger.acceptStep(request(manifest, 0, 10), result(state(manifest, 11, 1, 1), true));
    ledger.acceptStep(
        request(manifest, 1, 11),
        result(terminal(state(manifest, 12, 2, 2), "CONDITION", "SUCCESS", "success-id"), true));
    assertThat(ledger.evidence().at("/sourcePrefix/allActionsConsumed").asBoolean()).isTrue();
    assertThat(ledger.evidence().at("/sourcePrefix/recordingEndReached").asBoolean()).isFalse();
  }

  @Test
  void numericallyEquivalentIntegralStorageAndReturnedCopiesDoNotChangeReadEvidence() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode initial = state(manifest, 10, 0, 0);
    initial.put("tick", 10).put("startTick", 10L).put("elapsedTicks", 0L);
    ledger.requireUnchanged(initial);
    ObjectNode forged = terminal(initial.deepCopy(), "CONDITION", "SUCCESS", "success-id");
    rejectedUnchanged(ledger, () -> ledger.requireUnchanged(forged));
    initial.put("acceptedActions", 1);
    assertThat(ledger.state().path("acceptedActions").asInt()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrongTick",
        "stringTick",
        "fractionTick",
        "missingLedger",
        "oldLedger",
        "counterPrefix",
        "runningCondition"
      })
  void invalidResetDoesNotEstablishOrPoisonTheInitialPrefix(String mutation) {
    ObjectNode manifest = manifest();
    var ledger = new NativeRunLedger(manifest);
    ObjectNode initial = reset(state(manifest, 10, 0, 0));
    switch (mutation) {
      case "wrongTick" -> initial.put("initialTick", 11);
      case "stringTick" -> initial.put("initialTick", "10");
      case "fractionTick" -> initial.put("initialTick", 10.5);
      case "missingLedger" -> initial.remove("runState");
      case "oldLedger" -> ((ObjectNode) initial.path("runState"))
          .put("schema", "xrsps-run-state/v1");
      case "counterPrefix" -> ((ObjectNode) initial.path("runState")).put("acceptedSteps", 1);
      case "runningCondition" -> ((ObjectNode) initial.path("runState"))
          .put("conditionId", "success-id");
      default -> throw new AssertionError("unknown test case");
    }
    assertThatThrownBy(() -> ledger.acceptReset(initial))
        .hasMessageContaining("NATIVE_RUN_STATE_INVALID");
    assertThatThrownBy(ledger::state).hasMessageContaining("validated reset");
    ledger.acceptReset(reset(state(manifest, 10, 0, 0)));
    assertThat(ledger.terminal()).isFalse();
  }

  private ObjectNode snapshot(ObjectNode manifest, NativeRunLedger ledger, ObjectNode request) {
    ObjectNode snapshot = json.createObjectNode().put("snapshotVersion", 1).put("tick", 10);
    ObjectNode descriptor =
        snapshot
            .putArray("contentState")
            .addObject()
            .put("kind", "authoritative-reconstruction-v1")
            .put("descriptorVersion", 4)
            .put("runEventSchema", 2)
            .put("resetMode", "offline")
            .put("tick", 10)
            .put("manifestChecksum", manifest.path("manifestChecksum").asText());
    descriptor.set("manifest", manifest.deepCopy());
    descriptor.set("runState", ledger.state());
    ObjectNode receipt =
        descriptor
            .putArray("steps")
            .addObject()
            .put("expectedTick", 10)
            .put("maxTicks", 0)
            .put("terminalTick", 10)
            .put("sourceActionIndex", 0);
    receipt.set("action", request.path("action").deepCopy());
    return snapshot;
  }

  @Test
  void exactDetachedSnapshotBindsManifestAndTheWholeAcceptedRequestPrefix() {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode request = request(manifest, 0, 10);
    ledger.acceptStep(
        request,
        result(terminal(state(manifest, 10, 1, 1), "CONDITION", "SUCCESS", "success-id"), true));
    ObjectNode snapshot = snapshot(manifest, ledger, request);
    ledger.requireSnapshot(snapshot);
    ((ObjectNode) request.path("action")).put("option", "forged-after-acceptance");
    ledger.requireSnapshot(snapshot);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "oldDescriptor",
        "oldLifecycle",
        "wrongManifest",
        "changedInlineManifest",
        "forgedPrefix",
        "replayedPrefix",
        "forgedLedger",
        "wrongTick",
        "sessionMode",
        "sessionFlag"
      })
  void poisonedSnapshotCannotReplaceOrAuthenticateADifferentAcceptedPrefix(String mutation) {
    ObjectNode manifest = manifest();
    var ledger = running(manifest);
    ObjectNode request = request(manifest, 0, 10);
    ledger.acceptStep(request, result(state(manifest, 10, 1, 1), true));
    ObjectNode snapshot = snapshot(manifest, ledger, request);
    ObjectNode descriptor = (ObjectNode) snapshot.path("contentState").get(0);
    switch (mutation) {
      case "oldDescriptor" -> descriptor.put("descriptorVersion", 3);
      case "oldLifecycle" -> descriptor.put("runEventSchema", 1);
      case "wrongManifest" -> descriptor.put("manifestChecksum", "sha256:" + "f".repeat(64));
      case "changedInlineManifest" -> ((ObjectNode) descriptor.path("manifest"))
          .put("manifestVersion", 1);
      case "forgedPrefix" -> ((ObjectNode) descriptor.path("steps").get(0))
          .put("sourceActionIndex", 1);
      case "replayedPrefix" -> descriptor
          .withArray("steps")
          .add(descriptor.path("steps").get(0).deepCopy());
      case "forgedLedger" -> ((ObjectNode) descriptor.path("runState")).put("acceptedActions", 0);
      case "wrongTick" -> snapshot.put("tick", 11);
      case "sessionMode" -> descriptor.put("resetMode", "session");
      case "sessionFlag" -> descriptor.put("sessionScoped", false);
      default -> throw new AssertionError("unknown test case");
    }
    rejectedUnchanged(ledger, () -> ledger.requireSnapshot(snapshot));
  }
}
