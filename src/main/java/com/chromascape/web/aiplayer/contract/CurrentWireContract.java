package com.chromascape.web.aiplayer.contract;

/** Exact current envelope versions. Invoke before idempotency lookups or mutations. */
public final class CurrentWireContract {
  private CurrentWireContract() {}

  public static void require(String supplied, String expected) {
    if (!expected.equals(supplied)) {
      throw new CurrentSliceContractException(
          CurrentSliceFailureCode.SCHEMA_MISMATCH, "Expected " + expected);
    }
  }
}
