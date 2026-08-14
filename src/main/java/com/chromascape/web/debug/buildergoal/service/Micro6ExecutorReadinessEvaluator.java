package com.chromascape.web.debug.buildergoal.service;

/**
 * Single source of truth for Micro6 local-executor readiness classification. Both the relaunch
 * service (post-launch polling in {@link Micro6TargetRelaunchService}) and the patch-loop publish
 * health path ({@code ScriptExecutionService}) must agree on the evaluation ordering:
 *
 * <pre>port → health → account → login → world → script → dispatch</pre>
 *
 * <p>The port, health, and account phases are decided by the caller because they require network
 * probing and identity matching. This evaluator classifies the remaining phases — login, world,
 * script, and dispatch — from the health payload fields and returns the phase, a human-readable
 * reason, and whether the executor is publish-ready.
 */
public final class Micro6ExecutorReadinessEvaluator {

  public static final String PHASE_WAITING_FOR_LOGIN = "WAITING_FOR_LOGIN";
  public static final String PHASE_WAITING_FOR_WORLD = "WAITING_FOR_WORLD";
  public static final String PHASE_WAITING_FOR_SCRIPT = "WAITING_FOR_SCRIPT";
  public static final String PHASE_WAITING_FOR_DISPATCH = "WAITING_FOR_DISPATCH";
  public static final String PHASE_READY = "READY_TO_PUBLISH";

  private Micro6ExecutorReadinessEvaluator() {}

  public record Evaluation(String phase, String reason, boolean publishReady) {}

  /**
   * Classifies readiness using the canonical ordering login → world → script → dispatch. A client
   * that is still logging in is always reported as {@code WAITING_FOR_LOGIN}, never as {@code
   * WAITING_FOR_WORLD}, regardless of the observed world.
   */
  public static Evaluation evaluate(
      Boolean dispatchHold,
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason,
      boolean worldMatched,
      Integer requestedWorld,
      Integer observedWorld,
      boolean pathfinderReady,
      boolean scriptLoopAlive,
      String scriptLifecycleState) {
    if (isLoginIncomplete(loginState, dispatchHoldReason)) {
      return new Evaluation(
          PHASE_WAITING_FOR_LOGIN,
          buildDispatchHoldReason(
              dispatchHoldReason,
              startupState,
              loginState,
              loginAttempt,
              loginAttemptMax,
              loginTerminalReason),
          false);
    }
    if (!worldMatched) {
      return new Evaluation(
          PHASE_WAITING_FOR_WORLD,
          "Micro6 local executor has not logged into the requested world yet (requested="
              + requestedWorld
              + ", observed="
              + observedWorld
              + ")",
          false);
    }
    if (!scriptLoopAlive || !"RUNNING".equalsIgnoreCase(scriptLifecycleState)) {
      return new Evaluation(
          PHASE_WAITING_FOR_SCRIPT,
          "Micro6 local executor is logged in but AccountBuilder's execution loop is not live (lifecycle="
              + firstNonBlank(scriptLifecycleState, "UNKNOWN")
              + ")",
          false);
    }
    if (!isPublishReady(
        dispatchHold, startupState, loginState, loginTerminalReason, pathfinderReady)) {
      return new Evaluation(
          PHASE_WAITING_FOR_DISPATCH,
          buildDispatchHoldReason(
              dispatchHoldReason,
              startupState,
              loginState,
              loginAttempt,
              loginAttemptMax,
              loginTerminalReason),
          false);
    }
    return new Evaluation(PHASE_READY, null, true);
  }

  /**
   * Returns true when login has not completed yet; the specific login state is preserved by the
   * caller.
   */
  public static boolean isLoginIncomplete(String loginState, String dispatchHoldReason) {
    String login = normalizeOptionalString(loginState);
    // Readiness is deliberately fail-closed: a missing/blank login state is not evidence that
    // the executor is logged in. Older executors must be upgraded rather than being treated as
    // publish-ready from their other health fields.
    if (login == null || !"logged_in".equalsIgnoreCase(login)) {
      return true;
    }
    return false;
  }

