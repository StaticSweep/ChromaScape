package com.chromascape.web.debug.tickdebugger.video.controller;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoImportMetadata;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoMediaRange;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoMultipartReader;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoYouTube;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAccess;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoCatalogService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoExportService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoFrameIndexService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoImportService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoJobService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoObservationsService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoPreviewService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReadiness;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReconciliationService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRequestGuard;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRestrictedLauncher;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewSessionService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoSchedulingService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoTelemetry;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoWorkerRegistry;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoWriteNonceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * External video import, catalog, media, review and export API. Every protected route derives its
 * owner from the server-established principal and refuses before reading private data or upload
 * bytes. Mutations additionally require a same-origin request carrying a server-issued write nonce.
 */
@RestController
@RequestMapping("/api/debug/mouse-videos")
public class MouseVideoController {

  private static final String MEDIA_CSP = "sandbox; default-src 'none'";

  private final MouseVideoAccess access;
  private final MouseVideoImportService imports;
  private final MouseVideoCatalogService catalog;
  private final MouseVideoJobService jobService;
  private final MouseVideoExportService exportService;
  private final MouseVideoReviewService reviewService;
  private final MouseVideoFrameIndexService frameIndexService;
  private final MouseVideoObservationsService observationsService;
  private final MouseVideoSchedulingService schedulingService;
  private final MouseVideoPreviewService previewService;
  private final MouseVideoRestrictedLauncher restrictedLauncher;
  private final MouseVideoRequestGuard guard;
  private final MouseVideoWriteNonceService nonces;
  private final MouseVideoTelemetry telemetry;
  private final MouseVideoWorkerRegistry workerRegistry;
  private final MouseVideoReviewSessionService reviewSessions;
  private final MouseVideoReconciliationService reconciliation;
  private final MouseVideoReadiness readiness;
  private final ObjectMapper mapper;

  /**
   * Every prediction-bearing route fails closed when the review-policy guard is unavailable: the
   * dependency is required, so a missing bean prevents startup instead of silently bypassing
   * exposure and membership enforcement.
   */
  private void predictionAccess(MouseVideoAccess.Actor actor, UUID sourceId, String kind) {
    reviewSessions.expose(actor.owner(), actor.auditActor(), sourceId, kind);
  }

  public MouseVideoController(
      MouseVideoAccess access,
      MouseVideoImportService imports,
      MouseVideoCatalogService catalog,
      MouseVideoJobService jobService,
      MouseVideoExportService exportService,
      MouseVideoReviewService reviewService,
      MouseVideoFrameIndexService frameIndexService,
      MouseVideoObservationsService observationsService,
      MouseVideoSchedulingService schedulingService,
      MouseVideoPreviewService previewService,
      MouseVideoRestrictedLauncher restrictedLauncher,
      MouseVideoRequestGuard guard,
      MouseVideoWriteNonceService nonces,
      MouseVideoTelemetry telemetry,
      MouseVideoWorkerRegistry workerRegistry,
      MouseVideoReviewSessionService reviewSessions,
      MouseVideoReconciliationService reconciliation,
      MouseVideoReadiness readiness,
      ObjectMapper mapper) {
    this.access = access;
    this.imports = imports;
    this.catalog = catalog;
    this.jobService = jobService;
    this.exportService = exportService;
    this.reviewService = reviewService;
    this.frameIndexService = frameIndexService;
    this.observationsService = observationsService;
    this.schedulingService = schedulingService;
    this.previewService = previewService;
    this.restrictedLauncher = restrictedLauncher;
    this.guard = guard;
    this.nonces = nonces;
    this.telemetry = telemetry;
    this.workerRegistry = workerRegistry;
    this.reviewSessions = reviewSessions;
    this.reconciliation = reconciliation;
    this.readiness = readiness;
    this.mapper = mapper;
  }

  @GetMapping("/capabilities")
  public Map<String, Object> capabilities() {
    Map<String, Object> body = new LinkedHashMap<>(catalog.capabilities());
    body.put("readiness", readiness.report());
    return body;
  }

  @GetMapping("/sandbox")
  public Map<String, Object> sandbox(Principal principal) {
    access.require(principal);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("launcher", restrictedLauncher.status());
    return body;
  }

