package com.chromascape.web.gameplay.contract.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;

/**
 * Declared SC-1429/v2 bootstrap policy artifact.
 *
 * <p>{@code semanticObservation.policyDigest} and {@code planningInput.policyDigest} are mandatory
 * v2 fields, and {@link GameplayV2EligibilityFilter} fails the whole bundle with {@code
 * POLICY_DENIED} when the two disagree. The value must therefore describe a real declared policy,
 * not a placeholder: this class is that declaration, and {@link #digest()} is its canonical digest.
 *
 * <p>The bounds are the bounded single-run bootstrap limits (one action, one attempt, five minutes,
 * kill switch scoped to the run). They are deliberately narrower than the executor's catalog
 * {@code maxPolicyBounds}; a policy may tighten the catalog bounds but never widen them, which the
 * eligibility filter enforces independently.
 */
public final class GameplayV2BootstrapPolicy {
  /** Task the bootstrap policy authorises. */
  public static final String TASK_KEY = "FISHING_TRAINING";

  /** Maximum semantic actions authorised by this policy. */
  public static final int MAX_ACTIONS = 1;

  /** Maximum attempts authorised by this policy. */
  public static final int MAX_ATTEMPTS = 1;

  /** Maximum elapsed time authorised by this policy. */
  public static final Duration MAX_ELAPSED = Duration.ofMinutes(5);

  /** Kill switch scope authorised by this policy. */
  public static final String KILL_SWITCH_SCOPE = "RUN";

  private GameplayV2BootstrapPolicy() {}

  /**
   * The declared policy as the canonical v2 object. Field order is irrelevant: the digest is
   * computed over lexicographically sorted keys.
   */
  public static ObjectNode document(ObjectMapper objectMapper) {
    ObjectNode policy = objectMapper.createObjectNode();
    policy.put("contractVersion", GameplayV2Contracts.VERSION);
    policy.put("taskKey", TASK_KEY);
    policy.put("maxActions", MAX_ACTIONS);
    policy.put("maxAttempts", MAX_ATTEMPTS);
    policy.put("maxElapsedMs", MAX_ELAPSED.toMillis());
    policy.put("killSwitchScope", KILL_SWITCH_SCOPE);
    return policy;
  }

  /** Canonical digest of {@link #document(ObjectMapper)}; the only accepted policy digest. */
  public static String digest(ObjectMapper objectMapper) {
    return GameplayV2EligibilityFilter.canonicalDigest(objectMapper, document(objectMapper));
  }
}
