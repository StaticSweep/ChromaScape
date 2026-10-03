package com.chromascape.web.aiplayer.catalog;

import java.util.Map;

/**
 * Stable bounded operator-facing failure; never contains upstream credentials or settings dumps.
 */
public final class CatalogProblem extends RuntimeException {
  private final int status;
  private final String code;
  private final Map<String, String> errors;

  /** Creates this component with its explicit dependencies or bounded failure details. */
  public CatalogProblem(int status, String code, String message) {
    this(status, code, message, Map.of());
  }

  /** Creates this component with its explicit dependencies or bounded failure details. */
  public CatalogProblem(int status, String code, String message, Map<String, String> errors) {
    super(message);
    this.status = status;
    this.code = code;
    this.errors = Map.copyOf(errors);
  }

  /** Returns the HTTP status for this failure. */
  public int status() {
    return status;
  }

  /** Returns the stable failure code. */
  public String code() {
    return code;
  }

  /** Returns bounded errors keyed by the invalid field. */
  public Map<String, String> errors() {
    return errors;
  }
}
