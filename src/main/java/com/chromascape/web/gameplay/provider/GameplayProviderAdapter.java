package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.fasterxml.jackson.databind.JsonNode;

/** Provider boundary: semantic JSON in, one provider-neutral decision out; no execution tools. */
public interface GameplayProviderAdapter {
  /** Returns the provider identity represented by this adapter. */
  Provider provider();

  /** Validates and normalizes provider output into the shared v1 decision model. */
  ProviderDecision adapt(String modelVersion, JsonNode providerOutput);
}
