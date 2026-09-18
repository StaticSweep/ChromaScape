package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The full legal SC-1431 lifecycle graph, including terminal immutability. */
class AccountBuilderOrchestrationLifecycleMatrixTest {

  @Test
  void heldAndShadowStopAtHeld() {
    assertThat(State.HELD.terminal()).isTrue();
    assertThat(State.HELD.canTransitionTo(State.VALIDATED)).isFalse();
    assertThat(State.HELD.canTransitionTo(State.AUTHORIZED)).isFalse();
  }

  @Test
  void deliveryBoundaryRequiresCommittedReservationEvidence() {
    // AUTHORIZED may only advance through a committed reservation receipt.
    assertThat(State.AUTHORIZED.canTransitionTo(State.RESERVED)).isTrue();
    assertThat(State.AUTHORIZED.canTransitionTo(State.DISPATCH_SUBMITTED)).isFalse();
    assertThat(State.AUTHORIZED.canTransitionTo(State.ACTIVE)).isFalse();
    assertThat(State.RESERVED.canTransitionTo(State.DISPATCH_SUBMITTED)).isTrue();
    // Dispatch can never be reached again from a state past its own boundary.
    assertThat(State.DISPATCH_SUBMITTED.canTransitionTo(State.RESERVED)).isFalse();
    assertThat(State.ACTIVE.canTransitionTo(State.DISPATCH_SUBMITTED)).isFalse();
  }

  @Test
  void needsReconciliationOnlyMovesToProvenTerminalOutcomes() {
    List<State> proven =
        List.of(
            State.CONFIRMED,
            State.REFUTED,
            State.UNVERIFIABLE,
            State.DENIED,
            State.STOPPED,
            State.FAILED);
    for (State outcome : proven) {
      assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(outcome)).isTrue();
      assertThat(outcome.provenTerminal()).isTrue();
    }
    // No delivery state may be re-entered from reconciliation.
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.RESERVED)).isFalse();
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.DISPATCH_SUBMITTED)).isFalse();
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.ACTIVE)).isFalse();
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.AUTHORIZED)).isFalse();
  }

  @Test
  void terminalStatesAreImmutable() {
    List<State> immutable =
        List.of(
            State.HELD,
            State.CONFIRMED,
            State.REFUTED,
            State.UNVERIFIABLE,
            State.DENIED,
            State.STOPPED,
            State.FAILED);
    for (State from : immutable) {
      for (State to : State.values()) {
        if (to == from) {
          assertThat(from.canTransitionTo(to)).isTrue();
        } else {
          assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isFalse();
        }
      }
    }
  }

  @Test
  void uncertainOutcomesAreNeverMarkedRetryable() {
    assertThat(State.UNVERIFIABLE.provenTerminal()).isTrue();
    assertThat(State.NEEDS_RECONCILIATION.provenTerminal()).isFalse();
    // NEEDS_RECONCILIATION is terminal in the graph but not a proven outcome: it can only become
    // one of the proven terminal states through evidence, never through a redispatch.
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.CREATED)).isFalse();
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.VALIDATED)).isFalse();
    assertThat(State.NEEDS_RECONCILIATION.canTransitionTo(State.HELD)).isFalse();
  }
}
