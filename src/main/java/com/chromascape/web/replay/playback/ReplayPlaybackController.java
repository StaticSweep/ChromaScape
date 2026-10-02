package com.chromascape.web.replay.playback;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackCatalogResponse;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * xRSPS-neutral observed-playback endpoints for captured debugger evidence.
 *
 * <p>The versioned routes are canonical. The unversioned routes are one-cycle compatibility aliases
 * and intentionally delegate to exactly the same service and DTO schemas.
 */
@RestController
@RequestMapping("/api/replay/playback")
public class ReplayPlaybackController {

  private static final String DEPRECATION_HEADER = "Deprecation";
  private static final String LINK_HEADER = "Link";

  private final ReplayPlaybackService playbackService;
  private final ReplayPrincipalService principalService;

  public ReplayPlaybackController(
      ReplayPlaybackService playbackService, ReplayPrincipalService principalService) {
    this.playbackService = playbackService;
    this.principalService = principalService;
  }

  /** Lists a keyset page of linked recordings and their observed-evidence aggregates. */
  public ResponseEntity<ReplayPlaybackCatalogResponse> catalog(
      Principal principal,
      @RequestParam(required = false) String accountName,
      @RequestParam(required = false, defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    principalService.requirePrincipal(principal);
    return ResponseEntity.ok(playbackService.catalog(accountName, limit, cursor));
  }

  @GetMapping("/v1/recordings")
  public ResponseEntity<ReplayPlaybackCatalogResponse> catalogFiltered(
      Principal principal,
      @RequestParam(required = false) String accountName,
      @RequestParam(required = false) String taskKey,
      @RequestParam(required = false, defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    principalService.requirePrincipal(principal);
    return ResponseEntity.ok(playbackService.catalog(accountName, taskKey, limit, cursor, 0));
  }

  /**
   * Deprecated unversioned catalog alias. Offset remains available here solely for the migration
   * window; the canonical v1 route is cursor-only.
   */
  @Deprecated
  @GetMapping("/recordings")
  public ResponseEntity<ReplayPlaybackCatalogResponse> catalogAlias(
      Principal principal,
      @RequestParam(required = false) String accountName,
      @RequestParam(required = false, defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "0") int offset) {
    principalService.requirePrincipal(principal);
    ReplayPlaybackCatalogResponse response =
        playbackService.catalog(accountName, limit, cursor, offset);
    return ResponseEntity.ok()
        .header(DEPRECATION_HEADER, "true")
        .header(LINK_HEADER, "</api/replay/playback/v1/recordings>; rel=\"successor-version\"")
        .body(response);
  }

  /** Retrieves a bounded page of tick frames and logs for a recording. */
  @GetMapping("/v1/recordings/{recordingId}/frames")
  public ResponseEntity<ReplayPlaybackFramePageResponse> frames(
      Principal principal,
      @PathVariable String recordingId,
      @RequestParam(required = false) Long fromTick,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "100") int limit) {
    principalService.requirePrincipal(principal);
    return ResponseEntity.ok(
        playbackService.frames(requireRecordingIdForLink(recordingId), fromTick, cursor, limit));
  }

  /** Deprecated unversioned frame alias using the exact v1 response schema. */
  @Deprecated
  @GetMapping("/recordings/{recordingId}/frames")
  public ResponseEntity<ReplayPlaybackFramePageResponse> framesAlias(
      Principal principal,
      @PathVariable String recordingId,
      @RequestParam(required = false) Long fromTick,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "100") int limit) {
    principalService.requirePrincipal(principal);
    String normalizedRecordingId = requireRecordingIdForLink(recordingId);
    ReplayPlaybackFramePageResponse response =
        playbackService.frames(normalizedRecordingId, fromTick, cursor, limit);
    return ResponseEntity.ok()
        .header(DEPRECATION_HEADER, "true")
        .header(
            LINK_HEADER,
            "</api/replay/playback/v1/recordings/"
                + normalizedRecordingId
                + "/frames>; rel=\"successor-version\"")
        .body(response);
  }

  private static String requireRecordingIdForLink(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      throw ReplayException.badRequest(ReplayErrorCode.VALIDATION_ERROR, "recordingId is required");
    }
    return recordingId.trim();
  }
}
