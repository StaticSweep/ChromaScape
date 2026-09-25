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
    assertThat(AccountIdentityContract.matchesHash("profile-name", "b01c7f006bace664")).isFalse();
  }

  @Test
  void onlyCanonical16HexHashesQualify() {
    assertThat(AccountIdentityContract.isCanonicalHash("b01c7f006bace664")).isTrue();
    assertThat(AccountIdentityContract.isCanonicalHash(" B01C7F006BACE664 ")).isTrue();
    assertThat(AccountIdentityContract.isCanonicalHash("3720a9a3b5c9f57b")).isTrue();
    assertThat(AccountIdentityContract.isCanonicalHash(null)).isFalse();
    assertThat(AccountIdentityContract.isCanonicalHash("")).isFalse();
    assertThat(AccountIdentityContract.isCanonicalHash("   ")).isFalse();
    assertThat(AccountIdentityContract.isCanonicalHash("profile-name")).isFalse();
    assertThat(AccountIdentityContract.isCanonicalHash("abc123")).isFalse();
    assertThat(AccountIdentityContract.isCanonicalHash("0123456789abcdef0")).isFalse();
    assertThat(AccountIdentityContract.isCanonicalHash("gggggggggggggggg")).isFalse();
  }

  @Test
  void canonicalMatchNeverTreatsEqualMalformedStringsAsIdentity() {
    assertThat(AccountIdentityContract.matchesHash("hash-1", "hash-1")).isTrue();
    assertThat(AccountIdentityContract.matchesCanonicalHash("hash-1", "hash-1")).isFalse();
    assertThat(AccountIdentityContract.matchesCanonicalHash("abc123", "abc123")).isFalse();
    assertThat(AccountIdentityContract.matchesCanonicalHash("B01C7F006BACE664", "b01c7f006bace664"))
        .isTrue();
  }
}
