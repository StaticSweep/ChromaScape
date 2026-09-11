package com.chromascape.web.gameplay.provider.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.Provider;
import org.springframework.stereotype.Component;

/** Codex-specific identity adapter for the provider-neutral v2 contract. */
@Component
public final class CodexGameplayV2ProviderAdapter extends GameplayV2ProviderAdapter {
  /** Creates a Codex adapter using the shared v2 validator. */
  public CodexGameplayV2ProviderAdapter(GameplayV2ContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.CODEX;
  }
}
