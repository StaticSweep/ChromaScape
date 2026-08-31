package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import org.springframework.stereotype.Component;

@Component
public final class CodexGameplayProviderAdapter extends ClosedGameplayProviderAdapter {
  public CodexGameplayProviderAdapter(GameplayContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.CODEX;
  }
}
