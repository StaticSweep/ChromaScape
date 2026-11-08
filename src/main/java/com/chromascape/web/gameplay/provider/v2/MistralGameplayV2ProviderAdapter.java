package com.chromascape.web.gameplay.provider.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.Provider;
import org.springframework.stereotype.Component;

/** Closed Mistral gameplay adapter; providers can propose but never execute. */
@Component
public final class MistralGameplayV2ProviderAdapter extends GameplayV2ProviderAdapter {
  /** Creates the adapter over the shared contract validator. */
  public MistralGameplayV2ProviderAdapter(GameplayV2ContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.MISTRAL;
  }
}
