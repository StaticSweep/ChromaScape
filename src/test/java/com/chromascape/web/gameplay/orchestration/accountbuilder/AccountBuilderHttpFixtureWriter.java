package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;

/** Emits fresh, deterministic request JSON for the native real-HTTP verification harness. */
public final class AccountBuilderHttpFixtureWriter {
  private AccountBuilderHttpFixtureWriter() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: <suffix> <mode>");
    }
    var request = AccountBuilderPostgresTestFixtures.freshRequest(args[0], Mode.valueOf(args[1]));
    var roundTripped =
        AccountBuilderPostgresTestFixtures.OBJECT_MAPPER.readValue(
            AccountBuilderPostgresTestFixtures.OBJECT_MAPPER.writeValueAsBytes(request),
            AccountBuilderOrchestrationModels.OrchestrationRequest.class);
    new AccountBuilderOrchestrationValidator(
            AccountBuilderPostgresTestFixtures.CANONICAL_JSON,
            java.time.Clock.systemUTC(),
            (authority, envelope) -> true)
        .validateAndFingerprint(roundTripped);
    System.out.print(AccountBuilderPostgresTestFixtures.OBJECT_MAPPER.writeValueAsString(request));
  }
}
