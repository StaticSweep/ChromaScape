package com.chromascape.web.gameplay.provider.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Closed provider-facing gateway for durable, budgeted, read-only decision tools. */
@FunctionalInterface
public interface ProviderToolGateway {
  JsonNode invoke(String toolCallId, String toolName, Map<String, String> arguments);
}
