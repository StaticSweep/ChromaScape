package com.chromascape.web.gameplay.orchestration.accountbuilder;

/** Isolated runner for DrvFs environments where Gradle cannot enumerate the whole source tree. */
public final class AccountBuilderOrchestrationTestRunner {
  private AccountBuilderOrchestrationTestRunner() {}

  public static void main(String[] args) throws Exception {
    AccountBuilderOrchestrationServiceTest test = new AccountBuilderOrchestrationServiceTest();
    run(test, "heldAndShadowAreIdempotentAndNeverCallGateway");
    run(test, "exactDeliveryConsumesAuthorityAndDispatchesOnce");
    run(test, "authorityOwnershipIsCommittedBeforeReserveAndPreventsRedispatchAfterFailure");
    run(test, "noLeaseReservationUncertaintyUsesLookupOnlyReconciliation");
    run(test, "duplicateReceiptIsIdempotentAndLeaseTupleCannotBeReplaced");
    run(test, "conflictingIdentityFailsBeforeASecondGatewayCall");
    run(test, "acknowledgedNonTerminalDeliveryFailsClosedAsNeedsReconciliation");
    run(test, "reconciliationCanProveTerminalStateWithoutRedispatch");
    run(test, "staleCapabilityBindingIsDeniedBeforeReservation");
    run(test, "terminalReceiptCannotBeRewritten");
    run(test, "reconciliationNeededCannotReturnToDelivery");
    run(test, "restartCanQuiesceAClaimBeforeAuthorityWithoutDelivery");
    AccountBuilderOrchestrationConcurrencyTest concurrency =
        new AccountBuilderOrchestrationConcurrencyTest();
    run(concurrency, "identicalClaimsHaveOneFingerprintBoundOwner");
    run(concurrency, "conflictingAuthorityClaimsHaveOneDurableOwner");
    System.out.println("SC-1431 focused tests: 14/14 PASS");
  }

  private static void run(AccountBuilderOrchestrationServiceTest test, String method)
      throws Exception {
    test.setUp();
    AccountBuilderOrchestrationServiceTest.class.getDeclaredMethod(method).invoke(test);
  }

  private static void run(AccountBuilderOrchestrationConcurrencyTest test, String method)
      throws Exception {
    AccountBuilderOrchestrationConcurrencyTest.class.getDeclaredMethod(method).invoke(test);
  }
}
