package com.chromascape.web.recording.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import org.springframework.stereotype.Service;

/** Shared replay, training extraction and read-only scoring lineage. Never dispatches gameplay. */
@Service
public class CanonicalFrameConsumers {
  private final CanonicalFrameStore frames;
  private final BehaviorRunContextService contexts;
  private final ObjectMapper json;

  public CanonicalFrameConsumers(
      CanonicalFrameStore frames, BehaviorRunContextService contexts, ObjectMapper json) {
    this.frames = frames;
    this.contexts = contexts;
    this.json = json;
  }

  public JsonNode page(String recordingId, BigInteger after, int limit, boolean training) {
    if (training) contexts.requireTrainingContext(recordingId);
    int size = Math.max(1, Math.min(200, limit));
    var found = frames.page(recordingId, after, size + 1);
    boolean more = found.size() > size;
    var stored = found.subList(0, Math.min(size, found.size()));
    ObjectNode result = json.createObjectNode();
    result.set("runContext", contexts.context(recordingId));
    var entries = result.putArray("entries");
    for (var frame : stored) {
      var entry = entries.addObject();
      entry.set("frame", frame.payload());
      entry.set("observation", CanonicalObservation.from(frame).payload());
    }
    result.put(
        "nextSequence",
        stored.isEmpty() ? after.toString() : stored.get(stored.size() - 1).sequence().toString());
    result.put("hasMore", more);
    return result;
  }

  public JsonNode score(String recordingId, BigInteger sequence, JsonNode request) {
    if (sequence == null || sequence.signum() < 1)
      throw new ProvenanceException("INVALID_SEQUENCE");
    BehaviorSemanticContract.conform(request.path("contract"));
    var stored = frames.page(recordingId, sequence.subtract(BigInteger.ONE), 1);
    if (stored.isEmpty() || !stored.get(0).sequence().equals(sequence))
      throw new ProvenanceException("FRAME_NOT_FOUND");
    var frame = stored.get(0);
    var observation = CanonicalObservation.from(frame).payload();
    var scores = request.path("scores");
    var ids = BehaviorSemanticContract.actionIds();
    if (!scores.isArray() || scores.size() != ids.size())
      throw new ProvenanceException("SCORE_DIMENSION_MISMATCH");
    int selected = -1;
    double best = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < ids.size(); i++) {
      if (!scores.get(i).isNumber() || !Double.isFinite(scores.get(i).asDouble()))
        throw new ProvenanceException("INVALID_SCORE");
      if (observation.path("legalMask").get(i).asBoolean() && scores.get(i).asDouble() > best) {
        selected = i;
        best = scores.get(i).asDouble();
      }
    }
    ObjectNode result =
        json.createObjectNode()
            .put("schemaVersion", 1)
            .put("source", "RECOMMENDATION")
            .put("actionId", ids.get(selected))
            .put("frameDigest", frame.digest())
            .put("semanticContractDigest", BehaviorSemanticContract.digest())
            .put("outcome", "UNKNOWN")
            .put("dispatched", false);
    result.set("observation", observation);
    result.set("runContext", contexts.context(recordingId));
    result.set("observedEvents", frame.payload().path("semanticEvents"));
    return result;
  }
}
