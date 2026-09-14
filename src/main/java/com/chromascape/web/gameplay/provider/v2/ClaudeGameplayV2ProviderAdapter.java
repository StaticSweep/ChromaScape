package com.chromascape.web.gameplay.provider.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.Provider;
import org.springframework.stereotype.Component;

@Component
public final class ClaudeGameplayV2ProviderAdapter extends GameplayV2ProviderAdapter {
  public ClaudeGameplayV2ProviderAdapter(GameplayV2ContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.CLAUDE;
  }
}
