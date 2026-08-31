package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import org.springframework.stereotype.Component;

@Component
public final class ClaudeGameplayProviderAdapter extends ClosedGameplayProviderAdapter {
  public ClaudeGameplayProviderAdapter(GameplayContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.CLAUDE;
  }
}
