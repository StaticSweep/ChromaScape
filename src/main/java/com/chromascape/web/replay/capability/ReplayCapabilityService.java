package com.chromascape.web.replay.capability;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Holds the last-known xRSPS capability. Defaults to unavailable until startup validation succeeds.
 *
 * <p>One authoritative eligibility predicate lives here: {@link #isCausalSimulationCertifiable()}.
 * Compiler, preflight, run creation, and worker startup must all ask THIS question — missing
 * capability data always fails closed.
 */
@Service
public class ReplayCapabilityService {

  private static final Logger log = LoggerFactory.getLogger(ReplayCapabilityService.class);
  private static final Pattern SHA256_ADDRESS = Pattern.compile("^sha256:[0-9a-f]{64}$");

  private volatile CapabilityManifest currentManifest;
  private volatile long lastHandshakeAttemptMs;

  public ReplayCapabilityService() {
    this.currentManifest = unavailable();
  }

  /** Returns the current capability manifest (never null). */
  public CapabilityManifest getCapabilities() {
    return currentManifest;
  }

  /**
   * Alias for getCapabilities(). Called by ReplayCapabilityController and ReplayPreflightService.
   */
  public CapabilityManifest getManifest() {
    return currentManifest;
  }

  /** True when the xRSPS process has passed a production-eligible capability handshake. */
  public boolean isAvailable() {
    return isCausalSimulationCertifiable();
  }

  /**
   * THE authoritative causal-eligibility predicate. Requires ALL of: a handshake manifest, the
   * causal-simulation mode, an explicitly certifiable worker identity, non-blank identity fields,
   * SHA-256-addressed cache/content revisions, policy replay unavailable, control disallowed, and
   * OpenRune unavailable. Any missing or contradictory data fails closed.
   */
  public boolean isCausalSimulationCertifiable() {
    CapabilityManifest m = currentManifest;
    if (m == null || m.modes() == null || m.xrsps() == null) {
      return false;
    }
    if (m.modes().observedPlayback() || !m.modes().causalSimulation() || m.modes().policyReplay()) {
      return false;
    }
    if (m.controlAllowed() || m.openRuneAvailable()) {
      return false;
    }
    if (!m.certifiable()) {
      return false; // missing is never true
    }
    // A clean/certifiable Git identity proves reproducibility of the worker build, not fidelity
    // with the authoritative xRSPS game services. The interim pinned worker is a synthetic
    // contract harness and must remain diagnostic-only for production simulation.
    if (m.fidelity() != CapabilityManifest.Fidelity.XRSPS_AUTHORITATIVE) {
      return false;
    }
    if (!CapabilityManifest.V1_SUPPORTED_ACTIONS.equals(m.supportedActions())) {
      return false;
    }
    CapabilityManifest.XrspsIdentity x = m.xrsps();
    return nonBlank(x.adapterVersion())
        && nonBlank(x.buildCommit())
        && !isPlaceholder(x.buildCommit())
        && SHA256_ADDRESS.matcher(x.cacheRevision()).matches()
        && SHA256_ADDRESS.matcher(x.contentRevision()).matches();
  }

  /** Compatibility alias used by the replay-v2 catalog and manifest compiler. */
  public boolean isAuthoritativeCertifiable() {
    return isCausalSimulationCertifiable();
  }

  /** Legacy alias retained for existing callers; identical to the certifiable predicate. */
  public boolean isSimulationAvailable() {
    return isCausalSimulationCertifiable();
  }

  /** Updates the current manifest from a successful handshake. */
  public void updateFromHandshake(CapabilityManifest manifest) {
    this.currentManifest = manifest;
    this.lastHandshakeAttemptMs = System.currentTimeMillis();
    log.info(
        "xRSPS capability updated: simulation={}, certifiable={}, adapter={}, build={}",
        manifest.modes().causalSimulation(),
        manifest.certifiable(),
        manifest.xrsps().adapterVersion(),
        manifest.xrsps().buildCommit());
  }

  /** Marks the simulator as unavailable with a reason. */
  public void markUnavailable(String reason) {
    this.currentManifest = unavailable();
    this.lastHandshakeAttemptMs = System.currentTimeMillis();
    log.warn("Simulation marked unavailable: {}", reason);
  }

  /** Returns the timestamp of the last handshake attempt. */
  public long getLastHandshakeAttemptMs() {
    return lastHandshakeAttemptMs;
  }

  /** Returns the duration since the last handshake. */
  public Duration timeSinceLastHandshake() {
    return Duration.ofMillis(System.currentTimeMillis() - lastHandshakeAttemptMs);
  }

  /** Throws unless causal simulation is certifiably available right now. */
  public void requireSimulationAvailable() {
    if (!isCausalSimulationCertifiable()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "Certifiable causal simulation is not available",
          "Verify xRSPS is running, certified, and the capability handshake succeeded");
    }
  }

  private static boolean nonBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static boolean isPlaceholder(String v) {
    return "dev".equals(v) || "headless-deterministic".equals(v);
  }

  /**
   * The unavailable manifest is strictly fail-closed: EVERY executable mode is false — policy
   * replay can never appear here, and even observed playback reads false because an un-validated
   * subsystem must not advertise any capability at all.
   */
  private static CapabilityManifest unavailable() {
    return new CapabilityManifest(
        1,
        false,
        new CapabilityManifest.CapabilityModes(false, false, false),
        java.util.List.of(1),
        java.util.List.of(1),
        java.util.List.of(1),
        java.util.List.of(1),
        java.util.List.of("sha256-canonical-json-v1"),
        new CapabilityManifest.XrspsIdentity("", "", "", ""),
        false,
        false,
        CapabilityManifest.Fidelity.SYNTHETIC_CONTRACT,
        CapabilityManifest.V1_SUPPORTED_ACTIONS);
  }
}
