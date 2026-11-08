package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import org.springframework.stereotype.Component;

/** Closed Mistral gameplay adapter; providers can propose but never execute. */
@Component
public final class MistralGameplayProviderAdapter extends ClosedGameplayProviderAdapter {
  /** Creates the adapter over the shared contract validator. */
  public MistralGameplayProviderAdapter(GameplayContractValidator validator) {
    super(validator);
  }

  @Override
  public Provider provider() {
    return Provider.MISTRAL;
  }
}
