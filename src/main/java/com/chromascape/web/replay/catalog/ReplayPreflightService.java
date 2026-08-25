package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightRequest;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightResponse;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Produces a full {@link ReplayPreflightResponse} by combining recording integrity
 * checks with curation and capability context.
 */
@Service
@Transactional(readOnly = true)
public class ReplayPreflightService {

    private final ReplayRecordingResolver resolver;
    private final RecordingIntegrityService integrityService;
    private final ReplayCapabilityService capabilityService;
    private final RecordingCurationRepository curationRepository;

    public ReplayPreflightService(ReplayRecordingResolver resolver,
                                  RecordingIntegrityService integrityService,
                                  ReplayCapabilityService capabilityService,
                                  RecordingCurationRepository curationRepository) {
        this.resolver = resolver;
        this.integrityService = integrityService;
        this.capabilityService = capabilityService;
        this.curationRepository = curationRepository;
    }

    public ReplayPreflightResponse preflight(String recordingId, ReplayPreflightRequest request) {
        ResolvedRecording resolved = resolver.resolve(recordingId);
        ReplayEligibility eligibility = integrityService.evaluate(resolved);

        boolean simulationEligible = eligibility.simulationEligible();
        // Degrade simulation eligibility if xRSPS is not available
        if (simulationEligible && !capabilityService.isAvailable()) {
            simulationEligible = false;
            eligibility = new ReplayEligibility(
                    eligibility.playbackEligible(),
                    eligibility.policyReplayEligible(),
                    false,
                    eligibility.blockingReasons()
            );
            eligibility.blockingReasons().add(
                    new com.chromascape.web.replay.catalog.dto.ReplayBlockingReason(
                            ReplayMode.CAUSAL_SIMULATION,
                            ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                            "xRSPS kernel is not available",
                            "Start the xRSPS executable and verify the capability handshake"
                    )
            );
        }

        List<RecordingCuration> curations = curationRepository
                .findByRecordingIdOrderByVersionDesc(recordingId);

        RecordingSession recording = resolved.recording();
        DebugSession debugSession = resolved.debugSession();

        return new ReplayPreflightResponse(
                recordingId,
                debugSession != null ? debugSession.getSessionId() : null,
                eligibility,
                request != null ? request.requestedMode() : null,
                curations,
                capabilityService.getManifest()
        );
    }
}