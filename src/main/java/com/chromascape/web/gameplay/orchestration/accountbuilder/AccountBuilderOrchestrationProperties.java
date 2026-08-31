package com.chromascape.web.gameplay.orchestration.accountbuilder;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Default-off SC-1431 operator and delivery configuration. */
@Component
@ConfigurationProperties(prefix = "gameplay.accountbuilder-orchestration")
public final class AccountBuilderOrchestrationProperties {
  private boolean enabled;
  private boolean liveDeliveryEnabled;
  private String operatorKey = "";

  @PostConstruct
  void validate() {
    if ((enabled || liveDeliveryEnabled) && (operatorKey == null || operatorKey.length() < 32)) {
      throw new IllegalStateException(
          "gameplay.accountbuilder-orchestration.operator-key must contain at least 32 characters"
              + " when enabled");
    }
    if (liveDeliveryEnabled && !enabled) {
      throw new IllegalStateException("Live delivery cannot be enabled while orchestration is off");
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isLiveDeliveryEnabled() {
    return liveDeliveryEnabled;
  }

  public void setLiveDeliveryEnabled(boolean liveDeliveryEnabled) {
    this.liveDeliveryEnabled = liveDeliveryEnabled;
  }

  public String getOperatorKey() {
    return operatorKey;
  }

  public void setOperatorKey(String operatorKey) {
    this.operatorKey = operatorKey;
  }
}
