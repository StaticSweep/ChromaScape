package com.chromascape.web.gameplay.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The three Micro6 reads a v2 bootstrap draft needs.
 *
 * <p>All three are read-only. The catalog and observe responses attest the executor's identity and
 * revisions; the observation route carries what the client actually sees. Nothing here reserves,
 * starts or mutates executor state.
 */
public interface BootstrapV2Wire {
  /** {@code GET /api/v2/gameplay/catalog}. */
  JsonNode catalog();

  /** {@code GET /api/v2/gameplay/observe}. */
  JsonNode observe();

  /** {@code GET /api/v1/gameplay/observation}. */
  JsonNode observation();
}
