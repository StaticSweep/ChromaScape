package com.chromascape.web.debug.buildergoal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecoveryStateTransitionsTest {

  @Test
  void happyPathIsMonotonic() {
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.INCIDENT_CAPTURED, RecoveryStates.EVIDENCE_READY))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(RecoveryStates.EVIDENCE_READY, RecoveryStates.ANALYZING))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(RecoveryStates.ANALYZING, RecoveryStates.AWAITING_REVIEW))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.AWAITING_REVIEW, RecoveryStates.VALIDATION_QUEUED))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.VALIDATION_QUEUED, RecoveryStates.VALIDATING))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(RecoveryStates.VALIDATING, RecoveryStates.BUILD_VALIDATED))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.BUILD_VALIDATED, RecoveryStates.RERUN_REQUESTED))
        .isTrue();
    assertThat(RecoveryStates.canTransition(RecoveryStates.RERUN_REQUESTED, RecoveryStates.BOUND))
        .isTrue();
  }

  @Test
  void followUpProposalCycleRequiresNewProposal() {
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.VALIDATION_FAILED, RecoveryStates.ANALYZING))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(RecoveryStates.AWAITING_REVIEW, RecoveryStates.ANALYZING))
        .isTrue();
    assertThat(RecoveryStates.canTransition(RecoveryStates.ANALYZING, RecoveryStates.REJECTED))
        .isTrue();
  }

  @Test
  void skipsAreForbidden() {
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.INCIDENT_CAPTURED, RecoveryStates.VALIDATING))
        .isFalse();
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.AWAITING_REVIEW, RecoveryStates.BUILD_VALIDATED))
        .isFalse();
    assertThat(RecoveryStates.canTransition(RecoveryStates.BOUND, RecoveryStates.ANALYZING))
        .isFalse();
    assertThat(RecoveryStates.canTransition(null, RecoveryStates.ANALYZING)).isFalse();
  }

  @Test
  void uncertainRerunOutcomeIsExplicit() {
    assertThat(
            RecoveryStates.canTransition(
                RecoveryStates.RERUN_REQUESTED, RecoveryStates.NEEDS_RECONCILIATION))
        .isTrue();
    assertThat(
            RecoveryStates.canTransition(RecoveryStates.NEEDS_RECONCILIATION, RecoveryStates.BOUND))
        .isTrue();
    assertThat(RecoveryStates.isTerminal(RecoveryStates.NEEDS_RECONCILIATION)).isFalse();
    assertThat(RecoveryStates.isTerminal(RecoveryStates.BOUND)).isTrue();
  }
}
