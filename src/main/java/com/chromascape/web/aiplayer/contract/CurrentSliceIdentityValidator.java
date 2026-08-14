package com.chromascape.web.aiplayer.contract;

import org.springframework.stereotype.Component;

@Component
public class CurrentSliceIdentityValidator {
  public CurrentSliceIdentity validate(CurrentSliceIdentity identity) {
    if (identity == null) throw new CurrentSliceContractException(CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "identity is required");
    identity.validate();
    return identity;
  }

  public void requireUnchanged(CurrentSliceIdentity upstream, CurrentSliceIdentity downstream) {
    if (upstream == null || downstream == null) throw new CurrentSliceContractException(CurrentSliceFailureCode.IDENTITY_GROUP_MISSING, "identity is required");
    upstream.requireSameIdentity(downstream);
  }
}