  private static boolean isPublishReady(
      Boolean dispatchHold,
      String startupState,
      String loginState,
      String loginTerminalReason,
      boolean pathfinderReady) {
    // A missing dispatch_hold is not evidence that dispatch is permitted. Older executors must
    // report the field explicitly before they can become publish-ready.
    if (!Boolean.FALSE.equals(dispatchHold)) {
      return false;
    }
    if (!pathfinderReady) {
      return false;
    }
    String normalizedStartupState = normalizeOptionalString(startupState);
    if (!"accountbuilder_ready".equalsIgnoreCase(normalizedStartupState)) {
      return false;
    }
    String normalizedLoginState = normalizeOptionalString(loginState);
    if ("logged_in".equalsIgnoreCase(normalizedLoginState)) {
      return true;
    }
    if ("auto_login_in_progress".equalsIgnoreCase(normalizedLoginState)
        && loginTerminalReason == null) {
      return false;
    }
    return false;
  }

  static String buildDispatchHoldReason(
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason) {
    String normalizedLoginState = normalizeOptionalString(loginState);
    String normalizedStartupState = normalizeOptionalString(startupState);
    if ("initializing_bot_logic".equalsIgnoreCase(normalizedStartupState)) {
      return "Micro6 local executor is logged in but AccountBuilder bot logic is still initializing";
    }
    if ("bot_initialization_failed".equalsIgnoreCase(normalizedStartupState)) {
      return "Micro6 local executor failed to initialize AccountBuilder bot logic";
    }
    if ("logged_in".equalsIgnoreCase(normalizedLoginState)
        && normalizedStartupState != null
        && !"accountbuilder_ready".equalsIgnoreCase(normalizedStartupState)) {
      return "Micro6 local executor is logged in but AccountBuilder is not ready yet: "
          + normalizedStartupState;
    }
    if ("auto_login_failed".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for manual login after auto-login exhausted "
          + firstNonBlank(formatAttemptCounts(loginAttempt, loginAttemptMax), "its attempts");
    }
    if ("authenticator_required".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for manual authenticator input";
    }
    if ("auto_login_in_progress".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is attempting auto-login"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("profile_bootstrap_pending".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is preparing the requested profile for auto-login";
    }
    if ("waiting_for_profile_lock".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting to acquire the profile lock before auto-login"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("waiting_for_profile_record".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor could not find the requested profile yet"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("profile_bootstrap_failed".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor failed before auto-login could be armed";
    }
    if ("waiting_for_login_screen".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for the login screen"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("runtime_not_ready".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is still initializing the auto-login runtime";
    }
    if ("runtime_not_ready_timeout".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor auto-login runtime never became ready";
    }
    if ("login_screen_not_ready_timeout".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor login screen never became ready";
    }
    if ("waiting_for_login".equalsIgnoreCase(dispatchHoldReason)
        || "waiting_for_login".equalsIgnoreCase(normalizedLoginState)) {
      return "Micro6 local executor is waiting for login";
    }
    if ("starting_accountbuilder".equalsIgnoreCase(dispatchHoldReason)
        && startupState != null
        && !"starting_accountbuilder".equalsIgnoreCase(startupState)) {
      return "Micro6 local executor is online but not dispatch-ready: " + startupState;
    }
    if (loginTerminalReason != null && !loginTerminalReason.isBlank()) {
      return "Micro6 local executor is online but not dispatch-ready: " + loginTerminalReason;
    }
    return "Micro6 local executor is online but not dispatch-ready: "
        + firstNonBlank(dispatchHoldReason, startupState, normalizedLoginState, "dispatch_hold");
  }

  private static String formatAttemptCounts(Integer loginAttempt, Integer loginAttemptMax) {
    if (loginAttempt == null || loginAttemptMax == null || loginAttemptMax <= 0) {
      return null;
    }
    return loginAttempt + "/" + loginAttemptMax;
  }

  private static String formatAttemptSuffix(Integer loginAttempt, Integer loginAttemptMax) {
    String counts = formatAttemptCounts(loginAttempt, loginAttemptMax);
    return counts != null ? " (" + counts + ")" : "";
  }

  private static String normalizeOptionalString(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }
}
