package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;

/** Verifies the cryptographic, exact-scope binding of a human gameplay authority. */
@FunctionalInterface
public interface AccountBuilderAuthorityVerifier {
  /** Verifies the authority signature and exact approved scope for the supplied envelope. */
  boolean verifyBoundAuthority(HumanAuthority authority, ExecutionEnvelope envelope);
}
