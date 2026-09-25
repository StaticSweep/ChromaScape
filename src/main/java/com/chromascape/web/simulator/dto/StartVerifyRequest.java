package com.chromascape.web.simulator.dto;

/**
 * Request to start a new simulator verification job.
 *
 * <p>Validates that at minimum a session identifier is provided. All other fields are optional with
 * sensible defaults.
 */
public class StartVerifyRequest {

  /**
   * Session identifier to verify.
   *
   * <p>Use "latest" to fetch the most recent deterministic session from the bridge, or provide a
   * specific session ID.
   */
  private String session;

  /**
   * Optional scenario to run.
   *
   * <p>Valid values: None (default), S1-happy-path, S2-input-heavy, S3-reconnect, S4-out-of-order,
   * S5-low-activity.
   */
  private String scenario;

  /** Optional bridge URL. Defaults to the configured default bridge URL. */
  private String bridgeUrl;

  /** Optional API key. Defaults to the configured API key. */
  private String apiKey;

  public StartVerifyRequest() {}

  public StartVerifyRequest(String session, String scenario, String bridgeUrl, String apiKey) {
    this.session = session;
    this.scenario = scenario;
    this.bridgeUrl = bridgeUrl;
    this.apiKey = apiKey;
  }

  public String getSession() {
    return session;
  }

  public void setSession(String session) {
    this.session = session;
  }

  public String getScenario() {
    return scenario;
  }

  public void setScenario(String scenario) {
    this.scenario = scenario;
  }

  public String getBridgeUrl() {
    return bridgeUrl;
  }

  public void setBridgeUrl(String bridgeUrl) {
    this.bridgeUrl = bridgeUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }
}
