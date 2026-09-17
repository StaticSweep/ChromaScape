package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import org.springframework.stereotype.Component;

/** Claude-specific identity adapter for the provider-neutral v1 contract. */
@Component
public final class ClaudeGameplayProviderAdapter extends ClosedGameplayProviderAdapter {
  /** Creates a Claude adapter using the shared v1 validator. */
  public ClaudeGameplayProviderAdapter(GameplayContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.CLAUDE;
  }
}
