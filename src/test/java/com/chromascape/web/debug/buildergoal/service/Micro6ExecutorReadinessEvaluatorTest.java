package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Micro6ExecutorReadinessEvaluatorTest {

  private static final String LOGGED_IN = "logged_in";
  private static final String ACCOUNTBUILDER_READY = "accountbuilder_ready";

  private Micro6ExecutorReadinessEvaluator.Evaluation evaluate(
      boolean dispatchHold, String loginState, String startupState, boolean pathfinderReady) {
    return Micro6ExecutorReadinessEvaluator.evaluate(
        dispatchHold,
        null,
        startupState,
        loginState,
        null,
        null,
        null,
        true,
        360,
        360,
        pathfinderReady,
        true,
        "RUNNING");
  }

  @Test
  void readyWhenAllDispatchRequirementsSatisfied() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        evaluate(false, LOGGED_IN, ACCOUNTBUILDER_READY, true);
    assertThat(result.publishReady()).isTrue();
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_READY);
  }

  @Test
  void loginStateTakesPrecedenceOverWorldAndScript() {
    // Login incomplete is classified as WAITING_FOR_LOGIN even when the world is matched and the
    // script loop is alive, because readiness ordering is login → world → script → dispatch.
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            "accountbuilder_ready",
            "auto_login_in_progress",
            1,
            3,
            null,
            true,
            360,
            360,
            true,
            true,
            "RUNNING");
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_LOGIN);
    assertThat(result.publishReady()).isFalse();
  }

  @Test
  void missingLoginStateFailsClosed() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            ACCOUNTBUILDER_READY,
            null,
            null,
            null,
            null,
            true,
            360,
            360,
            true,
            true,
            "RUNNING");
    assertThat(result.publishReady()).isFalse();
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_LOGIN);
  }

  @Test
  void blankLoginStateFailsClosed() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            ACCOUNTBUILDER_READY,
            "   ",
            null,
            null,
            null,
            true,
            360,
            360,
            true,
            true,
            "RUNNING");
    assertThat(result.publishReady()).isFalse();
  }

  @Test
  void unknownLoginStateFailsClosed() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        evaluate(false, "UNKNOWN", ACCOUNTBUILDER_READY, true);
    assertThat(result.publishReady()).isFalse();
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_LOGIN);
  }

  @Test
  void worldMismatchIsNotReady() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            ACCOUNTBUILDER_READY,
            LOGGED_IN,
            null,
            null,
            null,
            false,
            360,
            301,
            true,
            true,
            "RUNNING");
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_WORLD);
    assertThat(result.publishReady()).isFalse();
  }

  @Test
  void worldMatchIsComputedFromObservedWorldNotHeartbeatFlag() {
    // The evaluator receives a recomputed worldMatched value; a mismatched observed world must
    // never be reported as ready even when the script loop is live.
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            ACCOUNTBUILDER_READY,
            LOGGED_IN,
            null,
            null,
            null,
            false,
            360,
            360,
            true,
            true,
            "RUNNING");
    // worldMatched=false means the caller observed a mismatch; fail closed regardless of equality.
    assertThat(result.publishReady()).isFalse();
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_WORLD);
  }

  @Test
  void deadScriptLoopIsNotReady() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            ACCOUNTBUILDER_READY,
            LOGGED_IN,
            null,
            null,
            null,
            true,
            360,
            360,
            true,
            false,
            "RUNNING");
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_SCRIPT);
    assertThat(result.publishReady()).isFalse();
  }

  @Test
  void nonRunningLifecycleIsNotReady() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        Micro6ExecutorReadinessEvaluator.evaluate(
            false,
            null,
            ACCOUNTBUILDER_READY,
            LOGGED_IN,
            null,
            null,
            null,
            true,
            360,
            360,
            true,
            true,
            "STOPPED");
    assertThat(result.phase()).isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_SCRIPT);
    assertThat(result.publishReady()).isFalse();
  }

  @Test
  void dispatchHoldIsNotReady() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        evaluate(true, LOGGED_IN, ACCOUNTBUILDER_READY, true);
    assertThat(result.publishReady()).isFalse();
    assertThat(result.phase())
        .isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_DISPATCH);
  }

  @Test
  void startupStateMustBeAccountbuilderReady() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        evaluate(false, LOGGED_IN, "initializing_bot_logic", true);
    assertThat(result.publishReady()).isFalse();
    assertThat(result.phase())
        .isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_DISPATCH);
  }

  @Test
  void missingStartupStateFailsClosed() {
    Micro6ExecutorReadinessEvaluator.Evaluation result = evaluate(false, LOGGED_IN, null, true);
    assertThat(result.publishReady()).isFalse();
  }

  @Test
  void pathfinderIncompleteIsNotReady() {
    Micro6ExecutorReadinessEvaluator.Evaluation result =
        evaluate(false, LOGGED_IN, ACCOUNTBUILDER_READY, false);
    assertThat(result.publishReady()).isFalse();
    assertThat(result.phase())
        .isEqualTo(Micro6ExecutorReadinessEvaluator.PHASE_WAITING_FOR_DISPATCH);
  }
}
