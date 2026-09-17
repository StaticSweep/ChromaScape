package com.chromascape.web.aiplayer.routine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Operator authentication for the AI-routine candidate workflow.
 *
 * <p>All candidate read/mutation endpoints require {@code Authorization: Bearer <token>} matching
 * {@code ai-routine.operator-token} (compared in constant time). The configured {@code
 * ai-routine.operator-id} is the auditable operator identity persisted into approval hashes and
 * audit events; there is no servlet {@code Principal} bridge.
 */
@Configuration
@ConfigurationProperties(prefix = "ai-routine.operator")
public class AiRoutineOperatorProperties {

  /** Bearer token required for candidate endpoints. Empty disables the candidate workflow. */
  private String token = "";

  /** Auditable operator identity recorded in approvals and audit events. */
  private String id = "operator";

  public String getToken() {
    return token;
  }

  public void setToken(String value) {
    token = value == null ? "" : value;
  }

  public String getId() {
    return id;
  }

  public void setId(String value) {
    id = value == null ? "operator" : value;
  }
}
