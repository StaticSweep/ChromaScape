package com.chromascape.web.recording.provenance;

/** Stable capture contract rejection without leaking task settings. */
public class ProvenanceException extends IllegalArgumentException {
  private final String code;

  public ProvenanceException(String code) {
    super(code);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
