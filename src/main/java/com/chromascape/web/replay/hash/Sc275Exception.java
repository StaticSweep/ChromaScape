package com.chromascape.web.replay.hash;

/**
 * Stable-coded failure for SC-275 causal verification. Carries the wire code in the message and
 * exposes it via {@link #getCode()}; carries no HTTP semantics so verifiers stay usable from
 * workers, importers, and tests alike.
 */
public class Sc275Exception extends IllegalArgumentException {

  private final String code;

  /** Creates a coded failure; the code prefixes the message. */
  public Sc275Exception(String code, String message) {
    super(code + ": " + message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