  @GetMapping("/write-nonce")
  public Map<String, Object> writeNonce(Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    var issued = nonces.issue(actor.owner());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nonce", issued.nonce());
    body.put("expiresAt", issued.expiresAt());
    return body;
  }

  @GetMapping("/observability")
  public Map<String, Object> observability(Principal principal) {
    access.require(principal);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("counters", telemetry.snapshot());
    body.put("reviewProtocol", reviewSessions.counters());
    body.put("ownedWorkers", workerRegistry.ownedCount());
    body.put("queue", jobService.coverage());
    return body;
  }

  @PostMapping("/imports/upload")
  public ResponseEntity<Map<String, Object>> upload(HttpServletRequest request, Principal principal)
      throws IOException {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    String requestId = requireUuid(request.getHeader("X-Mouse-Video-Request-Id"));
    long declared = request.getContentLengthLong();
    if (declared > MouseVideoMultipartReader.MAX_REQUEST_BYTES) {
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Upload exceeds the configured request size limit");
    }
    MouseVideoImportService.MouseVideoIntakeReservationHandle reservation = null;
    Path staging = null;
    try {
      // Reserve admissible capacity before any body byte is materialized. The request UUID is a
      // header so it is known before the bounded stream is consumed.
      reservation = imports.reserve(actor, requestId, Math.max(0L, declared));
      staging = Files.createTempDirectory(imports.stagingRoot(), "mouse-video-intake-");
      MouseVideoMultipartReader.StagedUpload staged =
          MouseVideoMultipartReader.read(
              request.getInputStream(),
              request.getContentType(),
              staging,
              mapper,
              imports.intakeFileLimit(),
              MouseVideoMultipartReader.MAX_REQUEST_BYTES);
      MouseVideoImportMetadata parsed = staged.metadata();
      MouseVideoImportMetadata metadata =
          new MouseVideoImportMetadata(
              requestId,
              parsed.declaredOrigin(),
              parsed.taskKey(),
              parsed.skill(),
              parsed.contextLabel(),
              parsed.placeLabel());
      Map<String, Object> receipt =
          imports.submitUpload(
              actor,
              metadata,
              reservation.reservationId(),
              staged.mediaPath(),
              staged.sizeBytes(),
              staged.checksum());
      return ResponseEntity.accepted().body(receipt);
    } catch (MouseVideoException rejected) {
      if (reservation != null) {
        imports.release(reservation);
      }
      throw rejected;
    } catch (MouseVideoMultipartReader.MultipartLimitExceededException limit) {
      if (reservation != null) {
        imports.release(reservation);
      }
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT,
          "Upload exceeds the configured multipart size limit",
          limit);
    } catch (MouseVideoMultipartReader.MalformedMultipartException malformed) {
      if (reservation != null) {
        imports.release(reservation);
      }
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST,
          "Upload is not valid multipart form data",
          malformed);
    } catch (IOException failed) {
      if (reservation != null) {
        imports.release(reservation);
      }
      throw failed;
    } catch (RuntimeException failed) {
      if (reservation != null) {
        imports.release(reservation);
      }
      throw failed;
    } finally {
      if (staging != null) {
        deleteRecursively(staging);
      }
    }
  }

  @PostMapping("/imports/youtube")
  public ResponseEntity<Map<String, Object>> youtube(
      @RequestBody Map<String, Object> body, HttpServletRequest request, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    MouseVideoImportMetadata metadata = metadataFrom(body);
    String url = stringField(body, "url");
    MouseVideoYouTube.Canonical canonical =
        MouseVideoYouTube.canonicalize(url)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_INVALID_REQUEST,
                        "Only a supported youtube.com/watch?v=… or youtu.be/… single-video link is"
                            + " accepted"));
    Map<String, Object> receipt =
        imports.submitYouTube(actor, metadata, canonical.canonicalUrl(), canonical.providerId());
    return ResponseEntity.accepted().body(receipt);
  }

  @GetMapping("/sources")
  public Map<String, Object> sources(
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", defaultValue = "10") int limit,
      @RequestParam(value = "kind", required = false) String kind,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    Map<String, Object> result = catalog.list(actor.owner(), cursor, limit, kind);
    if (result.get("items") instanceof java.util.List<?> items) {
      for (Object item : items) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) item;
        reviewSessions.protectCatalog(
            actor.owner(), actor.auditActor(), parseId((String) entry.get("id")), entry);
      }
    }
    return result;
  }

  @GetMapping("/sources/{sourceId}")
  public Map<String, Object> source(@PathVariable String sourceId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    Map<String, Object> result = catalog.get(actor.owner(), parseId(sourceId));
    return reviewSessions.protectCatalog(
        actor.owner(), actor.auditActor(), parseId(sourceId), result);
  }

  @GetMapping("/sources/{sourceId}/media/{role}")
  public ResponseEntity<Resource> media(
      @PathVariable String sourceId,
      @PathVariable String role,
      @RequestParam(value = "analysisId", required = false) String analysisId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
      Principal principal)
      throws IOException {
    MouseVideoAccess.Actor actor = access.require(principal);
    MouseVideoCatalogService.MediaHandle handle =
        catalog.media(actor.owner(), parseId(sourceId), role, optionalId(analysisId));
    HttpHeaders headers = mediaHeaders(handle.mediaType());
    try {
      var parsed = MouseVideoMediaRange.parse(rangeHeader, handle.sizeBytes());
      if (parsed.isEmpty()) {
        return ResponseEntity.ok()
            .headers(headers)
            .contentLength(handle.sizeBytes())
            .body(new PathSliceResource(handle.path(), 0L, handle.sizeBytes()));
      }
      MouseVideoMediaRange.ByteRange range = parsed.get();
      headers.set(
          HttpHeaders.CONTENT_RANGE,
          "bytes " + range.start() + "-" + range.end() + "/" + handle.sizeBytes());
      return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
          .headers(headers)
          .contentLength(range.length())
          .body(new PathSliceResource(handle.path(), range.start(), range.length()));
    } catch (MouseVideoMediaRange.InvalidRangeException unsatisfiable) {
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + handle.sizeBytes())
          .build();
    }
  }

  private static ResponseEntity<Resource> serveSlice(
      Path path, long sizeBytes, String mediaType, String rangeHeader) {
    HttpHeaders headers = mediaHeaders(mediaType);
    try {
      var parsed = MouseVideoMediaRange.parse(rangeHeader, sizeBytes);
      if (parsed.isEmpty()) {
        return ResponseEntity.ok()
            .headers(headers)
            .contentLength(sizeBytes)
            .body(new PathSliceResource(path, 0L, sizeBytes));
      }
      MouseVideoMediaRange.ByteRange range = parsed.get();
      headers.set(
          HttpHeaders.CONTENT_RANGE,
          "bytes " + range.start() + "-" + range.end() + "/" + sizeBytes);
      return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
          .headers(headers)
          .contentLength(range.length())
          .body(new PathSliceResource(path, range.start(), range.length()));
    } catch (MouseVideoMediaRange.InvalidRangeException unsatisfiable) {
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + sizeBytes)
          .build();
    }
  }

  private static HttpHeaders mediaHeaders(String mediaType) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.set(HttpHeaders.CONTENT_TYPE, mediaType);
    headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"media\"");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Content-Security-Policy", MEDIA_CSP);
    headers.setCacheControl(CacheControl.noStore().cachePrivate());
    return headers;
  }

  @GetMapping("/jobs/{jobId}")
  public Map<String, Object> job(@PathVariable String jobId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    return toMap(jobService.view(jobService.requireOwned(parseId(jobId), actor.owner())));
  }

  @PostMapping("/jobs/{jobId}/cancel")
  public ResponseEntity<Map<String, Object>> cancel(
      @PathVariable String jobId,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest request,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    long expectedRevision = 0L;
    if (body != null && body.get("expectedRevision") instanceof Number number) {
      expectedRevision = number.longValue();
    }
    UUID id = parseId(jobId);
    MouseVideoJobService.JobView cancelled =
        jobService.view(jobService.requestCancellation(id, actor.owner(), expectedRevision));
    telemetry.cancellationRequested();
    if ("CANCELLING".equals(cancelled.status())) {
      boolean owned = workerRegistry.requestCancel(id);
      boolean cleanedUp =
          owned && workerRegistry.awaitCleanup(id, Duration.ofSeconds(30).toMillis());
      if (owned && cleanedUp) {
        cancelled = jobService.view(jobService.confirmCancelled(id, actor.owner()));
      } else {
        cancelled =
            jobService.view(
                jobService.confirmReconciliation(
                    id,
                    actor.owner(),
                    owned
                        ? "Owned worker cleanup could not be confirmed"
                        : "No owned worker exists in this process; cleanup must be reconciled"));
      }
    }
    HttpStatus status =
        "CANCELLED".equals(cancelled.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
    return ResponseEntity.status(status).body(toMap(cancelled));
  }

  /**
   * Settles the owner's NEEDS_RECONCILIATION job once cleanup is proven (no worker, no mounted
   * workspace, no unattributed video scope). Otherwise 409 with the blocker; nothing changes.
   */
  @PostMapping("/jobs/{jobId}/reconcile")
  public Map<String, Object> reconcile(
      @PathVariable String jobId, HttpServletRequest request, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    Map<String, Object> settled =
        toMap(jobService.view(reconciliation.reconcile(parseId(jobId), actor.owner())));
    telemetry.reconciliationResolved();
    return settled;
  }

  @GetMapping("/sources/{sourceId}/exports")
  public Map<String, Object> exports(@PathVariable String sourceId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    Map<String, Object> body = new LinkedHashMap<>();
    predictionAccess(actor, parseId(sourceId), "EXPORT");
    body.put("items", exportService.list(actor.owner(), parseId(sourceId)));
    return body;
  }

  @PostMapping("/sources/{sourceId}/exports")
  public ResponseEntity<Map<String, Object>> createExport(
      @PathVariable String sourceId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    String requestId = requireUuid(stringField(body, "requestId"));
    UUID analysisId = optionalId(stringField(body, "analysisId"));
    Long reviewRevision =
        body.get("reviewRevision") instanceof Number number ? number.longValue() : null;
    predictionAccess(actor, parseId(sourceId), "EXPORT");
    MouseVideoExportService.ExportBundle bundle =
        exportService.create(
            actor.owner(), parseId(sourceId), requestId, analysisId, reviewRevision);
    if (bundle.created()) {
      telemetry.exportCreated();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("exportId", bundle.snapshot().getId().toString());
    response.put("manifestChecksum", bundle.snapshot().getManifestChecksum());
    response.put("sizeBytes", bundle.snapshot().getSizeBytes());
    response.put("created", bundle.created());
    response.put("downloadUrl", "/api/debug/mouse-videos/exports/" + bundle.snapshot().getId());
    return ResponseEntity.status(bundle.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(response);
  }

  @GetMapping("/exports/{exportId}")
  public ResponseEntity<Resource> downloadExport(
      @PathVariable String exportId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    reviewSessions.exposeExport(actor.owner(), actor.auditActor(), parseId(exportId));
    MouseVideoExportService.ExportHandle handle =
        exportService.retrieve(actor.owner(), parseId(exportId));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mouse-video-export.json\"")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", MEDIA_CSP)
        .cacheControl(CacheControl.noStore().cachePrivate())
        .contentLength(handle.sizeBytes())
        .body(new PathSliceResource(handle.path(), 0L, handle.sizeBytes()));
  }

  @GetMapping("/exports/{exportId}/manifest")
  public ResponseEntity<Map<String, Object>> exportManifest(
      @PathVariable String exportId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    reviewSessions.exposeExport(actor.owner(), actor.auditActor(), parseId(exportId));
    JsonNode manifest = readJson(exportService.manifestJson(actor.owner(), parseId(exportId)));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("exportId", exportId);
    body.put("manifest", manifest);
    return ResponseEntity.ok(body);
  }

  /**
   * Bounded page of the frozen frame index of one completed analysis. Read-only: this route never
   * decodes a frame and never creates analysis work.
   */
  @GetMapping("/sources/{sourceId}/analyses/{analysisId}/frame-index")
  public Map<String, Object> frameIndex(
      @PathVariable String sourceId,
      @PathVariable String analysisId,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    return frameIndexService.page(
        actor.owner(), parseId(sourceId), parseId(analysisId), cursor, limit);
  }

  /**
   * Bounded page of published observations for one completed analysis. An analysis without a
   * published observation artifact reports that absence instead of inventing observations.
   */
  @GetMapping("/sources/{sourceId}/analyses/{analysisId}/observations")
  public Map<String, Object> observations(
      @PathVariable String sourceId,
      @PathVariable String analysisId,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", defaultValue = "100") int limit,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "sincePts", required = false) String sincePts,
      @RequestParam(value = "untilPts", required = false) String untilPts,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    predictionAccess(actor, parseId(sourceId), "OBSERVATIONS");
    return observationsService.list(
        actor.owner(),
        parseId(sourceId),
        parseId(analysisId),
        cursor,
        limit,
        kind,
        sincePts,
        untilPts);
  }

  /** Schedules a bounded visual analysis. An equivalent completed analysis is returned READY. */
  @PostMapping("/sources/{sourceId}/analyses")
  public ResponseEntity<Map<String, Object>> scheduleAnalysis(
      @PathVariable String sourceId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    String requestId = requireUuid(stringField(body, "requestId"));
    predictionAccess(actor, parseId(sourceId), "ANALYSIS");
    MouseVideoSchedulingService.Receipt receipt =
        schedulingService.scheduleAnalysis(
            actor.owner(),
            parseId(sourceId),
            requestId,
            stringField(body, "algorithmId"),
            stringField(body, "templatePackId"));
    return ResponseEntity.status(
            "PENDING".equals(receipt.state()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(analysisReceipt(receipt));
  }

  /**
   * Schedules one exact source-frame preview. Repeated equivalent requests coalesce onto the same
   * preview/job; a changed body reusing a request identifier is a conflict.
   */
  @PostMapping("/sources/{sourceId}/analyses/{analysisId}/frame-previews")
  public ResponseEntity<Map<String, Object>> schedulePreview(
      @PathVariable String sourceId,
      @PathVariable String analysisId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    String requestId = requireUuid(stringField(body, "requestId"));
    long ordinal = integralField(body, "frameOrdinal");
    int maxWidth = optionalInt(body, "maxWidth", 0);
    int maxHeight = optionalInt(body, "maxHeight", 0);
    MouseVideoSchedulingService.Receipt receipt =
        schedulingService.schedulePreview(
            actor.owner(),
            parseId(sourceId),
            parseId(analysisId),
            requestId,
            ordinal,
            maxWidth,
            maxHeight);
    return ResponseEntity.status(
            "PENDING".equals(receipt.state()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(previewReceipt(receipt));
  }

  @GetMapping("/sources/{sourceId}/analyses/{analysisId}/frame-previews")
  public Map<String, Object> previews(
      @PathVariable String sourceId, @PathVariable String analysisId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", previewService.list(actor.owner(), parseId(sourceId), parseId(analysisId)));
    return body;
  }

  @GetMapping("/frame-previews/{previewId}")
  public Map<String, Object> preview(@PathVariable String previewId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    return previewService.get(actor.owner(), parseId(previewId));
  }

  @GetMapping("/frame-previews/{previewId}/media")
  public ResponseEntity<Resource> previewMedia(
      @PathVariable String previewId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
      Principal principal)
      throws IOException {
    MouseVideoAccess.Actor actor = access.require(principal);
    MouseVideoPreviewService.PreviewMedia media =
        previewService.media(actor.owner(), parseId(previewId));
    return serveSlice(media.path(), media.sizeBytes(), media.mediaType(), rangeHeader);
  }

  @GetMapping("/sources/{sourceId}/reviews")
  public Map<String, Object> reviews(@PathVariable String sourceId, Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    predictionAccess(actor, parseId(sourceId), "LEGACY_HISTORY");
    return reviewService.list(actor.owner(), parseId(sourceId));
  }

  @GetMapping("/reviews/{revisionId}")
  public Map<String, Object> review(
      @PathVariable String revisionId,
      @RequestParam("sourceId") String sourceId,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    predictionAccess(actor, parseId(sourceId), "LEGACY_HISTORY");
    return reviewService.get(actor.owner(), parseId(sourceId), parseId(revisionId));
  }

  @PostMapping("/sources/{sourceId}/reviews")
  public ResponseEntity<Map<String, Object>> createReview(
      @PathVariable String sourceId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      Principal principal) {
    MouseVideoAccess.Actor actor = access.require(principal);
    guard.requireWrite(request, actor);
    String requestId = requireUuid(stringField(body, "requestId"));
    UUID analysisId = optionalId(stringField(body, "analysisId"));
    long expectedBaseRevision =
        body.get("expectedBaseRevision") instanceof Number number ? number.longValue() : 0L;
    String reason = stringField(body, "reason");
    String sourceUse = stringField(body, "sourceUse");
    JsonNode corrections =
        body.get("corrections") == null ? null : mapper.valueToTree(body.get("corrections"));
    predictionAccess(actor, parseId(sourceId), "LEGACY_REVIEW");
    Map<String, Object> created =
        reviewService.create(
            actor.owner(),
            actor.auditActor(),
            parseId(sourceId),
            requestId,
            analysisId,
            expectedBaseRevision,
            reason,
            sourceUse,
            corrections);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @ExceptionHandler(MouseVideoException.class)
  public ResponseEntity<Map<String, Object>> rejected(
      MouseVideoException error, HttpServletRequest request) {
    telemetry.refused(error.getCode());
    String path = request == null ? "" : request.getRequestURI();
    if (path.contains("/imports/")) {
      telemetry.intakeRefused();
    } else if (path.contains("/media/")) {
      telemetry.mediaRefused();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", error.getCode());
    body.put("message", error.getMessage());
    body.put("retryable", error.isRetryable());
    return ResponseEntity.status(statusFor(error.getCode())).body(body);
  }

  private static HttpStatus statusFor(String code) {
    return switch (code) {
      case MouseVideoException.CODE_INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
      case MouseVideoException.CODE_UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
      case MouseVideoException.CODE_FORBIDDEN -> HttpStatus.FORBIDDEN;
      case MouseVideoException.CODE_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case MouseVideoException.CODE_CONFLICT -> HttpStatus.CONFLICT;
      case MouseVideoException.CODE_MEDIA_LIMIT -> HttpStatus.PAYLOAD_TOO_LARGE;
      case MouseVideoException.CODE_UNSUPPORTED_MEDIA,
          MouseVideoException.CODE_DECODER_FAILED,
          MouseVideoException.CODE_NO_VISIBLE_CURSOR,
          MouseVideoException.CODE_CURSOR_UNSUPPORTED,
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          MouseVideoException.CODE_SOURCE_AUTH_REQUIRED,
          MouseVideoException.CODE_CHECKSUM_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
      case MouseVideoException.CODE_QUOTA_EXCEEDED,
          MouseVideoException.CODE_SOURCE_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
      case MouseVideoException.CODE_TOOL_UNAVAILABLE,
          MouseVideoException.CODE_DISABLED,
          MouseVideoException.CODE_CLEANUP_UNCONFIRMED -> HttpStatus.SERVICE_UNAVAILABLE;
      case MouseVideoException.CODE_WORKER_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
      default -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  private static Map<String, Object> analysisReceipt(MouseVideoSchedulingService.Receipt receipt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("analysisId", receipt.analysisId() == null ? null : receipt.analysisId().toString());
    body.put("jobId", receipt.jobId() == null ? null : receipt.jobId().toString());
    body.put("state", receipt.state());
    body.put(
        "statusUrl",
        receipt.jobId() == null ? null : "/api/debug/mouse-videos/jobs/" + receipt.jobId());
    return body;
  }

  private static Map<String, Object> previewReceipt(MouseVideoSchedulingService.Receipt receipt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("previewId", receipt.previewId() == null ? null : receipt.previewId().toString());
    body.put("analysisId", receipt.analysisId() == null ? null : receipt.analysisId().toString());
    body.put("jobId", receipt.jobId() == null ? null : receipt.jobId().toString());
    body.put("state", receipt.state());
    body.put("frameOrdinal", receipt.frameOrdinal());
    body.put("pts", receipt.framePts());
    body.put(
        "metadataUrl",
        receipt.previewId() == null
            ? null
            : "/api/debug/mouse-videos/frame-previews/" + receipt.previewId());
    body.put(
        "statusUrl",
        receipt.jobId() == null ? null : "/api/debug/mouse-videos/jobs/" + receipt.jobId());
    return body;
  }

  private static Map<String, Object> toMap(MouseVideoJobService.JobView view) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", view.id());
    body.put("sourceId", view.sourceId());
    body.put("kind", view.kind());
    body.put("stage", view.stage());
    body.put("status", view.status());
    body.put("progress", view.progress());
    body.put("attempt", view.attempt());
    body.put("generation", view.generation());
    body.put("cancellationRevision", view.cancellationRevision());
    body.put("errorCode", view.errorCode());
    body.put("errorMessage", view.errorMessage());
    body.put("cleanupReason", view.cleanupReason());
    return body;
  }

  private MouseVideoImportMetadata metadataFrom(Map<String, Object> body) {
    return new MouseVideoImportMetadata(
        requireUuid(stringField(body, "requestId")),
        stringField(body, "declaredOrigin"),
        stringField(body, "taskKey"),
        stringField(body, "skill"),
        stringField(body, "context"),
        stringField(body, "place"));
  }

  private static String stringField(Map<String, Object> body, String name) {
    Object value = body == null ? null : body.get(name);
    return value == null ? null : value.toString();
  }

  private static String requireUuid(String value) {
    if (value == null || value.isBlank()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "A requestId UUID is required");
    }
    try {
      return UUID.fromString(value.trim()).toString();
    } catch (IllegalArgumentException invalid) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "requestId must be a UUID");
    }
  }

  private static long integralField(Map<String, Object> body, String name) {
    Object value = body == null ? null : body.get(name);
    if (value instanceof Number number) {
      long parsed = number.longValue();
      if (parsed >= 0) {
        return parsed;
      }
    }
    throw new MouseVideoException(
        MouseVideoException.CODE_INVALID_REQUEST, name + " must be a non-negative integer");
  }

  private static int optionalInt(Map<String, Object> body, String name, int fallback) {
    Object value = body == null ? null : body.get(name);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new MouseVideoException(
        MouseVideoException.CODE_INVALID_REQUEST, name + " must be numeric");
  }

  private static UUID optionalId(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return parseId(value);
  }

  private static UUID parseId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new MouseVideoException(MouseVideoException.CODE_INVALID_REQUEST, "Invalid identifier");
    }
  }

  private JsonNode readJson(String value) {
    try {
      return mapper.readTree(value == null ? "null" : value);
    } catch (IOException invalid) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Export manifest is unreadable");
    }
  }

  private static void deleteRecursively(Path root) {
    try {
      if (!Files.exists(root)) {
        return;
      }
      try (var walk = Files.walk(root)) {
        walk.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException ignored) {
                    // best-effort staging cleanup
                  }
                });
      }
    } catch (IOException ignored) {
      // best-effort staging cleanup
    }
  }

  /** Byte-slice resource that reports a known length without reading the file. */
  private static final class PathSliceResource extends AbstractResource {
    private final Path path;
    private final long offset;
    private final long length;

    private PathSliceResource(Path path, long offset, long length) {
      this.path = path;
      this.offset = offset;
      this.length = length;
    }

    @Override
    public String getDescription() {
      return "mouse-video media slice";
    }

    @Override
    public InputStream getInputStream() throws IOException {
      if (offset == 0L) {
        return MouseVideoMediaRange.slice(
            path, new MouseVideoMediaRange.ByteRange(0L, Math.max(0L, length - 1L)));
      }
      return MouseVideoMediaRange.slice(
          path, new MouseVideoMediaRange.ByteRange(offset, offset + length - 1L));
    }

    @Override
    public long contentLength() {
      return length;
    }

    @Override
    public boolean exists() {
      return true;
    }

    @Override
    public URI getURI() {
      return path.toUri();
    }
  }
}
