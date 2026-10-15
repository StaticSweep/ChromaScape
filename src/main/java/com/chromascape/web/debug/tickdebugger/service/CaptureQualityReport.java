package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.recording.service.MeasuredVideoIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic evidence counts for one bound recording, without gameplay or dataset promotion. */
public final class CaptureQualityReport {
  private CaptureQualityReport() {}

  public static Map<String, Object> summarize(
      String sessionId,
      List<TickSnapshot> snapshots,
      Map<Long, MeasuredVideoIndex.Mapping> mappings) {
    Map<String, JsonNode> raw = new HashMap<>(), settled = new HashMap<>();
    Map<String, Long> outcomes = new TreeMap<>(), actions = new TreeMap<>();
    TreeSet<String> codes = new TreeSet<>();
    int missing = 0;
    Long previous = null;
    String producer = null;
    JsonNode epochTick = null, epochTime = null;
    for (TickSnapshot s : snapshots) {
      if (!sessionId.equals(s.getSessionId())) {
        throw new IllegalArgumentException("Cross-session quality");
      }
      if (previous != null && s.getTick() != previous + 1) {
        codes.add("TICK_GAP_OR_REGRESSION");
      }
      previous = s.getTick();
      JsonNode q = s.getCaptureQuality();
      if (q == null) {
        missing++;
        codes.add("CAPTURE_QUALITY_MISSING");
        continue;
      }
      CaptureQualityContract.validate(q, sessionId, s.getTick(), new ObjectMapper());
      if (producer != null
          && (!producer.equals(q.path("producerId").asText())
              || !epochTick.equals(q.path("epochTick"))
              || !epochTime.equals(q.path("epochCapturedAtEpochMillis")))) {
        codes.add("CAPTURE_EPOCH_DISCONTINUITY");
      }
      producer = q.path("producerId").asText();
      epochTick = q.path("epochTick");
      epochTime = q.path("epochCapturedAtEpochMillis");
      q.path("qualityCodes").forEach(code -> codes.add(code.asText()));
      if (!"MEASURED".equals(q.path("clock").path("status").asText())) {
        codes.add("CLOCK_CALIBRATION_UNAVAILABLE");
      }
      for (JsonNode e : q.path("rawEvents")) {
        unique(raw, e, codes);
      }
      for (JsonNode e : q.path("settlements")) {
        unique(settled, e, codes);
      }
      if (q.path("terminal").asBoolean() && q.path("pendingCount").asInt() != 0) {
        codes.add("TERMINAL_PENDING_INTENT");
      }
    }
    for (JsonNode e : settled.values()) {
      outcomes.merge(e.path("status").asText(), 1L, Long::sum);
      if ("CONFIRMED".equals(e.path("status").asText())) {
        actions.merge(e.path("actionType").asText(), 1L, Long::sum);
      }
    }
    if (raw.size() > settled.size()) {
      codes.add("RAW_OUTCOME_PENDING_OR_MISSING");
    }
    var errors =
        mappings.values().stream()
            .filter(m -> "MEASURED".equals(m.status()))
            .map(m -> Math.abs(m.residualMillis()) + m.uncertaintyMillis())
            .sorted()
            .toList();
    mappings.values().stream()
        .filter(m -> !"MEASURED".equals(m.status()))
        .forEach(m -> codes.addAll(m.qualityCodes()));
    Map<String, Object> report = new java.util.LinkedHashMap<>();
    report.put("schema", "capture-quality-report/v1");
    report.put("sessionId", sessionId);
    report.put("ticks", snapshots.size());
    report.put("missingQualityTicks", missing);
    report.put("rawEvents", raw.size());
    report.put("settledEvents", settled.size());
    report.put("outcomeCounts", outcomes);
    report.put("confirmedActionCounts", actions);
    report.put("qualityCodes", List.copyOf(codes));
    report.put("calibratedTicks", errors.size());
    report.put("maxAlignmentErrorMillis", errors.isEmpty() ? null : errors.get(errors.size() - 1));
    report.put(
        "p95AlignmentErrorMillis",
        errors.isEmpty()
            ? null
            : errors.get(Math.max(0, (int) Math.ceil(errors.size() * .95) - 1)));
    report.put("tickVideoMeasurements", mappings);
    report.put(
        "acceptanceVerified", false); // counts cannot certify the user's required action matrix
    return report;
  }

  private static void unique(Map<String, JsonNode> index, JsonNode e, TreeSet<String> codes) {
    JsonNode prior = index.putIfAbsent(e.path("eventId").asText(), e);
    if (prior != null && !prior.equals(e)) {
      codes.add("CONFLICTING_EVENT_IDENTITY");
    }
  }
}
