package com.chromascape.web.aiplayer;

import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages shadow-mode play sessions where the AI observes human gameplay and builds behaviour
 * policies.
 *
 * <p>Shadow sessions collect tick-level decisions and actions without taking control, creating
 * datasets for imitation learning.
 */
@Service
public class ShadowPolicyService {

  private static final Logger log = LoggerFactory.getLogger(ShadowPolicyService.class);

  private final DecisionRepository decisionRepository;

  @Value("${features.ai-player.enabled:false}")
  private boolean enabled;

  @Value("${features.ai-player.shadow-mode:false}")
  private boolean shadowMode;

  @Value("${features.ai-player.control-enabled:false}")
  private boolean controlEnabled;

  public ShadowPolicyService(DecisionRepository decisionRepository) {
    this.decisionRepository = decisionRepository;
  }

  @PostConstruct
  void enforceReadOnlyAtStartup() {
    if (controlEnabled) {
      log.warn(
          "FEATURES_AI_PLAYER_CONTROL was requested, but AI Routine permanently enforces control_allowed=false");
    }
  }

  /**
   * Starts a new shadow observation session.
   *
   * @param accountName the account being observed
   * @param scriptName the script being run
   * @return session ID
   */
  public String startShadowSession(String accountName, String scriptName) {
    if (!enabled || !shadowMode) {
      log.debug("Shadow mode disabled, skipping session creation");
      return null;
    }

    String sessionId = "shadow_" + UUID.randomUUID().toString().substring(0, 8);
    log.info(
        "Shadow session started: {} for account={}, script={}", sessionId, accountName, scriptName);
    return sessionId;
  }

  /**
   * Ends a shadow observation session and records summary.
   *
   * @param sessionId the session to end
   */
  public void endShadowSession(String sessionId) {
    if (sessionId == null) return;
    log.info("Shadow session ended: {}", sessionId);
  }

  /**
   * Gets the current shadow policy recommendation for a given game state.
   *
   * <p>This is a placeholder for future ML-based policy inference. Currently returns a summary of
   * recent decisions.
   *
   * @param recordingSessionId the current recording session
   * @param tick the current tick
   * @return policy summary or null if not available
   */
  public String getPolicyRecommendation(String recordingSessionId, int tick) {
    if (!enabled) return null;

    Long sessionId;
    try {
      sessionId = Long.parseLong(recordingSessionId);
    } catch (NumberFormatException e) {
      log.debug("Invalid recording session ID: {}", recordingSessionId);
      return null;
    }

    // Look at recent decisions for context
    List<DecisionEntity> recentDecisions =
        decisionRepository.findByRecordingSessionIdOrderByTimestampDesc(sessionId);
    if (recentDecisions.isEmpty()) {
      return null;
    }

    int sampleSize = Math.min(recentDecisions.size(), 10);
    long successCount =
        recentDecisions.stream()
            .limit(sampleSize)
            .filter(d -> Boolean.TRUE.equals(d.getSuccess()))
            .count();

    return String.format(
        "Recent decision success rate: %d/%d (%.0f%%)",
        successCount, sampleSize, (double) successCount / sampleSize * 100);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isShadowMode() {
    return shadowMode;
  }

  public boolean isControlEnabled() {
    return false;
  }

  public boolean isControlRequested() {
    return controlEnabled;
  }

  /**
   * AI Routine is permanently read-only in this slice. The configured control flag is retained only
   * for diagnostics and is never an authority decision.
   */
  public boolean isControlAllowed() {
    return false;
  }
}
