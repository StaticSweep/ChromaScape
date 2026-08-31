package com.chromascape.web.replay.mismatch;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Compares expected (recorded) against actual (simulated) canonical
 * observations, applies tolerances, records mismatches, and implements
 * the stop policies.
 */
@Service
@Transactional
public class MismatchEngine {

    /** Domains compared during expectation evaluation. */
    private static final Set<String> COMPARED_DOMAINS = Set.of(
            "player.position", "player.inventory", "player.skills", "taskState");

    private final ReplayMismatchRepository mismatchRepository;
    private final CanonicalProjectionService projectionService;

    public MismatchEngine(ReplayMismatchRepository mismatchRepository,
                          CanonicalProjectionService projectionService) {
        this.mismatchRepository = mismatchRepository;
        this.projectionService = projectionService;
    }

    /**
     * Comparison result for one tick.
     */
    public record ComparisonResult(boolean shouldStop, List<ReplayMismatch> recorded) {
        public int hardCount() {
            return (int) recorded.stream().filter(m -> m.getLevel() == MismatchLevel.HARD).count();
        }
    }

    /**
     * Compares an expected observation fragment (from manifest expectations)
     * against the actual observation at a checkpoint.
     *
     * Expectations format:
     * { "domain.path": {"expected": <value>, "level": "HARD"|"SOFT", "tolerance": <num>} }
     * A bare value is treated as HARD exact-match.
     */
    public ComparisonResult compareAtCheckpoint(UUID runId, UUID attemptId,
                                                JsonNode expectations, JsonNode actualObservation,
                                                long tick, int sequence, ReplayStopPolicy stopPolicy) {
        List<ReplayMismatch> recorded = new ArrayList<>();
        if (expectations == null || !expectations.isObject()) {
            return new ComparisonResult(false, recorded);
        }
        var fields = expectations.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String pointer = entry.getKey();
            JsonNode spec = entry.getValue();

            MismatchLevel level = MismatchLevel.HARD;
            double tolerance = 0.0;
            JsonNode expectedValue = spec;
            if (spec.isObject() && spec.has("expected")) {
                expectedValue = spec.get("expected");
                level = parseLevel(spec.path("level").asText("HARD"));
                tolerance = spec.path("tolerance").asDouble(0.0);
            }

            String domain = domainOf(pointer);
            JsonNode actualValue = actualObservation.at(pointer.contains("/")
                    ? pointer : "/" + pointer.replace('.', '/'));
            if (actualValue.isMissingNode() && pointer.indexOf('.') < 0 && pointer.indexOf('/') < 0) {
                actualValue = actualObservation.path(pointer);
            }

            boolean matches = valuesMatch(expectedValue, actualValue, tolerance);
            if (!matches) {
                ReplayMismatch mismatch = record(runId, attemptId, level, "EXPECTATION_MISMATCH",
                        tick, sequence, domain, pointer,
                        truncate(expectedValue.toString()), truncate(actualValue.isMissingNode() ? "<missing>" : actualValue.toString()),
                        null, null, 0);
                recorded.add(mismatch);
                if (isStop(level, stopPolicy)) {
                    return new ComparisonResult(true, recorded);
                }
            }
        }
        boolean anyHardStop = recorded.stream()
                .anyMatch(m -> isStop(m.getLevel(), stopPolicy));
        return new ComparisonResult(anyHardStop, recorded);
    }

    /**
     * Records an externally-detected mismatch (e.g., infrastructure failure).
     */
    public ReplayMismatch record(UUID runId, UUID attemptId, MismatchLevel level, String ruleId,
                                 long tick, int sequence, String domain, String jsonPointer,
                                 String expectedValue, String actualValue,
                                 Integer relatedActionOrdinal, UUID causalParentId, int cascadeCount) {
        ReplayMismatch mismatch = new ReplayMismatch.Builder(runId, attemptId, level, ruleId)
                .firstTick(tick)
                .tickSequence(sequence)
                .domain(domain != null ? domain : domainOf(jsonPointer))
                .jsonPointer(jsonPointer)
                .expectedValue(expectedValue)
                .actualValue(actualValue)
                .relatedActionOrdinal(relatedActionOrdinal)
                .causalParentId(causalParentId)
                .cascadeCount(cascadeCount)
                .build();
        return mismatchRepository.save(mismatch);
    }

    /** True when this level under this policy halts the run. */
    public boolean isStop(MismatchLevel level, ReplayStopPolicy stopPolicy) {
        return switch (stopPolicy) {
            case STOP_ON_FIRST_HARD -> level == MismatchLevel.HARD;
            case COLLECT_UNTIL_CHECKPOINT -> level == MismatchLevel.HARD;
            case COLLECT_ALL, RUN_TO_END -> false;
        };
    }

    @Transactional(readOnly = true)
    public List<ReplayMismatch> getMismatches(UUID runId) {
        return mismatchRepository.findByRunIdOrderByFirstTickAscTickSequenceAsc(runId);
    }

    @Transactional(readOnly = true)
    public long countByLevel(UUID runId, MismatchLevel level) {
        return mismatchRepository.countByRunIdAndLevel(runId, level);
    }

    // ── helpers ──

    private boolean valuesMatch(JsonNode expected, JsonNode actual, double tolerance) {
        if (actual.isMissingNode()) {
            return false;
        }
        if (expected.isNumber() && actual.isNumber()) {
            return Math.abs(expected.asDouble() - actual.asDouble()) <= tolerance;
        }
        if (expected.equals(actual)) {
            return true;
        }
        // Canonical-form equality fallback (order-insensitive objects).
        String ce = projectionService.canonicalJson(expected);
        String ca = projectionService.canonicalJson(actual);
        return ce.equals(ca);
    }

    private static MismatchLevel parseLevel(String text) {
        try {
            return MismatchLevel.valueOf(text);
        } catch (IllegalArgumentException e) {
            return MismatchLevel.HARD;
        }
    }

    private static String domainOf(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "general";
        }
        int end = pointer.indexOf('.');
        if (end < 0) end = pointer.length();
        return pointer.substring(0, end);
    }

    private static String truncate(String v) {
        return v != null && v.length() > 512 ? v.substring(0, 509) + "..." : v;
    }
}
