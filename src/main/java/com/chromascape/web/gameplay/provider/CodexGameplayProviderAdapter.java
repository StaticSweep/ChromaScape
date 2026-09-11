package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import org.springframework.stereotype.Component;

/** Codex-specific identity adapter for the provider-neutral v1 contract. */
@Component
public final class CodexGameplayProviderAdapter extends ClosedGameplayProviderAdapter {
  /** Creates a Codex adapter using the shared v1 validator. */
  public CodexGameplayProviderAdapter(GameplayContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.CODEX;
  }
}
