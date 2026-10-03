package com.chromascape.web.recording.provenance;

import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Authorized read and separately operator-authenticated audited legacy assignment. */
@RestController
@RequestMapping("/api/recordings/v1/sessions")
public class RecordingProvenanceController {
  private final BehaviorRunContextService contexts;
  private final ReplayPrincipalService principals;
  private final CanonicalFrameConsumers consumers;

  public RecordingProvenanceController(
      BehaviorRunContextService contexts,
      ReplayPrincipalService principals,
      CanonicalFrameConsumers consumers) {
    this.contexts = contexts;
    this.principals = principals;
    this.consumers = consumers;
  }

  @GetMapping("/{recordingId}/frames")
  public JsonNode frames(
      @PathVariable String recordingId,
      @RequestParam(defaultValue = "0") java.math.BigInteger afterSequence,
      @RequestParam(defaultValue = "200") int limit,
      @RequestParam(defaultValue = "false") boolean training,
      Principal principal) {
    principals.requirePrincipal(principal);
    return consumers.page(recordingId, afterSequence, limit, training);
  }

  @PostMapping("/{recordingId}/frames/{sequence}/score")
  public JsonNode score(
      @PathVariable String recordingId,
      @PathVariable java.math.BigInteger sequence,
      @RequestBody JsonNode request,
      Principal principal) {
    principals.requirePrincipal(principal);
    return consumers.score(recordingId, sequence, request);
  }

  @GetMapping("/{recordingId}/provenance")
  public Map<String, Object> get(@PathVariable String recordingId, Principal principal) {
    principals.requirePrincipal(principal);
    var out = new HashMap<String, Object>();
    out.put("runContext", contexts.context(recordingId));
    out.put("eligibility", contexts.eligibility(recordingId));
    out.put("origin", contexts.recording(recordingId).getProvenanceOrigin());
    return out;
  }

  @PostMapping("/{recordingId}/provenance")
  public JsonNode assign(
      @PathVariable String recordingId, @RequestBody JsonNode request, HttpServletRequest servlet) {
    Object actor = servlet.getAttribute("aiRoutineOperatorId");
    // The operator interceptor is mandatory on this mutation; never trust a body actor.
    if (actor == null) throw new ProvenanceException("OPERATOR_AUTH_REQUIRED");
    return contexts.assign(
        recordingId, request.path("runContext"), actor.toString(), request.path("reason").asText());
  }
}
