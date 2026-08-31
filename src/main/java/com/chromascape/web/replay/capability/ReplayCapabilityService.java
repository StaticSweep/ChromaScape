package com.chromascape.web.replay.capability;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Holds the last-known xRSPS capability. Defaults to unavailable until
 * startup validation succeeds.
 */
@Service
public class ReplayCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(ReplayCapabilityService.class);

    private volatile CapabilityManifest currentManifest;
    private volatile long lastHandshakeAttemptMs;

    public ReplayCapabilityService() {
        this.currentManifest = unavailable();
    }

    /** Returns the current capability manifest (never null). */
    public CapabilityManifest getCapabilities() {
        return currentManifest;
    }

    /** Alias for getCapabilities(). Called by ReplayCapabilityController and ReplayPreflightService. */
    public CapabilityManifest getManifest() {
        return currentManifest;
    }

    /** True when the xRSPS process has passed a capability handshake. Called by ReplayPreflightService. */
    public boolean isAvailable() {
        CapabilityManifest m = currentManifest;
        return m != null && m.modes() != null && m.modes().causalSimulation();
    }

    /** True when causal simulation is currently available. */
    public boolean isSimulationAvailable() {
        CapabilityManifest m = currentManifest;
        return m != null && m.modes() != null && m.modes().causalSimulation();
    }

    /** True only when the worker explicitly identifies an authoritative certifiable runtime. */
    public boolean isAuthoritativeCertifiable() {
        CapabilityManifest m = currentManifest;
        return isSimulationAvailable()
                && m.certifiable()
                && "XRSPS_AUTHORITATIVE".equals(m.fidelity());
    }

    /** Returns whether the current worker declares support for a semantic action. */
    public boolean supportsAction(String actionType) {
        return actionType != null
                && currentManifest != null
                && currentManifest.supportedActions().contains(actionType);
    }

    /** Updates the current manifest from a successful handshake. */
    public void updateFromHandshake(CapabilityManifest manifest) {
        this.currentManifest = manifest;
        this.lastHandshakeAttemptMs = System.currentTimeMillis();
        log.info(
                "xRSPS capability updated: simulation={}, adapter={}, build={}",
                manifest.modes().causalSimulation(),
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

    /** Throws if causal simulation is not available. */
    public void requireSimulationAvailable() {
        if (!isSimulationAvailable()) {
            throw new ReplayException(
                    ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    true,
                    "Causal simulation is not available",
                    "Verify xRSPS is running and the capability handshake succeeded");
        }
    }

    private static CapabilityManifest unavailable() {
        return new CapabilityManifest(
                1,
                false,
                new CapabilityManifest.CapabilityModes(true, false, false),
                java.util.List.of(1, 2),
                java.util.List.of(1),
                java.util.List.of(1),
                java.util.List.of(1),
                java.util.List.of("sha256-canonical-json-v1"),
                new CapabilityManifest.XrspsIdentity("", "", "", ""),
                false,
                false,
                "SYNTHETIC_CONTRACT",
                java.util.List.of(),
                null);
    }
}
