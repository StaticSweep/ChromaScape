package com.chromascape.web.debug.patchloop.dto;

/**
 * Request to compile a Mermaid graph without executing it.
 *
 * <p>Validates the Mermaid syntax and returns the compiled graph structure.
 */
public class CompileRequest {

  /** The Mermaid graph source to compile */
  private String mermaid;

  public CompileRequest() {}

  public CompileRequest(String mermaid) {
    this.mermaid = mermaid;
  }

  public String getMermaid() {
    return mermaid;
  }

  public void setMermaid(String mermaid) {
    this.mermaid = mermaid;
  }
}
