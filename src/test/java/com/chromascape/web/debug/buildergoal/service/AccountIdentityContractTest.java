package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountIdentityContractTest {

  @Test
  void usesTheDocumentedAccountConnectorScheme() {
    assertThat(AccountIdentityContract.HASH_SCHEME).isEqualTo("accountconnector/v1");
    assertThat(AccountIdentityContract.hasRequiredScheme(" accountconnector/v1 ")).isTrue();
    assertThat(AccountIdentityContract.hasRequiredScheme("profile/v1")).isFalse();
    assertThat(AccountIdentityContract.hasRequiredScheme(null)).isFalse();
  }

  @Test
  void hashMatchingNormalizesOnlyWhitespaceAndCase() {
    assertThat(AccountIdentityContract.matchesHash(" B01C7F006BACE664 ", "b01c7f006bace664"))
        .isTrue();
    assertThat(AccountIdentityContract.matchesHash("b01c7f006bace664", "3720a9a3b5c9f57b"))
        .isFalse();
    assertThat(AccountIdentityContract.matchesHash(null, "b01c7f006bace664")).isFalse();
  }

  @Test
  void namesAreNotUsedToOverrideAHashMismatch() {
    assertThat(AccountIdentityContract.matchesHash("profile-name", "b01c7f006bace664"))
        .isFalse();
  }
}
