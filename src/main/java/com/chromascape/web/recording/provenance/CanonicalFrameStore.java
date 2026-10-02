package com.chromascape.web.recording.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durably accepts immutable frames before acknowledging; legacy tick projections are derived. */
@Service
public class CanonicalFrameStore {
  private final JdbcTemplate jdbc;
  private final BehaviorRunContextService contexts;
  private final ObjectMapper json;

  public CanonicalFrameStore(
      JdbcTemplate jdbc, BehaviorRunContextService contexts, ObjectMapper json) {
    this.jdbc = jdbc;
    this.contexts = contexts;
    this.json = json;
  }

  @Transactional
  public Receipt accept(String recordingId, JsonNode input) {
    JsonNode context = contexts.context(recordingId);
    CanonicalTickFrame frame = CanonicalTickFrame.validate(input, context);
    String session = input.path("sessionId").asText();
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,609))", Object.class, "frame:" + session);
    var existing =
        jdbc.queryForList(
            "SELECT content_hash FROM recording_tick_frames WHERE session_id=? AND sequence=?",
            String.class,
            session,
            new java.math.BigDecimal(frame.sequence()));
    if (!existing.isEmpty()) {
      if (!existing.get(0).equals(frame.digest()))
        throw new ProvenanceException("SEQUENCE_CONFLICT");
      BigInteger acknowledged =
          jdbc.queryForObject(
              "SELECT max(sequence) FROM recording_tick_frames WHERE session_id=?",
              BigInteger.class,
              session);
      return new Receipt(acknowledged.toString(), false, frame.payload());
    }
    BigInteger last =
        jdbc.queryForObject(
            "SELECT COALESCE(max(sequence),0) FROM recording_tick_frames WHERE session_id=?",
            BigInteger.class,
            session);
    if (!frame.sequence().equals(last.add(BigInteger.ONE)))
      throw new ProvenanceException("SEQUENCE_GAP");
    var priorQuality =
        jdbc.queryForList(
            "SELECT quality::text FROM recording_capture_quality WHERE session_id=?",
            String.class,
            session);
    ObjectNode summary =
        priorQuality.isEmpty()
            ? json.createObjectNode().put("acceptedFrames", 0).put("dropped", 0).put("unknown", 0)
            : (ObjectNode) parse(priorQuality.get(0));
    if (summary.has("closure") && !summary.path("closure").asText().equals("DISCONNECT"))
      throw new ProvenanceException("SESSION_FINALIZED");
    var incomingIds = new java.util.ArrayList<String>();
    input.path("semanticEvents").forEach(event -> incomingIds.add(event.path("eventId").asText()));
    if (!incomingIds.isEmpty()
        && jdbc.queryForObject(
                "SELECT count(*) FROM recording_tick_frames f WHERE session_id=? AND EXISTS (SELECT 1 FROM jsonb_array_elements(f.frame->'semanticEvents') event WHERE event->>'eventId'=ANY(string_to_array(?,',')))",
                Long.class,
                session,
                String.join(",", incomingIds))
            > 0) throw new ProvenanceException("DUPLICATE_EVENT_ID");
    JsonNode quality = input.path("quality");
    BigInteger eventSequence = new BigInteger(summary.path("lastEventSequence").asText("0"));
    for (JsonNode event : input.path("semanticEvents")) {
      BigInteger incoming = event.path("eventSequence").bigIntegerValue();
      if (!incoming.equals(eventSequence.add(BigInteger.ONE)))
        throw new ProvenanceException("EVENT_SEQUENCE_GAP");
      eventSequence = incoming;
    }
    summary.put("lastEventSequence", eventSequence.toString());
    summary.put("acceptedFrames", summary.path("acceptedFrames").asLong() + 1);
    for (String field : new String[] {"expected", "observed", "dropped", "unknown"})
      summary.put(field, addChecked(summary.path(field).asLong(), quality.path(field).asLong()));
    for (String field : new String[] {"gapRanges", "unsupportedFamilies", "captureLimitations"}) {
      var array = summary.withArray(field);
      for (JsonNode item : quality.path(field)) array.add(item.deepCopy());
      if (array.size() > 4096) throw new ProvenanceException("QUALITY_QUEUE_LIMIT");
    }
    ObjectNode counters = summary.withObject("eventFamilyCounters");
    quality
        .path("eventFamilyCounters")
        .fields()
        .forEachRemaining(
            entry -> {
              counters.put(
                  entry.getKey(),
                  addChecked(counters.path(entry.getKey()).asLong(), entry.getValue().asLong()));
            });
    summary
        .put("producerVersion", quality.path("producerVersion").asText())
        .put("adapterVersion", quality.path("adapterVersion").asText());
    summary.put("lastObservedSequence", frame.sequence().toString());
    if (!summary.has("firstObservedSequence"))
      summary.put("firstObservedSequence", frame.sequence().toString());
    jdbc.update(
        "INSERT INTO recording_tick_frames(session_id,sequence,producer_epoch,content_hash,frame,accepted_at) VALUES(?,?,?::uuid,?,?::jsonb,now())",
        session,
        new java.math.BigDecimal(frame.sequence()),
        input.path("producerEpoch").asText(),
        frame.digest(),
        frame.payload().toString());
    jdbc.update(
        "INSERT INTO recording_capture_quality(session_id,quality,updated_at) VALUES(?,?::jsonb,now()) ON CONFLICT(session_id) DO UPDATE SET quality=excluded.quality,updated_at=now()",
        session,
        summary.toString());
    return new Receipt(frame.sequence().toString(), true, frame.payload());
  }

  @Transactional
  public void close(String session, String cause) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,609))", Object.class, "frame:" + session);
    var rows =
        jdbc.queryForList(
            "SELECT quality::text FROM recording_capture_quality WHERE session_id=? AND finalized=false",
            String.class,
            session);
    if (rows.isEmpty()) {
      var existing =
          jdbc.queryForList(
              "SELECT finalized FROM recording_capture_quality WHERE session_id=?",
              Boolean.class,
              session);
      if (!existing.isEmpty()) return;
      ObjectNode quality =
          json.createObjectNode()
              .put("acceptedFrames", 0)
              .put("dropped", 0)
              .put("unknown", 1)
              .put("closure", cause);
      quality.putArray("gapRanges");
      quality.putArray("unsupportedFamilies").add("CAPTURE_NOT_OBSERVED");
      jdbc.update(
          "INSERT INTO recording_capture_quality(session_id,quality,finalized,updated_at) VALUES(?,?::jsonb,true,now())",
          session,
          quality.toString());
      return;
    }
    ObjectNode quality = (ObjectNode) parse(rows.get(0));
    quality.put("closure", cause);
    if (!"EXPLICIT_STOP".equals(cause))
      quality.put("unknown", addChecked(quality.path("unknown").asLong(), 1));
    jdbc.update(
        "UPDATE recording_capture_quality SET quality=?::jsonb,finalized=true,updated_at=now() WHERE session_id=?",
        quality.toString(),
        session);
  }

  /** Rejected/malformed/overflow data is durable quality evidence, never silently ignored. */
  @Transactional
  public void reject(String session, String code) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,609))", Object.class, "frame:" + session);
    var rows =
        jdbc.queryForList(
            "SELECT quality::text FROM recording_capture_quality WHERE session_id=?",
            String.class,
            session);
    ObjectNode quality =
        rows.isEmpty()
            ? json.createObjectNode().put("acceptedFrames", 0).put("dropped", 0).put("unknown", 0)
            : (ObjectNode) parse(rows.get(0));
    quality.put("dropped", addChecked(quality.path("dropped").asLong(), 1));
    quality.put("expected", addChecked(quality.path("expected").asLong(), 1));
    var errors = quality.withArray("rejections");
    if (errors.size() < 100) errors.add(code);
    quality.withArray("gapRanges");
    quality.withArray("unsupportedFamilies");
    jdbc.update(
        "INSERT INTO recording_capture_quality(session_id,quality,updated_at) VALUES(?,?::jsonb,now()) ON CONFLICT(session_id) DO UPDATE SET quality=excluded.quality,updated_at=now()",
        session,
        quality.toString());
  }

  /** Bounded sequence traversal; every consumer validates the same stored immutable frame. */
  @Transactional(readOnly = true)
  public java.util.List<CanonicalTickFrame> page(String recordingId, BigInteger after, int limit) {
    if (after == null || after.signum() < 0 || after.bitLength() > 64)
      throw new ProvenanceException("INVALID_FRAME_CURSOR");
    JsonNode context = contexts.context(recordingId);
    if (context == null) throw new ProvenanceException("RUN_CONTEXT_REQUIRED");
    return jdbc
        .queryForList(
            "SELECT frame::text FROM recording_tick_frames WHERE session_id=? AND sequence>? ORDER BY sequence LIMIT ?",
            String.class,
            context.path("debugSessionId").asText(),
            new java.math.BigDecimal(after),
            Math.max(1, Math.min(201, limit)))
        .stream()
        .map(value -> CanonicalTickFrame.validate(parse(value), context))
        .toList();
  }

  @Transactional(readOnly = true)
  public CanonicalTickFrame atTick(String recordingId, long tick) {
    JsonNode context = contexts.context(recordingId);
    if (context == null) throw new ProvenanceException("RUN_CONTEXT_REQUIRED");
    var values =
        jdbc.queryForList(
            "SELECT frame::text FROM recording_tick_frames WHERE session_id=? AND (frame->>'tick')::bigint=? LIMIT 2",
            String.class,
            context.path("debugSessionId").asText(),
            tick);
    if (values.size() != 1) throw new ProvenanceException("AMBIGUOUS_OR_MISSING_FRAME");
    return CanonicalTickFrame.validate(parse(values.get(0)), context);
  }

  private static long addChecked(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException error) {
      throw new ProvenanceException("QUALITY_COUNTER_OVERFLOW");
    }
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored capture quality", e);
    }
  }

  public record Receipt(String acceptedSequence, boolean newFrame, JsonNode frame) {}
}
