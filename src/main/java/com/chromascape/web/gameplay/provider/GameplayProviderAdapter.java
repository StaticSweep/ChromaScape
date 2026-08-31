package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.fasterxml.jackson.databind.JsonNode;

/** Provider boundary: semantic JSON in, one provider-neutral decision out; no execution tools. */
public interface GameplayProviderAdapter {
  Provider provider();

  ProviderDecision adapt(String modelVersion, JsonNode providerOutput);
}
