package com.chromascape.web.replay.session;

import com.chromascape.web.replay.security.ReplayPrincipalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, owner-scoped session lifecycle and recorded-journal scheduling. */
@RestController
@RequestMapping("/api/xrsps/sessions")
@Validated
public class XrspsSessionsController {

  private final XrspsSessionExecutionService service;
  private final ReplayPrincipalService principals;

  public XrspsSessionsController(
      XrspsSessionExecutionService service, ReplayPrincipalService principals) {
    this.service = service;
    this.principals = principals;
  }

  @PostMapping
  public ResponseEntity<SessionView> create(
      @Valid @RequestBody CreateSessionRequest request, Principal principal) {
    var result =
        service.createResult(
            request.mode(),
            request.idempotencyKey(),
            request.scenarioRef(),
            principals.requirePrincipal(principal));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(SessionView.of(result.session()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<SessionView> inspect(@PathVariable UUID id, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    return ResponseEntity.ok(SessionView.of(service.get(id)));
  }

  @PostMapping("/{id}/start")
  public ResponseEntity<SessionView> start(@PathVariable UUID id, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    return ResponseEntity.ok(SessionView.of(service.start(id)));
  }

  @PostMapping("/{id}/finish-recording")
  public ResponseEntity<XrspsDurableSessionService.RecordingCompletion> finishRecording(
      @PathVariable UUID id, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    return ResponseEntity.ok(service.finishRecording(id));
  }

  @PostMapping("/{id}/pause")
  public ResponseEntity<SessionView> pause(@PathVariable UUID id, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    return ResponseEntity.ok(SessionView.of(service.pause(id)));
  }

  @PostMapping("/{id}/resume")
  public ResponseEntity<SessionView> resume(@PathVariable UUID id, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    return ResponseEntity.ok(SessionView.of(service.resume(id)));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<SessionView> cancel(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) CancelRequest request,
      Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    String reason = request == null ? null : request.reason();
    return ResponseEntity.ok(SessionView.of(service.cancel(id, reason)));
  }

  @PostMapping("/{id}/step")
  public ResponseEntity<SessionView> step(
      @PathVariable UUID id, @Valid @RequestBody StepRequest request, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    return ResponseEntity.ok(SessionView.of(service.stepTicks(id, request.ticks())));
  }

  @PostMapping("/{id}/run")
  public ResponseEntity<RunReportView> run(
      @PathVariable UUID id, @Valid @RequestBody RunJournalRequest request, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    List<XrspsRecordedAction> journal =
        request.actions().stream().map(RecordedActionBody::toAction).toList();
    XrspsRecordedRunReport report = service.runJournal(id, journal);
    return ResponseEntity.ok(RunReportView.of(id, report));
  }

  @PostMapping("/{id}/step-run")
  public ResponseEntity<RunReportView> stepRun(
      @PathVariable UUID id, @Valid @RequestBody StepRequest request, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    XrspsRecordedRunReport report = service.stepJournal(id, request.ticks());
    return ResponseEntity.ok(RunReportView.of(id, report));
  }

  @GetMapping("/{id}/observation")
  public ResponseEntity<ObservationView> observation(@PathVariable UUID id, Principal principal) {
    service.getOwned(id, principals.requirePrincipal(principal));
    XrspsSessionExecutionService.Observation observation = service.observe(id);
    return ResponseEntity.ok(
        new ObservationView(
            observation.sessionId(),
            observation.mode(),
            observation.status(),
            observation.tick(),
            observation.observationRevision(),
            observation.epoch(),
            observation.legalActions(),
            observation.domainState(),
            observation.durable()));
  }

  public record CreateSessionRequest(
      @NotNull XrspsSessionMode mode,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{8,128}$") String idempotencyKey,
      @NotBlank String scenarioRef) {}

  public record CancelRequest(String reason) {}

  public record StepRequest(@Min(1) @Max(1000) int ticks) {}

  public record RecordedActionBody(
      @Min(0) long relativeTick,
      @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String commandId,
      @NotBlank String actionType,
      @NotBlank String payloadHash,
      @Min(0) long expectedRevision,
      com.fasterxml.jackson.databind.JsonNode payload) {
    XrspsRecordedAction toAction() {
      return new XrspsRecordedAction(
          relativeTick, commandId, actionType, payloadHash, expectedRevision, payload);
    }
  }

  public record RunJournalRequest(@NotEmpty @Valid List<@NotNull RecordedActionBody> actions) {}

  public record SessionView(
      UUID id,
      XrspsSessionMode mode,
      XrspsSessionStatus status,
      long tick,
      long observationRevision,
      long epoch,
      String idempotencyKey,
      String scenarioRef) {
    static SessionView of(XrspsSession session) {
      return new SessionView(
          session.getId(),
          session.getMode(),
          session.getStatus(),
          session.getTick(),
          session.getObservationRevision(),
          session.getEpoch(),
          session.getIdempotencyKey(),
          session.getScenarioRef());
    }
  }

  public record RunReportView(
      UUID sessionId,
      int submitted,
      long lastTick,
      long lastRevision,
      boolean completed,
      String haltReason,
      MismatchView firstMismatch,
      List<ReceiptView> receipts) {
    static RunReportView of(UUID sessionId, XrspsRecordedRunReport report) {
      return new RunReportView(
          sessionId,
          report.submitted(),
          report.lastTick(),
          report.lastRevision(),
          report.completed(),
          report.haltReason().name(),
          report.firstMismatch() == null ? null : MismatchView.of(report.firstMismatch()),
          report.receipts().stream().map(ReceiptView::of).toList());
    }
  }

  public record MismatchView(
      String commandId,
      long expectedTick,
      long expectedRevision,
      long actualTick,
      long actualRevision,
      String outcome,
      String detail) {
    static MismatchView of(XrspsActionMismatch mismatch) {
      return new MismatchView(
          mismatch.commandId(),
          mismatch.expectedTick(),
          mismatch.expectedRevision(),
          mismatch.actualTick(),
          mismatch.actualRevision(),
          mismatch.outcome() == null ? null : mismatch.outcome().name(),
          mismatch.detail());
    }
  }

  public record ReceiptView(
      String commandId, String outcome, long tick, long revision, String reason) {
    static ReceiptView of(XrspsSessionReceipt receipt) {
      return new ReceiptView(
          receipt.commandId(),
          receipt.outcome().name(),
          receipt.tick(),
          receipt.revision(),
          receipt.reason());
    }
  }

  public record ObservationView(
      UUID sessionId,
      XrspsSessionMode mode,
      XrspsSessionStatus status,
      long tick,
      long observationRevision,
      long epoch,
      List<String> legalActions,
      com.fasterxml.jackson.databind.JsonNode domainState,
      boolean durable) {}
}
