package com.chromascape.web.aiplayer.contract;

import org.springframework.stereotype.Component;

@Component
public class CurrentSliceIdentityValidator {
  private final CurrentContractGate gate;

  @org.springframework.beans.factory.annotation.Autowired
  public CurrentSliceIdentityValidator(CurrentContractGate gate) {
    this.gate = gate;
  }

  /** Structural-only validator for isolated tests; Spring always uses the pinned constructor. */
  public CurrentSliceIdentityValidator() {
    this.gate = null;
  }

  public void requireOperation(CurrentSliceIdentity identity, String operation) {
    validate(identity);
    if (gate != null) gate.requireOperation(identity, operation);
  }

  public CurrentSliceIdentity validate(CurrentSliceIdentity identity) {
    if (identity == null)
      throw new CurrentSliceContractException(
          CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "identity is required");
    identity.validate();
    if (gate != null) gate.requireIdentity(identity);
    return identity;
  }

  public void requireUnchanged(CurrentSliceIdentity upstream, CurrentSliceIdentity downstream) {
    if (upstream == null || downstream == null)
      throw new CurrentSliceContractException(
          CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "identity is required");
    validate(upstream);
    validate(downstream);
    upstream.requireSameIdentity(downstream);
  }
}
