package com.chromascape.web.replay.worker;

import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable result of the committed lease-expiry recovery transaction. Carries exactly the evidence
 * the separate recovery-report writer needs so report construction never has to touch the recovery
 * transaction itself.
 *
 * @param runId recovered run
 * @param attemptId expired attempt that produced the terminal evidence
 * @param state terminal state applied to the run
 * @param outcome terminal outcome applied to the run
 * @param terminalReason stable reason code applied to the run
 * @param terminalAt the single PostgreSQL timestamp shared by the run and the future report
 */
public record RecoveredRunDecision(
    UUID runId,
    UUID attemptId,
    ReplayRunState state,
    ReplayOutcome outcome,
    String terminalReason,
    LocalDateTime terminalAt) {}
